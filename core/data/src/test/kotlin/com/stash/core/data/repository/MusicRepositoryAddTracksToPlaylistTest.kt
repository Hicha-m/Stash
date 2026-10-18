package com.stash.core.data.repository

import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SharedMixDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** #479: a bulk add must finish even when the screen that started it goes away. */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicRepositoryAddTracksToPlaylistTest {

    private val added = mutableListOf<Long>()
    private val batches = mutableListOf<List<PlaylistTrackCrossRef>>()
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val playlistDao = mockk<PlaylistDao>(relaxed = true)
    private val sharedMixDao = mockk<SharedMixDao>(relaxed = true)

    init {
        coEvery { trackDao.getById(any()) } answers { track(firstArg()) }
        every { trackDao.getByPlaylist(PID, includeStreamable = true) } returns flowOf(emptyList())
        coEvery { playlistDao.getById(PID) } returns PlaylistEntity(
            id = PID, name = "Mix", source = MusicSource.BOTH, sourceId = "x", type = PlaylistType.CUSTOM,
        )
        coEvery { playlistDao.getNextPosition(PID) } returns 5
        // Each insert suspends, like a real Room write, so a cancelled caller would stop here.
        coEvery { playlistDao.insertCrossRef(any()) } coAnswers {
            delay(100)
            added += firstArg<PlaylistTrackCrossRef>().trackId
        }
        coEvery { playlistDao.insertAllCrossRefs(any()) } coAnswers {
            delay(100)
            val refs = firstArg<List<PlaylistTrackCrossRef>>()
            batches.add(refs)
            added += refs.map { it.trackId }
        }
        coEvery { sharedMixDao.forPlaylist(any()) } returns null
    }

    @Test fun `adds each id once in order`() = runTest {
        repo().addTracksToPlaylist(listOf(3L, 1L, 2L), PID)
        assertEquals(listOf(3L, 1L, 2L), added)
    }

    @Test fun `cancelling the caller mid-batch still adds every id`() = runTest {
        val ids = (1L..20L).toList()
        val caller = launch { repo().addTracksToPlaylist(ids, PID) }
        runCurrent()
        caller.cancel()
        caller.join()
        assertEquals(ids, added)
    }

    @Test fun `cancelling the caller mid-create still gives the new playlist every id`() = runTest {
        coEvery { playlistDao.insert(any()) } coAnswers { delay(100); PID }
        val ids = (1L..5L).toList()
        val caller = launch { repo().createPlaylistWithTracks("Road trip", ids) }
        runCurrent() // now suspended inside the playlist insert
        caller.cancel()
        caller.join()
        assertEquals(ids, added)
    }

    @Test fun `one failing track does not stop the rest`() = runTest {
        coEvery { trackDao.getById(2L) } throws IllegalStateException("boom")
        repo().addTracksToPlaylist(listOf(1L, 2L, 3L), PID)
        assertEquals(listOf(1L, 3L), added)
    }

    /**
     * A multi-select add used to autocommit and fully reload the playlist once
     * per song (O(n^2) row loads). It is one insert at consecutive positions,
     * every row user-added, and one COUNT-based recount.
     */
    @Test fun `a batch is one insert at consecutive positions and one recount`() = runTest {
        coEvery { trackDao.countByPlaylist(PID, includeStreamable = true) } returns 8
        repo().addTracksToPlaylist(listOf(3L, 1L, 2L), PID)

        assertEquals(1, batches.size)
        assertEquals(listOf(5, 6, 7), batches.single().map { it.position })
        assertEquals(true, batches.single().all { it.locallyAdded })
        coVerify(exactly = 0) { playlistDao.insertCrossRef(any()) }
        coVerify(exactly = 0) { trackDao.getByPlaylist(any(), any()) }
        coVerify(exactly = 1) { playlistDao.updateTrackCount(PID, 8) }
    }

    @Test fun `a batch that races a delete falls back to one insert per song`() = runTest {
        coEvery { playlistDao.insertAllCrossRefs(any()) } throws
            android.database.sqlite.SQLiteConstraintException("FOREIGN KEY constraint failed")
        coEvery { playlistDao.insertCrossRef(match { it.trackId == 2L }) } throws
            android.database.sqlite.SQLiteConstraintException("FOREIGN KEY constraint failed")
        repo().addTracksToPlaylist(listOf(1L, 2L, 3L), PID)
        assertEquals(listOf(1L, 3L), added)
    }

    @Test fun `a failing batch write is logged, never thrown at the screen that started it`() = runTest {
        coEvery { playlistDao.insertAllCrossRefs(any()) } throws IllegalStateException("disk full")
        repo().addTracksToPlaylist(listOf(1L, 2L), PID) // must not throw
        assertEquals(emptyList<Long>(), added)
    }

    private fun track(id: Long) = TrackEntity(
        id = id, title = "T$id", artist = "A$id", durationMs = 1000L, source = MusicSource.SPOTIFY,
        canonicalTitle = "t$id", canonicalArtist = "a$id",
    )

    private fun repo() = MusicRepositoryImpl(
        context = mockk(relaxed = true),
        trackDao = trackDao,
        playlistDao = playlistDao,
        syncHistoryDao = mockk(relaxed = true),
        downloadQueueDao = mockk(relaxed = true),
        discoveryQueueDao = mockk(relaxed = true),
        blocklistGuard = mockk(relaxed = true),
        trackMatcher = mockk(relaxed = true),
        stashMixRecipeDao = mockk(relaxed = true),
        downloadNetworkPreference = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        libraryScopePreference = mockk(relaxed = true),
        localFileOps = mockk(relaxed = true),
        syncPreferencesManager = mockk(relaxed = true),
        singleTrackDownloadEnqueuer = mockk(relaxed = true),
        lastFmRecommendationSource = mockk(relaxed = true),
        sharedMixDao = sharedMixDao,
        downloadJobs = com.stash.core.data.sync.DownloadJobRegistry(),
    )

    private companion object {
        const val PID = 7L
    }
}
