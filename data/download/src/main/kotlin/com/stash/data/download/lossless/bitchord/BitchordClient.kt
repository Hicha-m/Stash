package com.stash.data.download.lossless.bitchord

import android.util.Log
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.TidalRelay
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** One `/search` row from a BitChord server (`app/schemas.py` TrackItem; extra keys ignored). */
@Serializable
internal data class BitchordTrack(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** SECONDS (Tidal's unit, rounded server-side). */
    val duration: Int = 0,
    val artwork: String? = null,
    val audioQuality: String = "",
    val isrc: String? = null,
)

@Serializable
internal data class BitchordSearchResponse(val tracks: List<BitchordTrack> = emptyList())

/** `/stream/{id}` body (`app/schemas.py` StreamResponse). */
@Serializable
internal data class BitchordStreamResponse(
    val url: String = "",
    val codec: String = "",
    val container: String = "",
    /** `"dash"` for a Tidal MPD, `"none"` for a progressive file. */
    val manifest: String = "none",
    val bitDepth: Int = 0,
    val sampleRate: Int = 0,
    val bitrate: Int = 0,
)

/**
 * A playable Tidal stream from a BitChord server. [isDash] = [url] is an MPD
 * (`/dash/td/<id>.mpd?exp&sig`) whose init segment the server has patched; else
 * [url] is a progressive FLAC (Tidal CDN, or the server's signed `/audio/` proxy).
 */
data class BitchordStream(
    val url: String,
    val isDash: Boolean,
    val bitDepth: Int,
    val sampleRateHz: Int,
    val bitrateKbps: Int,
    val coverArtUrl: String?,
    /** Absolute wall-clock expiry of [url], epoch-ms. */
    val expiresAtMs: Long,
)

/**
 * Client for a BitChord Lossless Relay (the owner-hosted one from the signed config, or a user override) (upstream
 * `R1Blmmakan/music_addon`, a FastAPI service), used as the Tidal rung of the
 * streaming chain — after Qobuz, before the lossy sources.
 *
 * Contract used (all derived from upstream `app/main.py`):
 *  - `GET {base}/search?q=` → `{tracks:[{id:"td:<n>"|"dz:<n>", title, artist,
 *    album, duration(s), artwork, audioQuality, isrc}]}`. Only `td:` rows whose
 *    `audioQuality` is LOSSLESS / HI_RES_LOSSLESS are considered.
 *  - `GET {base}/stream/td:<n>?client=media3` → `{url, codec, container,
 *    manifest:"dash"|"none", bitDepth, sampleRate, bitrate}`, or 404 when the
 *    track has no FLAC (the server rejects lossy itself). `client=media3` pins the
 *    server's DASH-vs-progressive sniffing to "native DASH client" so it never
 *    swaps the Tidal MPD for a Deezer progressive file meant for browsers.
 *  - Auth: when the server sets `ACCESS_TOKEN`, either the base carries it as a
 *    path prefix (`https://host/<token>`, BitChord's own format) or it goes in
 *    `X-Access-Token`. The `/dash/` and `/audio/` URLs it hands back are HMAC-signed
 *    with an `exp` (1 h) and need no token.
 *
 * Matching reuses [QobuzCandidateMatcher] — the scorer the Qobuz rung uses — on
 * neutral fields, so ISRC equality, title/artist normalisation, version words and
 * the duration penalty all behave identically across the two lossless rungs.
 */
@Singleton
class BitchordClient @Inject constructor(
    sharedClient: OkHttpClient,
    private val prefs: LosslessSourcePreferences,
    private val config: LosslessConfigFetcher,
) {
    /**
     * Short timeouts: the server itself caps `/stream` at 7 s, and a self-hosted
     * box that is down must fail over to the lossy rungs fast.
     */
    internal var httpClient: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Non-suspending view of [isConfigured] for the player's media-source factory,
     * which runs on the playback thread and must not block on DataStore. Eager, so
     * it has settled long before the first track is prepared.
     */
    val configured: StateFlow<Boolean> = combine(prefs.bitchordEndpoint, config.tidalRelay) { user, signed ->
        user != null || signed != null
    }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, false)

    /** True when a server is known (the user's own, or the signed config's); the rung is off otherwise. */
    suspend fun isConfigured(): Boolean = server() != null

    /**
     * The server to use: the user's override from Settings when set — an explicit
     * choice beats an implicit one, and its token goes with it (never the config's,
     * which belongs to another server) — else the owner-hosted one from the signed
     * runtime config ([LosslessConfigFetcher.tidalRelay]). Null = rung off.
     */
    private suspend fun server(): TidalRelay? {
        prefs.bitchordEndpointNow()?.let { return TidalRelay(it, prefs.bitchordAccessTokenNow()) }
        return config.tidalRelay.first()
    }

    /**
     * Search, pick the best confident `td:` match, then resolve its stream. Null on
     * no server, no confident match, a non-FLAC answer, or any I/O error — the
     * caller just moves to the next rung.
     */
    suspend fun resolve(query: TrackQuery): BitchordStream? = withContext(Dispatchers.IO) {
        val server = server() ?: return@withContext null
        val base = server.base.toHttpUrlOrNull() ?: return@withContext null
        val token = server.token
        try {
            val searchUrl = base.newBuilder()
                .addPathSegment("search")
                .addQueryParameter("q", "${query.artist} ${query.title}".trim())
                .build()
            val tracks = json.decodeFromString<BitchordSearchResponse>(get(searchUrl, token) ?: return@withContext null).tracks
            val best = tracks
                .asSequence()
                .filter { it.id.startsWith("td:") && it.audioQuality in LOSSLESS_TIERS }
                .map { it to confidence(query, it) }
                .filter { it.second >= QobuzCandidateMatcher.MIN_CONFIDENCE }
                .maxByOrNull { it.second }
            if (best == null) {
                Log.d(TAG, "no confident match among ${tracks.size} for '${query.artist} - ${query.title}'")
                return@withContext null
            }
            val (track, conf) = best
            val streamUrl = base.newBuilder()
                .addPathSegment("stream")
                .addPathSegment(track.id)
                .addQueryParameter("client", "media3")
                // Hints the server uses if its own Tidal metadata lookup fails.
                .addQueryParameter("title", track.title)
                .addQueryParameter("artist", track.artist)
                .build()
            val stream = json.decodeFromString<BitchordStreamResponse>(get(streamUrl, token) ?: return@withContext null)
            toStream(stream, track, conf)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Message only: an IOException's text can carry the URL, and the base
            // may hold the access token as its path prefix.
            Log.w(TAG, "resolve failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun toStream(s: BitchordStreamResponse, track: BitchordTrack, conf: Float): BitchordStream? {
        val url = s.url.toHttpUrlOrNull() ?: return null
        // Defensive: upstream already refuses lossy, but a future server change
        // must not slip AAC into a rung the UI labels lossless.
        if (!s.codec.equals("flac", ignoreCase = true)) {
            Log.d(TAG, "non-flac codec '${s.codec}' for ${track.id}; skipping")
            return null
        }
        val isDash = s.manifest.equals("dash", ignoreCase = true)
        Log.d(TAG, "resolved ${track.id} conf=$conf dash=$isDash ${s.bitDepth}/${s.sampleRate}")
        return BitchordStream(
            url = url.toString(),
            isDash = isDash,
            bitDepth = s.bitDepth,
            sampleRateHz = s.sampleRate,
            bitrateKbps = s.bitrate,
            coverArtUrl = track.artwork?.takeIf { it.isNotBlank() },
            expiresAtMs = expiryFor(url),
        )
    }

    /**
     * The URL's lifetime: the server's signed `exp` (epoch seconds) when present,
     * capped at [MAX_TTL_MS] either way. The cap matters for DASH: the MPD names
     * Tidal CDN segment URLs whose own signatures outlive neither the MPD's nor an
     * hour, and an unsigned (open-mode) server gives no `exp` at all.
     */
    private fun expiryFor(url: HttpUrl): Long {
        val now = System.currentTimeMillis()
        val cap = now + MAX_TTL_MS
        val exp = url.queryParameter("exp")?.toDoubleOrNull()?.toLong()?.times(1000L) ?: return cap
        return minOf(exp, cap)
    }

    private fun confidence(query: TrackQuery, t: BitchordTrack): Float =
        QobuzCandidateMatcher.confidence(
            query = query,
            candTitle = t.title,
            candArtist = t.artist,
            candIsrc = t.isrc,
            candDurationSec = t.duration,
            candStreamable = true,
        )

    /** Body of a 2xx, null on any other status (404 = "no FLAC" / "no match"). */
    private fun get(url: HttpUrl, token: String?): String? {
        val req = Request.Builder().url(url).get()
            .header("Accept", "application/json")
            .apply { if (token != null) header("X-Access-Token", token) }
            .build()
        httpClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                // Code only: the path may carry the access token as its prefix.
                Log.d(TAG, "HTTP ${r.code}")
                return null
            }
            val body = r.body ?: throw IOException("empty body")
            if (body.contentLength() > MAX_BODY_BYTES) throw IOException("body too large")
            return body.string()
        }
    }

    private companion object {
        const val TAG = "BitchordClient"
        const val TIMEOUT_S = 10L
        const val MAX_BODY_BYTES = 1L * 1024 * 1024
        /** Below upstream's 1 h signature so the cache's margin never serves a dead URL. */
        const val MAX_TTL_MS = 50 * 60_000L
        val LOSSLESS_TIERS = setOf("LOSSLESS", "HI_RES_LOSSLESS")
    }
}
