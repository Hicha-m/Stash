package com.stash.data.download.lossless.qobuz

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.MusicSource
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.forLibrarySource
import org.junit.Test

/**
 * How a YouTube library row's length is trusted ([forLibrarySource]) and how every source picks
 * among scored candidates ([QobuzCandidateMatcher.pickBest]).
 */
class CandidatePickTest {

    private data class Cand(val name: String, val sec: Int)

    private fun pick(query: TrackQuery, vararg scored: Pair<Cand, Float>) =
        QobuzCandidateMatcher.pickBest(query, scored.toList()) { it.sec }?.first?.name

    private val q210 = TrackQuery(artist = "A", title = "Song", durationMs = 210_000)

    @Test fun `the higher score wins whatever the length`() {
        assertThat(pick(q210, Cand("far", 280) to 0.9f, Cand("near", 210) to 0.8f)).isEqualTo("far")
    }

    @Test fun `equal scores go to the closest length, not the first listed`() {
        // The review's case: an unlabelled 4:40 album cut listed before the right 3:30 single.
        assertThat(pick(q210, Cand("album cut", 280) to 1.0f, Cand("single", 210) to 1.0f)).isEqualTo("single")
        assertThat(pick(q210, Cand("single", 210) to 1.0f, Cand("album cut", 280) to 1.0f)).isEqualTo("single")
    }

    @Test fun `equal scores and equal gaps keep the first listed`() {
        assertThat(pick(q210, Cand("first", 200) to 1.0f, Cand("second", 220) to 1.0f)).isEqualTo("first")
    }

    @Test fun `an unknown candidate length loses a tie, and no query length keeps the order`() {
        assertThat(pick(q210, Cand("unknown", 0) to 1.0f, Cand("known", 300) to 1.0f)).isEqualTo("known")
        val noLength = q210.copy(durationMs = null)
        assertThat(pick(noLength, Cand("first", 280) to 1.0f, Cand("second", 210) to 1.0f)).isEqualTo("first")
    }

    @Test fun `nothing under the threshold is picked`() {
        assertThat(pick(q210, Cand("weak", 210) to QobuzCandidateMatcher.MIN_CONFIDENCE - 0.01f)).isNull()
        assertThat(pick(q210)).isNull()
    }

    private fun yt(type: String? = null, album: String? = null, artist: String = "Some Channel", title: String = "Artist - Song (Official Video)") =
        TrackQuery(artist = artist, title = title, album = album, durationMs = 210_000).forLibrarySource(MusicSource.YOUTUBE, type)

    @Test fun `an official audio upload keeps its length, a video does not`() {
        assertThat(yt(type = "ATV").durationReliable).isTrue()
        assertThat(yt(type = "OMV", album = "Album").durationReliable).isFalse() // the stored class wins over the album
        assertThat(yt(type = "UGC").durationReliable).isFalse()
        // Never classified: YouTube Music gives songs an album and videos none.
        assertThat(yt(album = "Album").durationReliable).isTrue()
        assertThat(yt().durationReliable).isFalse()
        assertThat(yt(artist = "Artist - Topic", title = "Song").durationReliable).isTrue()
        // A clean title alone is not enough: the artist's own music video often reads "Song" by "Artist".
        assertThat(yt(artist = "Artist", title = "Song").durationReliable).isFalse()
    }

    @Test fun `other library rows are left as they are`() {
        val spotify = TrackQuery(artist = "Artist", title = "Song (Official Video)", durationMs = 210_000)
        assertThat(spotify.forLibrarySource(MusicSource.SPOTIFY, "OMV")).isEqualTo(spotify)
    }

    @Test fun `an official audio row keeps the strict length rule against a longer album cut`() {
        // A 3:30 ATV single: the 4:40 album cut is listed first and names no version.
        fun score(q: TrackQuery, sec: Int) = QobuzCandidateMatcher.confidence(q, "Song", "Artist", null, sec, true)
        val atv = TrackQuery(artist = "Artist", title = "Song", album = "Song - Single", durationMs = 210_000)
            .forLibrarySource(MusicSource.YOUTUBE, "ATV")
        val albumCut = Cand("album cut", 280)
        val single = Cand("single", 210)
        assertThat(score(atv, albumCut.sec)).isLessThan(score(atv, single.sec))
        assertThat(QobuzCandidateMatcher.pickBest(atv, listOf(albumCut to score(atv, albumCut.sec), single to score(atv, single.sec))) { it.sec }?.first)
            .isEqualTo(single)
        // A music video of the same song scores both alike, and its length (the video's) breaks no
        // tie: the catalog's order stands, as it does with no length at all.
        val video = TrackQuery(artist = "Artist", title = "Song", durationMs = 210_000).forLibrarySource(MusicSource.YOUTUBE, "OMV")
        assertThat(score(video, albumCut.sec)).isEqualTo(score(video, single.sec))
        assertThat(QobuzCandidateMatcher.pickBest(video, listOf(albumCut to score(video, albumCut.sec), single to score(video, single.sec))) { it.sec }?.first)
            .isEqualTo(albumCut)
    }
}
