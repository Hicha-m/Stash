package com.stash.feature.library

import com.stash.core.common.matchesArtistCredits
import com.stash.core.data.db.dao.ArtistSummary
import com.stash.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildArtistCards]: an Artists-tab card counts exactly the songs its artist page lists, in
 * both states of the Download switch, and its delete names exactly what the delete removes.
 *
 * [LibraryRows] stands in for the tracks table: [LibraryRows.pairs] is what
 * `TrackDao.getAllArtists` returns for a mode, [LibraryRows.page] what the artist page lists
 * (`MusicRepository.getTracksByArtist`: the mode, then [matchesArtistCredits]).
 */
class ArtistCardsTest {

    /** A library mixing own songs, features, an album-artist credit and a case variant. */
    private val library = LibraryRows(
        row(1L, "Drake", downloaded = true),
        row(2L, "Drake", downloaded = false),
        row(3L, "Drake, 21 Savage", downloaded = true),
        row(4L, "Future, Drake", downloaded = false),
        row(5L, "Future, Drake", downloaded = true),
        row(6L, "Someone Else", albumArtist = "Drake", downloaded = true),
        row(7L, "Future", downloaded = true),
        row(8L, "Future", downloaded = false),
        row(9L, "Aarne, Toxi$", downloaded = false),
        row(10L, "Toxi$", downloaded = true),
        row(11L, "drake", downloaded = false),
    )

    @Test
    fun `every card counts what its page lists, in Download and in Stream only`() {
        for (streamOnly in listOf(false, true)) {
            val cards = buildArtistCards(library.pairs(streamOnly))
            assertTrue("no cards with streamOnly=$streamOnly", cards.isNotEmpty())
            for (card in cards) {
                val page = library.page(card.name, streamOnly)
                assertEquals("${card.name} count, streamOnly=$streamOnly", page.size, card.trackCount)
                assertEquals(
                    "${card.name} duration, streamOnly=$streamOnly",
                    page.sumOf { it.durationMs },
                    card.totalDurationMs,
                )
            }
        }
    }

    @Test
    fun `an artist with downloaded and not-downloaded songs counts them all only in Stream only`() {
        val download = buildArtistCards(library.pairs(streamOnly = false)).single { it.name == "Drake" }
        val streamOnly = buildArtistCards(library.pairs(streamOnly = true)).single { it.name == "Drake" }

        // Download: own (1), collab (3), feature (5), album-artist credit (6).
        assertEquals(4, download.trackCount)
        // Stream only adds the songs that aren't downloaded: 2, 4, and the "drake" spelling (11).
        assertEquals(7, streamOnly.trackCount)
    }

    @Test
    fun `cards are one per primary act of a stored credit`() {
        assertEquals(
            setOf("Drake", "Future", "Someone Else", "Toxi$"),
            buildArtistCards(library.pairs(streamOnly = false)).map { it.name }.toSet(),
        )
        assertEquals(
            setOf("Drake", "Future", "Someone Else", "Toxi$", "Aarne", "drake"),
            buildArtistCards(library.pairs(streamOnly = true)).map { it.name }.toSet(),
        )
    }

    @Test
    fun `delete count is the downloaded songs the card's delete removes, never the page`() {
        for (streamOnly in listOf(false, true)) {
            for (card in buildArtistCards(library.pairs(streamOnly))) {
                val deleted = artistCardTracks(library.page(card.name, streamOnly), card.name)
                assertEquals("${card.name}, streamOnly=$streamOnly", deleted.size, card.downloadedCount)
            }
        }
        // Drake's delete takes his own downloaded songs (1, 3): not Future's feature (5), not the
        // compilation (6), not a song that isn't downloaded.
        val drake = buildArtistCards(library.pairs(streamOnly = true)).single { it.name == "Drake" }
        assertEquals(2, drake.downloadedCount)
        val aarne = buildArtistCards(library.pairs(streamOnly = true)).single { it.name == "Aarne" }
        assertEquals(0, aarne.downloadedCount)
    }

    @Test
    fun `a card whose page would list nothing is left out`() {
        val rows = LibraryRows(
            row(1L, "", downloaded = true),
            // primaryArtist gives "Earth", which the credit split ("Earth, Wind & Fire", "Chicago")
            // doesn't name: the "Earth" page is empty.
            row(2L, "Earth, Wind & Fire, Chicago", downloaded = true),
            row(3L, "Drake", downloaded = true),
        )

        assertEquals(listOf("Drake"), buildArtistCards(rows.pairs(streamOnly = false)).map { it.name })
    }

    @Test
    fun `art prefers the act's own credit, then any credit it leads`() {
        val pairs = listOf(
            ArtistSummary("Aarne, SLAANG", trackCount = 5, totalDurationMs = 0, artUrl = "https://art/slaang"),
            ArtistSummary("Aarne", trackCount = 1, totalDurationMs = 0, artUrl = "https://art/aarne"),
            ArtistSummary("Future, Drake", trackCount = 2, totalDurationMs = 0, artUrl = null),
            ArtistSummary("Future, Metro", trackCount = 1, totalDurationMs = 0, artUrl = "https://art/metro"),
        )

        val cards = buildArtistCards(pairs).associateBy { it.name }

        assertEquals("https://art/aarne", cards.getValue("Aarne").artUrl)
        assertEquals("https://art/metro", cards.getValue("Future").artUrl)
        assertNull(buildArtistCards(listOf(ArtistSummary("X", 1, 0, null))).single().artUrl)
    }

    @Test
    fun `foldCase keys agree with equals ignoring case`() {
        val words = listOf("Drake", "DRAKE", "drake", "Ångström", "ÅNGSTRÖM", "İstanbul", "istanbul", "ISTANBUL", "Straße", "STRASSE")
        for (a in words) for (b in words) {
            assertEquals("$a vs $b", a.equals(b, ignoreCase = true), foldCase(a) == foldCase(b))
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun row(id: Long, artist: String, albumArtist: String = "", downloaded: Boolean) =
        Track(
            id = id,
            title = "Song $id",
            artist = artist,
            albumArtist = albumArtist,
            durationMs = id * 1_000,
            isDownloaded = downloaded,
            filePath = if (downloaded) "/music/$id.flac" else null,
        )

    private class LibraryRows(vararg val rows: Track) {
        /** `TrackDao.getAllArtists(includeNotDownloaded = streamOnly)`. */
        fun pairs(streamOnly: Boolean): List<ArtistSummary> =
            rows.filter { streamOnly || it.isDownloaded }
                .groupBy { it.artist to it.albumArtist }
                .map { (key, group) ->
                    ArtistSummary(
                        artist = key.first,
                        trackCount = group.size,
                        totalDurationMs = group.sumOf { it.durationMs },
                        artUrl = null,
                        albumArtist = key.second,
                        downloadedCount = group.count { it.isDownloaded },
                    )
                }

        /** `MusicRepository.getTracksByArtist(name)` in that mode. */
        fun page(name: String, streamOnly: Boolean): List<Track> =
            rows.filter { (streamOnly || it.isDownloaded) && matchesArtistCredits(it.artist, it.albumArtist, name) }
    }
}
