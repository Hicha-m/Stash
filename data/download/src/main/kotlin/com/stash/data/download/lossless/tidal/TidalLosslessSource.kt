package com.stash.data.download.lossless.tidal

import android.util.Log
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.bitchord.BitchordClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * [LosslessSource] for Tidal through the BitChord relay — the same server and
 * matching the streaming rung uses ([BitchordClient]), so a track that streams
 * from Tidal also downloads from it. DASH answers come back with
 * [SourceResult.isDash] set; the downloader fetches the segments and remuxes.
 *
 * [BitchordClient.resolve] folds "no match" and I/O errors into null, so only a
 * thrown exception counts against the breaker; a miss is neither success nor failure.
 */
@Singleton
class TidalLosslessSource @Inject constructor(
    private val client: BitchordClient,
    private val rateLimiter: AggregatorRateLimiter,
) : LosslessSource {

    override val id: String = SOURCE_ID
    override val displayName: String = "Tidal"

    override suspend fun isEnabled(): Boolean =
        !rateLimiter.stateOf(id).isCircuitBroken && client.isConfigured()

    override suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean): SourceResult? {
        if (!isEnabled()) return null
        if (!bypassRateLimit && !rateLimiter.acquire(id)) return null
        val stream = try {
            client.resolve(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            rateLimiter.reportFailure(id)
            Log.w(TAG, "resolve threw ${e.javaClass.simpleName}")
            return null
        } ?: return null
        rateLimiter.reportSuccess(id)
        return SourceResult(
            sourceId = id,
            downloadUrl = stream.url,
            downloadHeaders = emptyMap(), // relay URLs are HMAC-signed; no token needed
            format = AudioFormat(
                codec = "flac",
                bitrateKbps = stream.bitrateKbps,
                sampleRateHz = stream.sampleRateHz,
                bitsPerSample = stream.bitDepth,
                fileExtension = "flac",
            ),
            confidence = stream.confidence,
            sourceTrackId = stream.trackId,
            coverArtUrl = stream.coverArtUrl,
            isDash = stream.isDash,
        )
    }

    override suspend fun rateLimitState(): RateLimitState = rateLimiter.stateOf(id)

    companion object {
        const val SOURCE_ID = "tidal_bitchord"
        private const val TAG = "TidalLosslessSource"
    }
}
