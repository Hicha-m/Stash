package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 8 — verifies the `includeStreamable: Boolean` parameter added to
 * every library-content DAO query. Each query must:
 *  - Return only `is_downloaded = 1` rows when `includeStreamable = false`.
 *  - Return downloaded + streamable rows when `includeStreamable = true`.
 *  - NEVER return rows that are checked-but-unavailable (`is_downloaded = 0
 *    AND is_streamable = 0 AND is_streamable_checked_at IS NOT NULL`).
 *  - NEVER return un-checked rows (`is_streamable_checked_at IS NULL`).
 *
 * The predicate `(is_downloaded = 1 OR (:includeStreamable AND is_streamable = 1))`
 * handles all four states correctly.
 *
 * EXCEPT the Library Artists and Albums queries (getAllArtists, getAllAlbums,
 * getByArtist, getByAlbum): they take `includeNotDownloaded`, the Download
 * switch's Library rule — `is_downloaded = 1 OR :includeNotDownloaded`, so
 * Download lists downloaded rows and Stream only lists EVERY row. Nothing in
 * the app sets `is_streamable`, so their old `is_streamable = 1` arm admitted
 * nothing and a Stream only artist card counted 3 songs while its page listed 40.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoStreamableTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: TrackDao
    private lateinit var playlistDao: PlaylistDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.trackDao()
        playlistDao = db.playlistDao()
    }

    @After fun tearDown() { db.close() }

    // ── getAllAlbums ────────────────────────────────────────────────────

    @Test fun `getAllAlbums in Download lists and counts downloaded songs only`() = runTest {
        insertDownloaded(id = 1L, album = "Album A", artist = "Drake")
        insertStreamableOnly(id = 2L, album = "Album A", artist = "Drake")
        insertUnchecked(id = 3L, album = "Album B", artist = "Drake")

        val albums = dao.getAllAlbums(includeNotDownloaded = false, libraryOnly = false).first()

        assertEquals(listOf("Album A" to 1), albums.map { it.album to it.trackCount })
    }

    @Test fun `getAllAlbums in Stream only lists and counts every song`() = runTest {
        insertDownloaded(id = 1L, album = "Album A", artist = "Drake")
        insertStreamableOnly(id = 2L, album = "Album A", artist = "Drake")
        // Never-probed and probed-unavailable rows are songs too: nothing sets is_streamable.
        insertUnchecked(id = 3L, album = "Album A", artist = "Drake")
        insertUnavailable(id = 4L, album = "Album C", artist = "Drake")

        val albums = dao.getAllAlbums(includeNotDownloaded = true, libraryOnly = false).first()

        assertEquals(
            setOf("Album A" to 3, "Album C" to 1),
            albums.map { it.album to it.trackCount }.toSet(),
        )
    }

    @Test fun `getAllAlbums names an untagged album after the act its counted songs credit`() = runTest {
        // Two downloaded songs by Drake, three not-downloaded by Future, no album artist tag.
        insertDownloaded(id = 1L, album = "Shared", artist = "Drake")
        insertDownloaded(id = 2L, album = "Shared", artist = "Drake")
        (3L..5L).forEach { insertUnchecked(id = it, album = "Shared", artist = "Future") }

        // Download counts Drake's two, so the card is Drake's and his page lists them. Naming it
        // after Future (most songs overall) opened a page that listed nothing.
        val download = dao.getAllAlbums(includeNotDownloaded = false, libraryOnly = false).first().single()
        assertEquals("Drake" to 2, download.artist to download.trackCount)

        val streamOnly = dao.getAllAlbums(includeNotDownloaded = true, libraryOnly = false).first().single()
        assertEquals("Future" to 5, streamOnly.artist to streamOnly.trackCount)
    }

    @Test fun `getAllAlbums in your library names an untagged album after the act its library songs credit`() = runTest {
        // Drake: two songs, one downloaded and one in a synced playlist. Future: three songs in
        // the app but not in the library. The display-artist pick reads the narrowed rows too.
        insertDownloaded(id = 1L, album = "Shared", artist = "Drake")
        val synced = insertUnchecked(id = 2L, album = "Shared", artist = "Drake")
        (3L..5L).forEach { insertUnchecked(id = it, album = "Shared", artist = "Future") }
        playlistDao.insertCrossRef(crossRef(playlistDao.insert(customPlaylist()), synced, position = 0))

        val everything = dao.getAllAlbums(includeNotDownloaded = true, libraryOnly = false).first().single()
        assertEquals("Future" to 5, everything.artist to everything.trackCount)

        val inLibrary = dao.getAllAlbums(includeNotDownloaded = true, libraryOnly = true).first().single()
        assertEquals("Drake" to 2, inLibrary.artist to inLibrary.trackCount)
    }

    // ── getAllArtists ───────────────────────────────────────────────────

    @Test fun `getAllArtists in Download counts downloaded songs only`() = runTest {
        insertDownloaded(id = 1L, album = "Album A", artist = "Drake")
        insertStreamableOnly(id = 2L, album = "Album B", artist = "Drake")
        insertUnchecked(id = 3L, album = "Album B", artist = "Future")

        val artists = dao.getAllArtists(includeNotDownloaded = false, libraryOnly = false).first()

        assertEquals(
            listOf(Triple("Drake", 1, 1)),
            artists.map { Triple(it.artist, it.trackCount, it.downloadedCount) },
        )
    }

    @Test fun `getAllArtists in Stream only counts every song, and how many are downloaded`() = runTest {
        insertDownloaded(id = 1L, album = "Album A", artist = "Drake")
        insertStreamableOnly(id = 2L, album = "Album B", artist = "Drake")
        insertUnchecked(id = 3L, album = "Album B", artist = "Drake")
        insertUnavailable(id = 4L, album = "Album C", artist = "Travis")

        val artists = dao.getAllArtists(includeNotDownloaded = true, libraryOnly = false).first()

        assertEquals(
            setOf(Triple("Drake", 3, 1), Triple("Travis", 1, 0)),
            artists.map { Triple(it.artist, it.trackCount, it.downloadedCount) }.toSet(),
        )
    }

    @Test fun `getAllArtists keeps each album-artist credit apart`() = runTest {
        // The artist page also takes songs whose ALBUM artist credits the act, so the card
        // needs the pair, not the artist column alone.
        insertTrack(id = 1L, artist = "Drake", albumArtist = "", downloaded = true, durationMs = 100)
        insertTrack(id = 2L, artist = "Someone Else", albumArtist = "Drake", downloaded = false, durationMs = 200)
        insertTrack(id = 3L, artist = "Someone Else", albumArtist = "", downloaded = true, durationMs = 300)

        val pairs = dao.getAllArtists(includeNotDownloaded = true, libraryOnly = false).first()
            .map { listOf(it.artist, it.albumArtist, it.trackCount, it.downloadedCount, it.totalDurationMs) }
            .toSet()

        assertEquals(
            setOf(
                listOf("Drake", "", 1, 1, 100L),
                listOf("Someone Else", "Drake", 1, 0, 200L),
                listOf("Someone Else", "", 1, 1, 300L),
            ),
            pairs,
        )
    }

    @Test fun `getAllArtists art is a stable non-null pick, not whichever row came last`() = runTest {
        dao.insert(
            TrackEntity(
                id = 1L, title = "With Art", artist = "Drake", album = "A",
                canonicalTitle = "with art", canonicalArtist = "drake",
                isDownloaded = true, albumArtUrl = "https://art/1.jpg",
            ),
        )
        insertDownloaded(id = 2L, album = "B", artist = "Drake") // no art, visited last

        val drake = dao.getAllArtists(includeNotDownloaded = false, libraryOnly = false).first().single()

        assertEquals("https://art/1.jpg", drake.artUrl)
    }

    // ── getByArtist / getByAlbum (the artist and album pages) ──────────

    @Test fun `getByArtist follows the Download switch`() = runTest {
        insertDownloaded(id = 1L, album = "A", artist = "Drake")
        insertUnchecked(id = 2L, album = "B", artist = "Drake, Future")
        insertTrack(id = 3L, artist = "Someone Else", albumArtist = "Drake", downloaded = false)
        insertDownloaded(id = 4L, album = "C", artist = "Travis")

        assertEquals(listOf(1L), dao.getByArtist("Drake", includeNotDownloaded = false, libraryOnly = false).first().map { it.id })
        assertEquals(
            setOf(1L, 2L, 3L),
            dao.getByArtist("Drake", includeNotDownloaded = true, libraryOnly = false).first().map { it.id }.toSet(),
        )
    }

    @Test fun `getByAlbum follows the Download switch and ignores case`() = runTest {
        insertDownloaded(id = 1L, album = "Her Loss", artist = "Drake")
        insertUnchecked(id = 2L, album = "HER LOSS", artist = "Drake, 21 Savage")
        insertStreamableOnly(id = 3L, album = "Her Loss", artist = "21 Savage")
        insertDownloaded(id = 4L, album = "Scorpion", artist = "Drake")

        assertEquals(listOf(1L), dao.getByAlbum("her loss", includeNotDownloaded = false, libraryOnly = false).first().map { it.id })
        assertEquals(
            setOf(1L, 2L, 3L),
            dao.getByAlbum("her loss", includeNotDownloaded = true, libraryOnly = false).first().map { it.id }.toSet(),
        )
    }

    @Test fun `getByAlbum never matches untagged songs`() = runTest {
        insertDownloaded(id = 1L, album = "", artist = "Drake")

        assertTrue(dao.getByAlbum("", includeNotDownloaded = true, libraryOnly = false).first().isEmpty())
    }

    // ── getByPlaylist ───────────────────────────────────────────────────

    @Test fun `getByPlaylist breaks position ties deterministically`() = runTest {
        val later = insertDownloaded(id = 5L, album = "A", artist = "Drake")
        val earlier = insertDownloaded(id = 3L, album = "A", artist = "Drake")
        val playlistId = playlistDao.insert(customPlaylist())
        // REFRESH keeps a user-added row at its old position while the sync
        // renumbers from 0, so two members can share a position.
        playlistDao.insertCrossRef(crossRef(playlistId, later, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistId, earlier, position = 0))

        val tracks = dao.getByPlaylist(playlistId, includeStreamable = false).first()

        assertEquals(listOf(3L, 5L), tracks.map { it.id })
    }

    @Test fun `countByPlaylist matches getByPlaylist in every mode`() = runTest {
        val downloaded = insertDownloaded(id = 1L, album = "A", artist = "Drake")
        val streamable = insertStreamableOnly(id = 2L, album = "B", artist = "Drake")
        val unchecked = insertUnchecked(id = 3L, album = "D", artist = "Drake")
        for (playlist in listOf(customPlaylist(), stashMixPlaylist())) {
            val playlistId = playlistDao.insert(playlist)
            listOf(downloaded, streamable, unchecked).forEachIndexed { i, id ->
                playlistDao.insertCrossRef(crossRef(playlistId, id, position = i))
            }
            for (includeStreamable in listOf(false, true)) {
                assertEquals(
                    "${playlist.type} includeStreamable=$includeStreamable",
                    dao.getByPlaylist(playlistId, includeStreamable).first().size,
                    dao.countByPlaylist(playlistId, includeStreamable),
                )
            }
        }
    }

    @Test fun `getByPlaylist excludes streamable by default`() = runTest {
        val downloaded = insertDownloaded(id = 1L, album = "A", artist = "Drake")
        val streamable = insertStreamableOnly(id = 2L, album = "B", artist = "Drake")
        val playlistId = playlistDao.insert(customPlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, downloaded, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistId, streamable, position = 1))

        val tracks = dao.getByPlaylist(playlistId, includeStreamable = false).first()

        assertEquals(listOf(1L), tracks.map { it.id })
    }

    @Test fun `getByPlaylist includes streamable when flagged`() = runTest {
        val downloaded = insertDownloaded(id = 1L, album = "A", artist = "Drake")
        val streamable = insertStreamableOnly(id = 2L, album = "B", artist = "Drake")
        val playlistId = playlistDao.insert(customPlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, downloaded, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistId, streamable, position = 1))

        val tracks = dao.getByPlaylist(playlistId, includeStreamable = true).first()

        assertEquals(setOf(1L, 2L), tracks.map { it.id }.toSet())
    }

    /**
     * getByPlaylist deliberately does NOT filter on `is_streamable` the way
     * search/getTotalCount do. Those are library-wide
     * aggregates, where an unplayable row is noise. A playlist is different:
     * **membership is the source of truth**, and availability is decided at play
     * time.
     *
     * This is load-bearing. Sync inserts every new track with `is_streamable = 0`
     * and `is_streamable_checked_at = null` (see [TrackEntity.isStreamable] —
     * "not yet known streamable until the worker drains them"). If this query
     * filtered to `is_streamable = 1`, every freshly synced playlist would render
     * EMPTY in Online mode until the streamability worker caught up — i.e. the
     * "playlists sync as empty shells with cover art and 0 songs" symptom that
     * v0.9.82 shipped a hotfix for.
     *
     * This test previously asserted the opposite (`excludes unavailable even when
     * streamable true`) and had been failing on master for weeks. Making the query
     * satisfy it would have reintroduced the empty-playlist bug, so the assertion
     * was inverted to pin the behaviour we actually want.
     *
     * Hiding only PROBED-unavailable rows (`is_streamable = 0 AND checked_at IS
     * NOT NULL`) while keeping unprobed ones is defensible, but deliberately not
     * done: a probe can fail transiently (host down, region-locked) and silently
     * dropping a synced track from its playlist is worse than a track that fails
     * on tap.
     */
    @Test fun `getByPlaylist keeps unprobed and unavailable rows when streamable true`() = runTest {
        val downloaded = insertDownloaded(id = 1L, album = "A", artist = "Drake")
        val unavailable = insertUnavailable(id = 2L, album = "C", artist = "Drake")
        val unchecked = insertUnchecked(id = 3L, album = "D", artist = "Drake")
        val playlistId = playlistDao.insert(customPlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, downloaded, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistId, unavailable, position = 1))
        playlistDao.insertCrossRef(crossRef(playlistId, unchecked, position = 2))

        val tracks = dao.getByPlaylist(playlistId, includeStreamable = true).first()

        // All three: a synced playlist shows its whole tracklist in Online mode.
        assertEquals(listOf(1L, 2L, 3L), tracks.map { it.id })
    }

    // ── getByPlaylist: STASH_MIX offline exemption ──────────────────────
    // Stash Mixes are an inherently online discovery surface. Their
    // stream-only tracks must stay visible even when the Online/Offline
    // preference is OFF (includeStreamable = false), so the full mix shows
    // in Offline mode instead of collapsing to the few downloaded tracks.

    @Test fun `getByPlaylist STASH_MIX includes streamable even when flag is off`() = runTest {
        val downloaded = insertDownloaded(id = 1L, album = "A", artist = "Drake")
        val streamable = insertStreamableOnly(id = 2L, album = "B", artist = "Drake")
        val playlistId = playlistDao.insert(stashMixPlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, downloaded, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistId, streamable, position = 1))

        val tracks = dao.getByPlaylist(playlistId, includeStreamable = false).first()

        assertEquals(setOf(1L, 2L), tracks.map { it.id }.toSet())
    }

    @Test fun `getByPlaylist STASH_MIX still excludes unavailable and unchecked when flag is off`() = runTest {
        val downloaded = insertDownloaded(id = 1L, album = "A", artist = "Drake")
        val unavailable = insertUnavailable(id = 2L, album = "C", artist = "Drake")
        val unchecked = insertUnchecked(id = 3L, album = "D", artist = "Drake")
        val playlistId = playlistDao.insert(stashMixPlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, downloaded, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistId, unavailable, position = 1))
        playlistDao.insertCrossRef(crossRef(playlistId, unchecked, position = 2))

        val tracks = dao.getByPlaylist(playlistId, includeStreamable = false).first()

        assertEquals(listOf(1L), tracks.map { it.id })
    }

    @Test fun `getByPlaylist non-mix still excludes streamable when flag is off`() = runTest {
        // Control: the exemption is scoped to STASH_MIX. A CUSTOM playlist
        // must still collapse to downloaded-only in Offline mode.
        val downloaded = insertDownloaded(id = 1L, album = "A", artist = "Drake")
        val streamable = insertStreamableOnly(id = 2L, album = "B", artist = "Drake")
        val playlistId = playlistDao.insert(customPlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, downloaded, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistId, streamable, position = 1))

        val tracks = dao.getByPlaylist(playlistId, includeStreamable = false).first()

        assertEquals(listOf(1L), tracks.map { it.id })
    }

    // ── getTotalCount ───────────────────────────────────────────────────

    @Test fun `getTotalCount excludes streamable by default`() = runTest {
        insertDownloaded(id = 1L, album = "A", artist = "Drake")
        insertStreamableOnly(id = 2L, album = "B", artist = "Drake")

        val count = dao.getTotalCount(includeStreamable = false).first()

        assertEquals(1, count)
    }

    @Test fun `getTotalCount includes streamable when flagged`() = runTest {
        insertDownloaded(id = 1L, album = "A", artist = "Drake")
        insertStreamableOnly(id = 2L, album = "B", artist = "Drake")

        val count = dao.getTotalCount(includeStreamable = true).first()

        assertEquals(2, count)
    }

    @Test fun `getTotalCount excludes unavailable even when streamable true`() = runTest {
        insertDownloaded(id = 1L, album = "A", artist = "Drake")
        insertUnavailable(id = 2L, album = "C", artist = "Drake")
        insertUnchecked(id = 3L, album = "D", artist = "Drake")

        val count = dao.getTotalCount(includeStreamable = true).first()

        assertEquals(1, count)
    }

    // ── FTS search ──────────────────────────────────────────────────────

    @Test fun `search excludes streamable by default`() = runTest {
        insertDownloaded(id = 1L, album = "A", artist = "Drake", title = "Hotline Bling")
        insertStreamableOnly(id = 2L, album = "B", artist = "Drake", title = "Hotline Two")

        val tracks = dao.search(query = "Hotline*", includeStreamable = false).first()

        assertEquals(listOf(1L), tracks.map { it.id })
    }

    @Test fun `search includes streamable when flagged`() = runTest {
        insertDownloaded(id = 1L, album = "A", artist = "Drake", title = "Hotline Bling")
        insertStreamableOnly(id = 2L, album = "B", artist = "Drake", title = "Hotline Two")

        val tracks = dao.search(query = "Hotline*", includeStreamable = true).first()

        assertEquals(setOf(1L, 2L), tracks.map { it.id }.toSet())
    }

    @Test fun `search excludes unavailable even when streamable true`() = runTest {
        insertDownloaded(id = 1L, album = "A", artist = "Drake", title = "Hotline Bling")
        insertUnavailable(id = 2L, album = "C", artist = "Drake", title = "Hotline Three")

        val tracks = dao.search(query = "Hotline*", includeStreamable = true).first()

        assertEquals(listOf(1L), tracks.map { it.id })
    }

    // ── PlaylistDao.getAllVisible ───────────────────────────────────────

    @Test fun `getAllVisible escape-hatch excludes streamable by default`() = runTest {
        // Playlist with sync_enabled = 0 — only visible if it has a downloaded
        // track. A streamable-only track must NOT keep it visible when the
        // streaming flag is off.
        val streamableTrack = insertStreamableOnly(id = 1L, album = "A", artist = "Drake")
        val playlistId = playlistDao.insert(syncDisabledPlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, streamableTrack, position = 0))

        val visible = playlistDao.getAllVisible(includeStreamable = false).first()

        assertTrue("sync_enabled=0 with only streamable tracks must be hidden", visible.isEmpty())
    }

    @Test fun `getAllVisible escape-hatch includes streamable when flagged`() = runTest {
        val streamableTrack = insertStreamableOnly(id = 1L, album = "A", artist = "Drake")
        val playlistId = playlistDao.insert(syncDisabledPlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, streamableTrack, position = 0))

        val visible = playlistDao.getAllVisible(includeStreamable = true).first()

        assertEquals(listOf(playlistId), visible.map { it.id })
    }

    @Test fun `getAllVisible always shows sync-enabled playlists`() = runTest {
        // sync_enabled = 1 visibility doesn't depend on tracks — visible
        // regardless of includeStreamable.
        val playlistId = playlistDao.insert(syncEnabledPlaylist())

        val visibleFalse = playlistDao.getAllVisible(includeStreamable = false).first()
        val visibleTrue = playlistDao.getAllVisible(includeStreamable = true).first()

        assertEquals(listOf(playlistId), visibleFalse.map { it.id })
        assertEquals(listOf(playlistId), visibleTrue.map { it.id })
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private suspend fun insertTrack(
        id: Long,
        artist: String,
        albumArtist: String,
        downloaded: Boolean,
        album: String = "Album $id",
        durationMs: Long = 0L,
    ): Long {
        dao.insert(
            TrackEntity(
                id = id,
                title = "Title $id",
                artist = artist,
                albumArtist = albumArtist,
                album = album,
                durationMs = durationMs,
                canonicalTitle = "title $id",
                canonicalArtist = artist.lowercase(),
                isDownloaded = downloaded,
            )
        )
        return id
    }

    private suspend fun insertDownloaded(
        id: Long,
        album: String,
        artist: String,
        title: String = "Title $id",
    ): Long {
        dao.insert(
            TrackEntity(
                id = id,
                title = title,
                artist = artist,
                album = album,
                canonicalTitle = title.lowercase(),
                canonicalArtist = artist.lowercase(),
                isDownloaded = true,
                isStreamable = false,
                isStreamableCheckedAt = null,
            )
        )
        return id
    }

    // ── findStreamableMetadataBackfillCandidates ────────────────────────

    /**
     * Stubs Last.fm can't fill never leave the candidate set. Unordered, the
     * same oldest batch came back every run and a just-created stub could sit
     * behind it forever; newest-first serves the fresh stubs.
     */
    @Test fun `stream-only metadata backfill serves the newest stubs first`() = runTest {
        (1L..5L).forEach { insertStreamableOnly(id = it, album = "Album", artist = "Artist") }

        val batch = dao.findStreamableMetadataBackfillCandidates(limit = 2)

        assertEquals(listOf(5L, 4L), batch.map { it.id })
    }

    private suspend fun insertStreamableOnly(
        id: Long,
        album: String,
        artist: String,
        title: String = "Title $id",
    ): Long {
        dao.insert(
            TrackEntity(
                id = id,
                title = title,
                artist = artist,
                album = album,
                canonicalTitle = title.lowercase(),
                canonicalArtist = artist.lowercase(),
                isDownloaded = false,
                isStreamable = true,
                isStreamableCheckedAt = 1_000_000L,
            )
        )
        return id
    }

    private suspend fun insertUnavailable(
        id: Long,
        album: String,
        artist: String,
        title: String = "Title $id",
    ): Long {
        // is_downloaded = 0, is_streamable = 0, checked_at != null
        dao.insert(
            TrackEntity(
                id = id,
                title = title,
                artist = artist,
                album = album,
                canonicalTitle = title.lowercase(),
                canonicalArtist = artist.lowercase(),
                isDownloaded = false,
                isStreamable = false,
                isStreamableCheckedAt = 1_000_000L,
            )
        )
        return id
    }

    private suspend fun insertUnchecked(
        id: Long,
        album: String,
        artist: String,
        title: String = "Title $id",
    ): Long {
        // is_downloaded = 0, is_streamable = 0, checked_at = null
        dao.insert(
            TrackEntity(
                id = id,
                title = title,
                artist = artist,
                album = album,
                canonicalTitle = title.lowercase(),
                canonicalArtist = artist.lowercase(),
                isDownloaded = false,
                isStreamable = false,
                isStreamableCheckedAt = null,
            )
        )
        return id
    }

    private fun customPlaylist() = PlaylistEntity(
        name = "Test Playlist",
        source = MusicSource.BOTH,
        sourceId = "test_playlist_${System.nanoTime()}",
        type = PlaylistType.CUSTOM,
        trackCount = 0,
        syncEnabled = true,
        isActive = true,
    )

    private fun stashMixPlaylist() = PlaylistEntity(
        name = "Daily Discover",
        source = MusicSource.BOTH,
        sourceId = "stash_mix_${System.nanoTime()}",
        type = PlaylistType.STASH_MIX,
        trackCount = 0,
        syncEnabled = true,
        isActive = true,
    )

    private fun syncDisabledPlaylist() = PlaylistEntity(
        name = "Sync-Disabled",
        source = MusicSource.SPOTIFY,
        sourceId = "sync_disabled_${System.nanoTime()}",
        type = PlaylistType.CUSTOM,
        trackCount = 0,
        syncEnabled = false,
        isActive = true,
    )

    private fun syncEnabledPlaylist() = PlaylistEntity(
        name = "Sync-Enabled",
        source = MusicSource.SPOTIFY,
        sourceId = "sync_enabled_${System.nanoTime()}",
        type = PlaylistType.CUSTOM,
        trackCount = 0,
        syncEnabled = true,
        isActive = true,
    )

    private fun crossRef(playlistId: Long, trackId: Long, position: Int) =
        PlaylistTrackCrossRef(
            playlistId = playlistId,
            trackId = trackId,
            position = position,
            addedAt = java.time.Instant.EPOCH,
        )
}
