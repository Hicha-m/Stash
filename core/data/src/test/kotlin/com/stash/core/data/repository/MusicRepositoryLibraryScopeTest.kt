package com.stash.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The owner's rule for the Library's Artists and Albums: the Download switch
 * ([StreamingPreference.enabled]) decides what they list. Stream only (true) = every song,
 * Download (false) = downloaded songs only. Covers the repository flows the tabs, the artist and
 * album pages and the card actions read, over a real Room database so the SQL and the credit
 * filter are exercised together, and checks that an open flow follows a flip of the switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MusicRepositoryLibraryScopeTest {

    private lateinit var db: StashDatabase
    private val streamOnly = MutableStateFlow(false)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() { db.close() }

    @Test fun `artist page lists downloaded songs in Download and every song in Stream only`() = runTest {
        insert(1L, artist = "Drake", album = "Scorpion", downloaded = true)
        insert(2L, artist = "Drake, Future", album = "WATTBA", downloaded = false)
        insert(3L, artist = "Someone Else", album = "Mix", albumArtist = "Drake", downloaded = false)
        insert(4L, artist = "Drakeo the Ruler", album = "X", downloaded = true) // LIKE hit, not a credit

        streamOnly.value = false
        assertEquals(listOf(1L), repo().getTracksByArtist("Drake").first().map { it.id })

        streamOnly.value = true
        assertEquals(setOf(1L, 2L, 3L), repo().getTracksByArtist("Drake").first().map { it.id }.toSet())
    }

    @Test fun `album page lists the album's songs crediting the card's artist, by the same rule`() = runTest {
        insert(1L, artist = "Metro Boomin, Future", album = "HEROES & VILLAINS", downloaded = true)
        insert(2L, artist = "Metro Boomin, Travis Scott", album = "heroes & villains", downloaded = false)
        insert(3L, artist = "Don Toliver", album = "HEROES & VILLAINS", downloaded = false) // no credit
        insert(4L, artist = "Metro Boomin", album = "NOT ALL HEROES", downloaded = true) // other album

        streamOnly.value = false
        assertEquals(
            listOf(1L),
            repo().getTracksByAlbum("HEROES & VILLAINS", "Metro Boomin").first().map { it.id },
        )

        streamOnly.value = true
        assertEquals(
            setOf(1L, 2L),
            repo().getTracksByAlbum("HEROES & VILLAINS", "Metro Boomin").first().map { it.id }.toSet(),
        )
    }

    @Test fun `artist and album tabs count by the same rule`() = runTest {
        insert(1L, artist = "Drake", album = "Scorpion", downloaded = true)
        insert(2L, artist = "Drake", album = "Scorpion", downloaded = false)
        insert(3L, artist = "Drake", album = "Views", downloaded = false)

        streamOnly.value = false
        assertEquals(listOf(1), repo().getAllArtists().first().map { it.trackCount })
        assertEquals(listOf("Scorpion" to 1), repo().getAllAlbums().first().map { it.album to it.trackCount })

        streamOnly.value = true
        assertEquals(listOf(3), repo().getAllArtists().first().map { it.trackCount })
        assertEquals(
            setOf("Scorpion" to 2, "Views" to 1),
            repo().getAllAlbums().first().map { it.album to it.trackCount }.toSet(),
        )
    }

    @Test fun `an open artist page follows a flip of the switch`() = runTest {
        insert(1L, artist = "Drake", album = "Scorpion", downloaded = true)
        insert(2L, artist = "Drake", album = "Views", downloaded = false)
        streamOnly.value = false

        val seen = mutableListOf<Set<Long>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo().getTracksByArtist("Drake").collect { tracks -> seen += tracks.map { it.id }.toSet() }
        }
        awaitSize(seen, 1)
        streamOnly.value = true
        awaitSize(seen, 2)
        job.cancel()

        assertEquals(listOf(setOf(1L), setOf(1L, 2L)), seen)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Room emits off the test scheduler; poll briefly for the expected emission. */
    private suspend fun awaitSize(seen: List<*>, size: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (seen.size < size && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertEquals(size, seen.size)
    }

    private suspend fun insert(
        id: Long,
        artist: String,
        album: String,
        downloaded: Boolean,
        albumArtist: String = "",
    ) {
        db.trackDao().insert(
            TrackEntity(
                id = id,
                title = "Title $id",
                artist = artist,
                albumArtist = albumArtist,
                album = album,
                canonicalTitle = "title $id",
                canonicalArtist = artist.lowercase(),
                isDownloaded = downloaded,
                filePath = if (downloaded) "/music/$id.flac" else null,
                dateAdded = java.time.Instant.ofEpochMilli(id),
            ),
        )
    }

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
        streamingPreference = mockk<StreamingPreference> { every { enabled } returns streamOnly },
        localFileOps = mockk(relaxed = true),
        syncPreferencesManager = mockk(relaxed = true),
        singleTrackDownloadEnqueuer = mockk(relaxed = true),
        lastFmRecommendationSource = mockk(relaxed = true),
        sharedMixDao = mockk(relaxed = true),
        downloadJobs = com.stash.core.data.sync.DownloadJobRegistry(),
    )
}
