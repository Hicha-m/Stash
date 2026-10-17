package com.stash.data.download.lossless.quota

import android.util.Log
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.LosslessRelayClient
import com.stash.data.download.lossless.relay.RelaySigner
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The cold-start read of the daily lossless allowance: once per process, and only when what
 * [LosslessQuota] has stored is from a previous UTC day (or nothing is stored), ask one relay's
 * quota endpoint — the Qobuz relay's `GET /v1/quota` first, else the Tidal relay's `GET /quota`
 * — so the Settings line and the spent state are right before the first song plays. Every
 * relay answer after that keeps the value current through its headers.
 *
 * Only the owner's relays from the signed config are asked (a user's own endpoint does not keep
 * the shared count), signed `"<install>:quota:<ts>"` by [RelaySigner]. No key or no relay in the
 * config → no request at all.
 */
@Singleton
class LosslessQuotaRefresher @Inject constructor(
    sharedClient: OkHttpClient,
    private val config: LosslessConfigFetcher,
    private val signer: RelaySigner,
    private val quota: LosslessQuota,
) {
    /** Test seam; short timeouts like the relay clients — this is a nicety, never a wait. */
    internal var httpClient: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /** Test seams. */
    internal var nowMs: () -> Long = System::currentTimeMillis
    internal var configWaitMs: Long = CONFIG_WAIT_MS

    /** Runs [refreshIfStale] once on [scope]; called from app start. */
    fun start(scope: CoroutineScope) {
        scope.launch { refreshIfStale() }
    }

    /** True when a quota endpoint answered and the allowance was recorded. Never throws. */
    internal suspend fun refreshIfStale(): Boolean = withContext(Dispatchers.IO) {
        quota.awaitLoaded()
        if (!quota.needsColdRefresh()) return@withContext false
        // The signed config loads from its cache in parallel at startup: give it a moment.
        withTimeoutOrNull(configWaitMs) {
            combine(config.relayKey, config.relays, config.tidalRelay) { key, relays, tidal ->
                key != null && (relays.isNotEmpty() || tidal != null)
            }.first { it }
        } ?: return@withContext false
        val targets = buildList {
            config.relays.value.firstOrNull()?.let { add("${it.base}/v1/quota" to null) }
            config.tidalRelay.value?.let { add("${it.base}/quota" to it.token) }
        }
        targets.any { (url, token) -> fetch(url, token) }
    }

    private suspend fun fetch(url: String, token: String?): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        var mayRetrySkew = true
        while (true) {
            val b = Request.Builder().url(parsed).get()
                .header("X-Stash-Version", LosslessRelayClient.PROTOCOL_VERSION)
                .header("Accept", "application/json")
                .apply { if (token != null) header("X-Access-Token", token) }
            if (!signer.sign(b, "quota", nowMs())) return false
            try {
                httpClient.newCall(b.build()).execute().use { r ->
                    if (r.code == 401 && mayRetrySkew && signer.adoptServerTime(r, nowMs())) {
                        mayRetrySkew = false
                        return@use
                    }
                    quota.onResponse(r)
                    if (!r.isSuccessful) {
                        // Host-free: the Tidal base can carry its access token as a path prefix.
                        Log.i(TAG, "quota endpoint HTTP ${r.code}")
                        return false
                    }
                    val src = r.body?.source() ?: return false
                    if (src.request(MAX_BODY_BYTES + 1)) return false
                    return quota.onQuotaBody(src.readUtf8())
                }
            } catch (e: IOException) {
                Log.i(TAG, "quota endpoint unreachable (${e.javaClass.simpleName})")
                return false
            }
        }
    }

    private companion object {
        const val TAG = "LosslessQuotaRefresh"
        const val TIMEOUT_S = 8L
        const val CONFIG_WAIT_MS = 30_000L
        const val MAX_BODY_BYTES = 16L * 1024
    }
}
