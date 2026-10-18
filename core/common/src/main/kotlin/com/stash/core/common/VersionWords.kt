package com.stash.core.common

/**
 * The words that name another performance of a song: "Live", "Remix", "Acoustic", "Sped Up",
 * "Taylor's Version"... One list for the two places that tell versions apart, so they never
 * disagree:
 *  - the library's same-song rule ([TrackCanonical.sameVersion]): two titles that share a canonical
 *    key are two recordings only when one names a word here that the other doesn't;
 *  - the lossless matcher (`QobuzCandidateMatcher` in :data:download): a candidate that names a
 *    version the query doesn't is penalised (#502).
 *
 * Everything else a title carries names the same performance, so no word here covers it: a remaster
 * (with or without a year), Deluxe, Mono / Stereo, a Single / Album / Radio Version or Edit,
 * Explicit / Clean, credits ("feat. X", "with X", "prod. X") and YouTube's presentation tags
 * ("Official Video", "Audio", "Lyrics", "HD"). A year, stereo, mono, single... mix is a reissue mix of
 * the same recording ("2009 Mix", "Stereo Mix"), so it names nothing either.
 *
 * Whole words only, so "Alive" is not "live" and "Remixed" is not "remix". Callers pass the text
 * that may hold a version (bracketed groups, a dash suffix, Qobuz's version field), never a bare
 * title: "Live Forever" and "Remix Culture" are song names, not versions.
 */
object VersionWords {

    /**
     * A dash suffix: " - Live", " – Remastered 2011", " — Fcukers Remix". The space on each side
     * keeps "Jay-Z" and "Re-Recorded" whole.
     */
    val DASH_SUFFIX = Regex("\\s[-\u2013\u2014]\\s.*")

    /** A reissue mix of the same recording, set aside before [WORDS] runs: "2009 Mix", "Mono Mix". */
    private val SAME_SONG_MIX =
        Regex("\\b(\\d{4}|stereo|mono|single|radio|album|original|remaster|remastered)\\s+mix\\b")

    /**
     * Performance words, longest spelling first where one contains another. The first line is the
     * list the lossless matcher started with; the rest extend it for the library rule (and the
     * matcher gains them too: each names a recording the query didn't ask for).
     */
    private val WORDS = Regex(
        "\\b(" +
            "remix|mix|live|cover|karaoke|instrumental|lofi|lo-fi|lo fi|piano|acoustic|" +
            "orchestral|sped up|sped|slowed|nightcore|demo|" +
            "unplugged|sessions|session|reverb|re-?recorded|re-?record|taylor['\u2019]?s version|" +
            "speed up|en vivo|ao vivo|a ?cap+el+a|off vocal|stripped|symphonic|reprise|rehearsal|" +
            "mash[- ]?up|8d" +
            ")\\b",
    )

    /**
     * The performance words [text] names, folded so spellings of one word compare equal: "remix" and
     * "mix", "lo-fi" and "lofi", "sped up" and "speed up", "live" and "en vivo", "re-record" and
     * "re-recorded". A word on both sides of a comparison matches: "(Remix)" and "- Fcukers Remix"
     * both name "mix".
     *
     * @param text the parts of a title that may name a version, in any case.
     * @return the folded words, empty when [text] names the same performance as the plain song.
     */
    fun of(text: String): Set<String> =
        WORDS.findAll(SAME_SONG_MIX.replace(text.lowercase(), " "))
            .map { fold(it.value) }
            .toSet()

    /**
     * Words only the lossless matcher reads as versions, on top of [of]: "Re-recording", "Outtake",
     * "Performance", "Concert", "A COLORS SHOW". They name another take of a song as surely as
     * "Live" does, so a catalog lookup must not trade one for the studio cut (or the reverse). They
     * stay out of [WORDS] because the library's same-song rule ([TrackCanonical]) reads that list,
     * and the library's identity must not move with the matcher.
     */
    private val MATCH_ONLY_WORDS = Regex("\\b(re-?recording|outtakes?|performance|concert|colors show)\\b")

    /**
     * [of] plus [MATCH_ONLY_WORDS], folded the same way: a performance, a concert or a COLORS show
     * is a live take ("live"), a re-recording is "re-recorded". For the lossless matcher only.
     *
     * @param text the parts of a title that may name a version, in any case.
     */
    fun forCatalogMatch(text: String): Set<String> {
        val extra = MATCH_ONLY_WORDS.findAll(text.lowercase()).map {
            when {
                it.value.startsWith("re") -> "re-recorded"
                it.value.startsWith("outtake") -> "outtake"
                else -> "live"
            }
        }
        return of(text) + extra
    }

    /** One spelling per word, so two titles that name it differently still compare equal. */
    private fun fold(word: String): String = when {
        word == "remix" -> "mix"
        word == "lo-fi" || word == "lo fi" -> "lofi"
        word == "sped up" || word == "speed up" -> "sped"
        word == "en vivo" || word == "ao vivo" -> "live"
        word == "sessions" -> "session"
        word == "off vocal" -> "instrumental"
        word.startsWith("re") && word.contains("record") -> "re-recorded"
        word.startsWith("taylor") -> "taylor's version"
        word.startsWith("mash") -> "mashup"
        word.contains("cap") -> "a cappella"
        else -> word
    }
}
