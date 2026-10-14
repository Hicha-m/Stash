package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies `ArtistImageDao.distinctArtistNames(includeNotDownloaded)` — the candidate set the
 * [com.stash.core.data.sync.workers.ArtistImageBackfillWorker] walks. It must equal the credits
 * the Library Artists tab builds its cards from (`TrackDao.getAllArtists(includeNotDownloaded)`)
 * in both states of the Download switch: downloaded tracks only in Download, every track in
 * Stream only. Every name costs an InnerTube search, so nothing the tab doesn't show may get in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ArtistImageDaoTest {

    private lateinit var db: StashDatabase
    private lateinit var trackDao: TrackDao
    private lateinit var playlistDao: PlaylistDao
    private lateinit var artistImageDao: ArtistImageDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        trackDao = db.trackDao()
        playlistDao = db.playlistDao()
        artistImageDao = db.artistImageDao()
    }

    @After fun tearDown() = db.close()

    // ── Download (includeNotDownloaded = false) ─────────────────────────

    @Test fun `download - includes downloaded artists`() = runTest {
        insertDownloaded(id = 1L, artist = "Drake")

        assertEquals(listOf("Drake"), artistImageDao.distinctArtistNames(includeNotDownloaded = false))
    }

    @Test fun `download - excludes artists with nothing downloaded, as the tab does`() = runTest {
        insertStreamableOnly(id = 1L, artist = "Future")
        insertUnavailable(id = 2L, artist = "Travis")
        // A not-downloaded member of a live playlist: listed by the playlist, not by the tab.
        val unchecked = insertUnchecked(id = 3L, artist = "Aarne")
        val playlistId = playlistDao.insert(activePlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, unchecked, position = 0))

        assertTrue(artistImageDao.distinctArtistNames(includeNotDownloaded = false).isEmpty())
    }

    // ── Stream only (includeNotDownloaded = true) ───────────────────────

    @Test fun `stream only - includes every artist the tab lists, downloaded or not`() = runTest {
        insertDownloaded(id = 1L, artist = "Drake")
        insertStreamableOnly(id = 2L, artist = "Future")
        insertUnavailable(id = 3L, artist = "Travis")
        insertUnchecked(id = 4L, artist = "Aarne")

        assertEquals(
            setOf("Drake", "Future", "Travis", "Aarne"),
            artistImageDao.distinctArtistNames(includeNotDownloaded = true).toSet(),
        )
    }

    @Test fun `stream only - matches the credits the Artists tab is built from`() = runTest {
        insertDownloaded(id = 1L, artist = "Drake")
        insertUnchecked(id = 2L, artist = "Aarne, Toxi$")
        insertUnchecked(id = 3L, artist = "Future")

        for (includeNotDownloaded in listOf(false, true)) {
            assertEquals(
                "includeNotDownloaded=$includeNotDownloaded",
                trackDao.getAllArtists(includeNotDownloaded).first().map { it.artist }.toSet(),
                artistImageDao.distinctArtistNames(includeNotDownloaded).toSet(),
            )
        }
    }

    // ── Both ────────────────────────────────────────────────────────────

    @Test fun `dedupes repeated credits`() = runTest {
        insertDownloaded(id = 1L, artist = "Drake")
        insertDownloaded(id = 2L, artist = "Drake")
        insertUnchecked(id = 3L, artist = "Drake")

        assertEquals(listOf("Drake"), artistImageDao.distinctArtistNames(includeNotDownloaded = false))
        assertEquals(listOf("Drake"), artistImageDao.distinctArtistNames(includeNotDownloaded = true))
    }

    @Test fun `excludes empty artist credits`() = runTest {
        insertDownloaded(id = 1L, artist = "")
        insertUnchecked(id = 2L, artist = "")

        assertTrue("blank credit must not be resolved", artistImageDao.distinctArtistNames(false).isEmpty())
        assertTrue("blank credit must not be resolved", artistImageDao.distinctArtistNames(true).isEmpty())
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private suspend fun insertDownloaded(id: Long, artist: String): Long {
        trackDao.insert(
            TrackEntity(
                id = id,
                title = "Title $id",
                artist = artist,
                album = "Album $id",
                canonicalTitle = "title $id",
                canonicalArtist = artist.lowercase(),
                isDownloaded = true,
                isStreamable = false,
                isStreamableCheckedAt = null,
            )
        )
        return id
    }

    private suspend fun insertStreamableOnly(id: Long, artist: String): Long {
        trackDao.insert(
            TrackEntity(
                id = id,
                title = "Title $id",
                artist = artist,
                album = "Album $id",
                canonicalTitle = "title $id",
                canonicalArtist = artist.lowercase(),
                isDownloaded = false,
                isStreamable = true,
                isStreamableCheckedAt = 1_000_000L,
            )
        )
        return id
    }

    private suspend fun insertUnavailable(id: Long, artist: String): Long {
        trackDao.insert(
            TrackEntity(
                id = id,
                title = "Title $id",
                artist = artist,
                album = "Album $id",
                canonicalTitle = "title $id",
                canonicalArtist = artist.lowercase(),
                isDownloaded = false,
                isStreamable = false,
                isStreamableCheckedAt = 1_000_000L,
            )
        )
        return id
    }

    private suspend fun insertUnchecked(id: Long, artist: String): Long {
        trackDao.insert(
            TrackEntity(
                id = id,
                title = "Title $id",
                artist = artist,
                album = "Album $id",
                canonicalTitle = "title $id",
                canonicalArtist = artist.lowercase(),
                isDownloaded = false,
                isStreamable = false,
                isStreamableCheckedAt = null,
            )
        )
        return id
    }

    private fun activePlaylist(isActive: Boolean = true) = PlaylistEntity(
        name = "Test Playlist",
        source = MusicSource.BOTH,
        sourceId = "test_playlist_${System.nanoTime()}",
        type = PlaylistType.CUSTOM,
        trackCount = 0,
        syncEnabled = true,
        isActive = isActive,
    )

    private fun crossRef(playlistId: Long, trackId: Long, position: Int, removed: Boolean = false) =
        PlaylistTrackCrossRef(
            playlistId = playlistId,
            trackId = trackId,
            position = position,
            addedAt = Instant.EPOCH,
            removedAt = if (removed) Instant.now() else null,
        )
}