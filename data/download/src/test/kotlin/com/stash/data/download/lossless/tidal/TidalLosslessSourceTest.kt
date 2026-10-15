package com.stash.data.download.lossless.tidal

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.bitchord.BitchordClient
import com.stash.data.download.lossless.bitchord.BitchordStream
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TidalLosslessSourceTest {

    private val client: BitchordClient = mockk()
    private val rateLimiter: AggregatorRateLimiter = mockk(relaxUnitFun = true)
    private val source = TidalLosslessSource(client, rateLimiter)
    private val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky")
    private val id = TidalLosslessSource.SOURCE_ID

    private fun state(broken: Boolean = false) {
        coEvery { rateLimiter.stateOf(id) } returns RateLimitState(2.0, 0L, broken, 0L, 0)
    }

    private val stream = BitchordStream(
        url = "https://relay/dash/td/1.mpd?exp=9&sig=s",
        isDash = true,
        bitDepth = 24,
        sampleRateHz = 96_000,
        bitrateKbps = 0,
        coverArtUrl = "https://img/cover.jpg",
        expiresAtMs = 0L,
        confidence = 0.97f,
        trackId = "td:1",
    )

    @Test fun `not configured means disabled and no lookup`() = runTest {
        state()
        coEvery { client.isConfigured() } returns false
        assertThat(source.isEnabled()).isFalse()
        assertThat(source.resolve(query)).isNull()
        coVerify(exactly = 0) { client.resolve(any()) }
    }

    @Test fun `circuit broken means disabled`() = runTest {
        state(broken = true)
        coEvery { client.isConfigured() } returns true
        assertThat(source.isEnabled()).isFalse()
    }

    @Test fun `maps the stream onto a flac SourceResult marked dash`() = runTest {
        state()
        coEvery { client.isConfigured() } returns true
        coEvery { rateLimiter.acquire(id) } returns true
        coEvery { client.resolve(query) } returns stream

        val r = source.resolve(query)!!

        assertThat(r.sourceId).isEqualTo(id)
        assertThat(r.downloadUrl).isEqualTo(stream.url)
        assertThat(r.isDash).isTrue()
        assertThat(r.format.codec).isEqualTo("flac")
        assertThat(r.format.fileExtension).isEqualTo("flac")
        assertThat(r.format.bitsPerSample).isEqualTo(24)
        assertThat(r.format.sampleRateHz).isEqualTo(96_000)
        assertThat(r.confidence).isEqualTo(0.97f)
        assertThat(r.sourceTrackId).isEqualTo("td:1")
        assertThat(r.coverArtUrl).isEqualTo("https://img/cover.jpg")
        coVerify { rateLimiter.reportSuccess(id) }
    }

    @Test fun `rate-limited background resolve returns null without calling the relay`() = runTest {
        state()
        coEvery { client.isConfigured() } returns true
        coEvery { rateLimiter.acquire(id) } returns false
        assertThat(source.resolve(query)).isNull()
        coVerify(exactly = 0) { client.resolve(any()) }
    }

    @Test fun `bypass skips the token bucket`() = runTest {
        state()
        coEvery { client.isConfigured() } returns true
        coEvery { client.resolve(query) } returns stream.copy(isDash = false)
        val r = source.resolve(query, bypassRateLimit = true)!!
        assertThat(r.isDash).isFalse()
        coVerify(exactly = 0) { rateLimiter.acquire(any()) }
    }

    @Test fun `a throw counts as a failure`() = runTest {
        state()
        coEvery { client.isConfigured() } returns true
        coEvery { rateLimiter.acquire(id) } returns true
        coEvery { client.resolve(query) } throws IllegalStateException("boom")
        assertThat(source.resolve(query)).isNull()
        coVerify { rateLimiter.reportFailure(id) }
    }
}
