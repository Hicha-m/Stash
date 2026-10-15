package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.bitchord.BitchordClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stream-URL resolver for Tidal lossless via the user's self-hosted BitChord
 * Lossless Relay ([BitchordClient]). The second lossless rung: after qbdlx
 * (Qobuz), before the lossy JioSaavn / YouTube rungs.
 *
 * Returns either a progressive FLAC URL or, for most Hi-Res tracks, a DASH MPD
 * ([StreamUrl.isDash]); the server patches the fMP4 init segment's zero sample
 * rate itself and re-signs it inside the MPD, so the client plays the MPD as-is
 * through a DASH media source — see [StashMediaSourceFactory].
 *
 * Expiry: the server's signed `exp`, capped below an hour ([BitchordClient]),
 * which is what [StreamUrlCache] counts down.
 *
 * Returns null when no server is configured, there is no confident match, or the
 * track has no FLAC on Tidal.
 */
@Singleton
class TidalStreamResolver @Inject constructor(
    private val client: BitchordClient,
) {
    /** A server is configured (non-blocking; see [BitchordClient.configured]). */
    fun enabledHint(): Boolean = client.configured.value

    suspend fun resolve(track: TrackEntity): StreamUrl? {
        if (!client.isConfigured()) return null
        val query = TrackQuery(
            artist = track.artist,
            title = track.title,
            album = track.album.takeIf { it.isNotBlank() },
            isrc = track.isrc?.takeIf { it.isNotBlank() },
            durationMs = track.durationMs,
        )
        // allowAac: a CD-only track streams as Tidal's 320 kbps AAC rather than
        // falling through to the lossy rungs below (JioSaavn / YouTube).
        val s = client.resolve(query, allowAac = true) ?: run {
            Log.d(TAG, "no_result id=${track.id}")
            return null
        }
        return StreamUrl(
            url = s.url,
            expiresAtMs = s.expiresAtMs,
            codec = s.codec,
            bitsPerSample = s.bitDepth.takeIf { it > 0 && s.codec == "flac" },
            sampleRateHz = s.sampleRateHz.takeIf { it > 0 },
            bitrateKbps = s.bitrateKbps.takeIf { it > 0 },
            coverArtUrl = s.coverArtUrl,
            origin = ORIGIN,
            isDash = s.isDash,
        )
    }

    companion object {
        private const val TAG = "TidalStreamResolver"
        const val ORIGIN = "tidal"
    }
}
