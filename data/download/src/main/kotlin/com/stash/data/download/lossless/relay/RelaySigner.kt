package com.stash.data.download.lossless.relay

import android.util.Log
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Request
import okhttp3.Response

/**
 * Signs requests to the owner's relays (spec §5.4), shared by the Qobuz relay client and the
 * Tidal relay client so both carry the same install identity and the same learned clock offset.
 *
 * `X-Stash-Auth` = hex HMAC-SHA256 with the signed config's `relay_key` over
 * `"<install_id>:<payload>:<unix_ts>"`, with `X-Stash-Install` and `X-Stash-Ts` alongside. The
 * payload names what is asked (`<track_id>:<format_id>` for a Qobuz mint, `td:<id>` for a Tidal
 * stream, `quota` for the allowance endpoints). The install id sits INSIDE the MAC so a captured
 * header cannot be replayed under another install's allowance; the timestamp lets the relay
 * reject anything outside its ±5 min window.
 *
 * No key → no headers. A user-run server built to the public contract needs none.
 *
 * Callers pass their own clock (each client has a test seam for it); the offset is the one
 * piece of state, so a skew learned from either relay fixes both.
 */
@Singleton
class RelaySigner @Inject constructor(
    private val config: LosslessConfigFetcher,
) {
    /**
     * Relay time minus device time, seconds; added to every signed timestamp. In memory only:
     * a clock fixed while the app runs costs one more 401 + retry, and a restart starts at 0.
     */
    @Volatile var clockOffsetSec: Long = 0L
        private set

    /** True when the applied config carries a relay key, i.e. [sign] would sign. */
    val keyed: Boolean get() = config.relayKey.value != null

    /**
     * Signs [b] over [payload] at [nowMs] (+ [clockOffsetSec]). Returns true when it signed.
     *
     * A DataStore failure fetching the install id falls back to a transient id rather than
     * throwing: callers run this BEFORE their HTTP try, and an exception here would escape into
     * a source's breaker (QbdlxQobuzSource trips qbdlx wholesale).
     */
    suspend fun sign(b: Request.Builder, payload: String, nowMs: Long): Boolean {
        val key = config.relayKey.value ?: return false
        val install = runCatching { config.installId() }.getOrElse { UUID.randomUUID().toString() }
        val ts = nowMs / 1000 + clockOffsetSec
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
        val sig = mac.doFinal("$install:$payload:$ts".toByteArray()).joinToString("") { "%02x".format(it) }
        b.header("X-Stash-Install", install).header("X-Stash-Ts", ts.toString()).header("X-Stash-Auth", sig)
        return true
    }

    /**
     * On a 401 carrying `X-Stash-Server-Time` (epoch seconds, sent for signature / timestamp
     * failures), record the device's offset from it. False when the header is absent or junk:
     * the 401 is then handled exactly as before.
     */
    fun adoptServerTime(r: Response, nowMs: Long): Boolean {
        val server = r.header("X-Stash-Server-Time")?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: return false
        clockOffsetSec = server - nowMs / 1000
        Log.i(TAG, "relay clock offset ${clockOffsetSec}s; re-signing")
        return true
    }

    private companion object {
        const val TAG = "RelaySigner"
    }
}
