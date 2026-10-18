package com.stash.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [VersionWords], the one list of words that name another performance of a song. The library's
 * same-song rule and the lossless matcher both read it, so what it names (and what it never names)
 * is pinned here.
 */
class VersionWordsTest {

    private fun words(text: String) = VersionWords.of(text)

    @Test fun `the performance words the owner listed each name a version`() {
        val named = mapOf(
            "(Live)" to "live",
            "(Remix)" to "mix",
            "(Acoustic)" to "acoustic",
            "(Instrumental)" to "instrumental",
            "(Demo)" to "demo",
            "(Karaoke Version)" to "karaoke",
            "(Cover)" to "cover",
            "(MTV Unplugged)" to "unplugged",
            "[BBC Session]" to "session",
            "(Sped Up)" to "sped",
            "(Slowed)" to "slowed",
            "(Nightcore)" to "nightcore",
            "(Reverb)" to "reverb",
            "(Re-Recorded)" to "re-recorded",
            "(Taylor's Version)" to "taylor's version",
        )
        for ((text, word) in named) assertEquals(text, setOf(word), words(text))
    }

    @Test fun `spellings of one word fold together`() {
        assertEquals(setOf("mix"), words("(Remix) - Club Mix"))
        assertEquals(words("(Sped Up)"), words("(Speed Up)"))
        assertEquals(words("(Lo-Fi)"), words("(lofi)"))
        assertEquals(words("(Live)"), words("(En Vivo)"))
        assertEquals(words("(Live)"), words("(Ao Vivo)"))
        assertEquals(words("(Re-Recorded)"), words("(Rerecorded 2015)"))
        assertEquals(words("(Re-Recorded)"), words("(Re-record)"))
        assertEquals(words("(Taylor's Version)"), words("(Taylor’s Version)"))
        assertEquals(words("(Session)"), words("(Spotify Sessions)"))
        assertEquals(setOf("a cappella"), words("(A Cappella)"))
        assertEquals(words("(A Cappella)"), words("(Acapella)"))
        assertEquals(words("(Instrumental)"), words("(Off Vocal)"))
        assertEquals(setOf("mashup"), words("(Mash-Up)"))
    }

    @Test fun `remix on both sides names the same word`() {
        assertEquals(words("(Fcukers Remix - Official Audio)"), words(" - Fcukers Remix"))
    }

    @Test fun `several words in one text are all named`() {
        assertEquals(setOf("slowed", "reverb"), words("(Slowed + Reverb)"))
        assertEquals(setOf("live", "acoustic", "session"), words("(Live Acoustic Session)"))
    }

    @Test fun `the same performance names no word`() {
        val same = listOf(
            "(Remastered)", "(Remastered 2011)", " - Remastered 2011", "[2011 Remaster]", "(2011)",
            "(Deluxe Edition)", "(Mono)", "(Stereo)", "(Single Version)", "(Album Version)",
            "(Radio Version)", "(Radio Edit)", "(Explicit)", "(Clean)", "(Official Video HD)",
            "[Official Music Video]", "(Audio)", "(Lyrics)", "(Lyric Video)", "(HD)", "(Visualizer)",
            "(Bonus Track)", "",
        )
        for (text in same) assertEquals(text, emptySet<String>(), words(text))
    }

    @Test fun `a reissue mix is the same recording, a club mix is not`() {
        for (text in listOf("(2009 Mix)", "(Stereo Mix)", "(Mono Mix)", "(Single Mix)", "(Radio Mix)", "(Original Mix)", "(Remastered Mix)")) {
            assertEquals(text, emptySet<String>(), words(text))
        }
        assertEquals(setOf("mix"), words("(Club Mix)"))
        assertEquals(setOf("mix"), words("(2019 Remix)"))
    }

    @Test fun `whole words only`() {
        assertEquals(emptySet<String>(), words("Alive (Stay Alive) - Olive Remixed"))
        assertEquals(emptySet<String>(), words("(Livestream Recording)"))
        assertEquals(emptySet<String>(), words("(Mixed by X)"))
        assertEquals(emptySet<String>(), words("(Discovery)"))
    }

    @Test fun `the dash suffix keeps hyphenated names whole`() {
        assertEquals(" - Live", VersionWords.DASH_SUFFIX.find("Song - Live")?.value)
        assertEquals(" – Remastered 2011", VersionWords.DASH_SUFFIX.find("Song – Remastered 2011")?.value)
        assertEquals(null, VersionWords.DASH_SUFFIX.find("Jay-Z Re-Recorded"))
    }

    @Test fun `the matcher reads more takes as versions than the library rule does`() {
        // The library's same-song rule ([of]) is unchanged by these words...
        for (text in listOf("(2011 Re-recording)", "(Outtake)", "(Performance From \"Control\")", "(In Concert)", "- A COLORS SHOW")) {
            assertEquals(text, emptySet<String>(), words(text))
        }
        // ...while the lossless matcher reads them as another take.
        assertEquals(setOf("re-recorded"), VersionWords.forCatalogMatch("(2011 Re-recording)"))
        assertEquals(setOf("outtake"), VersionWords.forCatalogMatch("(Studio Outtakes)"))
        assertEquals(setOf("live"), VersionWords.forCatalogMatch("(Performance From \"Control\")"))
        assertEquals(setOf("live"), VersionWords.forCatalogMatch("(Live in Concert)"))
        assertEquals(setOf("live"), VersionWords.forCatalogMatch("Until Morning - A COLORS SHOW"))
        assertEquals(setOf("mix", "live"), VersionWords.forCatalogMatch("(Live Remix)"))
    }

    @Test fun `the matcher reads a version the catalog names in its own words`() {
        assertEquals(setOf("australian version"), VersionWords.forCatalogMatch("(Australian Version)"))
        assertEquals(setOf("1991 version"), VersionWords.forCatalogMatch("[1991 Version]"))
        assertEquals(setOf("japanese version"), VersionWords.forCatalogMatch("Song - Japanese Version"))
        assertEquals(setOf("early version"), VersionWords.forCatalogMatch("(Remastered) Early Version"))
        assertEquals(setOf("rework"), VersionWords.forCatalogMatch("(FISHER Rework)"))
        // A release label, a reissue year or a version word already listed names no new version.
        for (text in listOf("(Album Version)", "(LP Version)", "(Mono Version)", "(2009 Remastered Version)", "(Single Version)", "(Version 2)")) {
            assertEquals(text, emptySet<String>(), VersionWords.forCatalogMatch(text))
        }
        assertEquals(setOf("live"), VersionWords.forCatalogMatch("(Live Version)"))
        assertEquals(setOf("taylor's version"), VersionWords.forCatalogMatch("(Taylor's Version)"))
        // The library's same-song rule does not move with the matcher.
        assertEquals(emptySet<String>(), words("(Australian Version)"))
        assertEquals(emptySet<String>(), words("(FISHER Rework)"))
    }
}
