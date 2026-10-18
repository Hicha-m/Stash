package com.stash.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.files.LocalFileOps
import com.stash.core.data.files.LocalFileState
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cold-start fixups must never touch a user's playlist memberships.
 *
 * A removed startup "dedup" cleared EVERY playlist_tracks row (locally added
 * ones too) of any playlist holding more downloaded members than its cached
 * `track_count`. That count is the remote snapshot size, so it fired on
 * ordinary drift: ACCUMULATE keeps a song the user un-liked on Spotify (N
 * downloaded members over a count of N-1), and a REFRESH playlist keeps the
 * user's own "Save to Playlist" adds (#42) on top of the remote list. In
 * REFRESH the orphan sweep that runs right after then deleted those songs and
 * their files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MusicRepositoryRunMigrationsTest {

    private lateinit var db: StashDatabase
    private var playlistId = 0L
    private val syncPrefs = mockk<SyncPreferencesManager>(relaxed = true)
    private val localFileOps = mockk<LocalFileOps>(relaxed = true)

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Every downloaded file is present: the integrity sweep leaves them alone.
        every { localFileOps.classify(any(), any()) } returns LocalFileState.OK
        db.trackDao().insert(downloaded(1L))
        db.trackDao().insert(downloaded(2L))
        db.trackDao().insert(downloaded(3L))
        // The remote list now holds ONE song: track_count = 1 while three
        // downloaded members remain (one retained by ACCUMULATE, one the user
        // added in Stash).
        playlistId = db.playlistDao().insert(
            PlaylistEntity(
                name = "Liked Songs", source = MusicSource.SPOTIFY, sourceId = "liked",
                type = PlaylistType.LIKED_SONGS, trackCount = 1,
            ),
        )
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = 1L, position = 0))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = 2L, position = 1))
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(playlistId = playlistId, trackId = 3L, position = 2, locallyAdded = true),
        )
    }

    @After fun tearDown() = db.close()

    @Test fun `ACCUMULATE drift above track_count keeps every membership`() = runTest {
        coEvery { syncPrefs.anyAccumulate() } returns true

        repo().runMigrations()

        assertEquals(setOf(1L, 2L, 3L), memberIds())
    }

    @Test fun `REFRESH keeps the members and the songs, locally added ones included`() = runTest {
        coEvery { syncPrefs.anyAccumulate() } returns false

        repo().runMigrations()

        assertEquals(setOf(1L, 2L, 3L), memberIds())
        assertEquals(3, db.trackDao().getByIds(listOf(1L, 2L, 3L)).size)
    }

    private suspend fun memberIds(): Set<Long> =
        db.playlistDao().getCrossRefsForPlaylist(playlistId)
            .filter { it.removedAt == null }
            .map { it.trackId }
            .toSet()

    private fun downloaded(id: Long) = TrackEntity(
        id = id, title = "T$id", artist = "A$id", source = MusicSource.SPOTIFY,
        canonicalTitle = "t$id", canonicalArtist = "a$id",
        isDownloaded = true, filePath = "/music/$id.opus", fileSizeBytes = 4_000_000L,
    )

    private fun repo() = MusicRepositoryImpl(
        context = mockk(relaxed = true),
        trackDao = db.trackDao(),
        playlistDao = db.playlistDao(),
        syncHistoryDao = mockk(relaxed = true),
        downloadQueueDao = mockk(relaxed = true),
        discoveryQueueDao = mockk(relaxed = true),
        blocklistGuard = mockk(relaxed = true),
        trackMatcher = mockk(relaxed = true),
        stashMixRecipeDao = mockk(relaxed = true),
        downloadNetworkPreference = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        libraryScopePreference = mockk(relaxed = true),
        localFileOps = localFileOps,
        syncPreferencesManager = syncPrefs,
        singleTrackDownloadEnqueuer = mockk(relaxed = true),
        lastFmRecommendationSource = mockk(relaxed = true),
        sharedMixDao = db.sharedMixDao(),
        downloadJobs = com.stash.core.data.sync.DownloadJobRegistry(),
    )
}
