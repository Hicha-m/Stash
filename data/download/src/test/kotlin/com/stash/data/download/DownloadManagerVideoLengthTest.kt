package com.stash.data.download

import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.model.MusicSource
import com.stash.core.model.QualityTier
import com.stash.core.model.Track
import com.stash.data.download.files.AlbumArtCache
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.MetadataEmbedder
import com.stash.data.download.jiosaavn.JioSaavnResolver
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.tidal.TidalAacResolver
import com.stash.data.download.lyrics.LyricsFetchTrigger
import com.stash.data.download.matching.AlbumMatchExecutor
import com.stash.data.download.matching.DuplicateDetectionService
import com.stash.data.download.matching.HybridSearchExecutor
import com.stash.data.download.matching.MatchScorer
import com.stash.data.download.matching.YtLibraryCanonicalizer
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.shared.TrackFinalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A YouTube music-video row's length is the video's ([TrackQuery.durationReliable] false): the
 * lossless matcher allows it 40% off, but the lossy AAC rungs keep only a file within max(8 s, 3%)
 * of it. Tidal AAC is skipped for such a row instead of asking the relay, downloading, and always
 * throwing the file away; JioSaavn still runs because its matcher applies the same window before
 * anything is fetched; FLAC has no length check past a preview stub, so a full song is kept.
 */
class DownloadManagerVideoLengthTest {

    private val qualityPrefs: QualityPreferencesManager = mockk(relaxed = true) {
        every { qualityTier } returns flowOf(QualityTier.MAX)
    }
    private val losslessUrlDownloader: LosslessUrlDownloader = mockk(relaxed = true)
    private val jioSaavnResolver: JioSaavnResolver = mockk { coEvery { resolve(any(), any()) } returns null }
    private val tidalAacResolver: TidalAacResolver = mockk { coEvery { resolve(any()) } returns null }

    private fun newSubject(): DownloadManager = DownloadManager(
        downloadExecutor = mockk<DownloadExecutor>(relaxed = true),
        searchExecutor = mockk<HybridSearchExecutor>(relaxed = true),
        albumMatchExecutor = mockk<AlbumMatchExecutor>(relaxed = true),
        matchScorer = mockk<MatchScorer>(relaxed = true),
        duplicateDetection = mockk<DuplicateDetectionService>(relaxed = true),
        fileOrganizer = mockk<FileOrganizer>(relaxed = true),
        qualityPrefs = qualityPrefs,
        ytLibraryCanonicalizer = mockk<YtLibraryCanonicalizer>(relaxed = true),
        trackDao = mockk<TrackDao>(relaxed = true),
        playlistDao = mockk<PlaylistDao>(relaxed = true),
        lastFmApiClient = mockk<LastFmApiClient>(relaxed = true),
        lastFmCredentials = mockk<LastFmCredentials>(relaxed = true),
        losslessRegistry = mockk<LosslessSourceRegistry>(relaxed = true),
        losslessUrlDownloader = losslessUrlDownloader,
        losslessPrefs = mockk<LosslessSourcePreferences>(relaxed = true),
        jioSaavnResolver = jioSaavnResolver,
        tidalAacResolver = tidalAacResolver,
        trackFinalizer = mockk<TrackFinalizer>(relaxed = true),
        loudnessMeasurer = mockk<com.stash.core.data.audio.LoudnessMeasurer>(relaxed = true),
        metadataEmbedder = mockk<MetadataEmbedder>(relaxed = true),
        albumArtCache = mockk<AlbumArtCache>(relaxed = true),
        lyricsFetchTrigger = mockk<LyricsFetchTrigger>(relaxed = true),
        audioDurationExtractor = mockk<com.stash.core.data.audio.AudioDurationExtractor>(relaxed = true),
        losslessHealthGate = mockk<com.stash.data.download.lossless.LosslessSourceHealthGate>(relaxed = true),
        localFileOps = mockk<com.stash.core.data.files.LocalFileOps> { every { acceptDownloadOrDelete(any()) } returns true },
        losslessQuota = com.stash.data.download.lossless.quota.LosslessQuota.inMemory(),
    )

    private fun youTubeRow(album: String = "", musicVideoType: String? = null) = Track(
        id = 7L,
        title = "Artist - Song (Official Video)",
        artist = "ArtistVEVO",
        album = album,
        durationMs = 280_000L,
        source = MusicSource.YOUTUBE,
        musicVideoType = musicVideoType,
    )

    @Test
    fun `a YouTube music-video row never asks the Tidal relay for AAC`() = runTest {
        val result = newSubject().tryTidalAacDownload(youTubeRow(musicVideoType = "OMV"))

        assertNull(result)
        coVerify(exactly = 0) { tidalAacResolver.resolve(any()) }
        coVerify(exactly = 0) { losslessUrlDownloader.download(any(), any(), any()) }
    }

    @Test
    fun `an unclassified YouTube row without an album counts as a video too`() = runTest {
        newSubject().tryTidalAacDownload(youTubeRow())

        coVerify(exactly = 0) { tidalAacResolver.resolve(any()) }
    }

    @Test
    fun `an official audio row still asks Tidal for AAC, with its length trusted`() = runTest {
        val asked = slot<TrackQuery>()
        coEvery { tidalAacResolver.resolve(capture(asked)) } returns null

        newSubject().tryTidalAacDownload(youTubeRow(musicVideoType = "ATV"))

        assertTrue(asked.captured.durationReliable)
        assertEquals("Artist", asked.captured.artist)
        assertEquals("Song", asked.captured.title)
    }

    @Test
    fun `a Spotify row still asks Tidal for AAC`() = runTest {
        val spotify = Track(id = 8L, title = "Song", artist = "Artist", durationMs = 210_000L, source = MusicSource.SPOTIFY)

        newSubject().tryTidalAacDownload(spotify)

        coVerify(exactly = 1) { tidalAacResolver.resolve(any()) }
    }

    @Test
    fun `JioSaavn still runs for a YouTube video row`() = runTest {
        val asked = slot<TrackQuery>()
        coEvery { jioSaavnResolver.resolve(capture(asked), any()) } returns null

        newSubject().tryJioSaavnDownload(youTubeRow())

        assertFalse(asked.captured.durationReliable)
        assertEquals(280_000L, asked.captured.durationMs)
    }

    @Test
    fun `the FLAC backstop keeps a full song shorter than the video`() {
        // A 3:30 FLAC for a 4:40 video row is a song, not a preview stub.
        assertFalse(isLosslessDurationDegraded(probedMs = 210_000L, expectedMs = 280_000L))
        assertTrue(isLosslessDurationDegraded(probedMs = 30_000L, expectedMs = 280_000L))
    }
}
