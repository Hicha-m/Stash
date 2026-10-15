package com.stash.data.download.lossless

import java.net.URI

/**
 * The fetch plan for a single-representation DASH manifest: one init segment,
 * then [mediaUrls] in playback order. Concatenating them gives a playable fMP4.
 */
data class DashPlan(
    val initUrl: String,
    val mediaUrls: List<String>,
)

/** A manifest we cannot turn into a [DashPlan]; the download fails rather than guessing. */
class DashManifestException(message: String) : Exception(message)

/**
 * Minimal MPD reader for Tidal's audio manifests (one `SegmentTemplate` with a
 * `SegmentTimeline`, no DRM). Pure JVM — a regex pass, not an XML stack — because
 * the shape is fixed and this keeps it unit-testable without Android.
 *
 * Deliberately narrow: anything it does not understand (`r="-1"`, template
 * tokens other than `$Number$`, no timeline) is an error, so a format change
 * upstream surfaces as a failed download instead of a silently truncated file.
 */
object DashManifestParser {

    private val TEMPLATE = Regex("<(?:\\w+:)?SegmentTemplate\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val TIMELINE = Regex(
        "<(?:\\w+:)?SegmentTimeline\\b[^>]*>(.*?)</(?:\\w+:)?SegmentTimeline>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val S_ELEMENT = Regex("<(?:\\w+:)?S\\b([^>]*?)/?>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([\\w:]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
    private val BASE_URL = Regex("<(?:\\w+:)?BaseURL\\b[^>]*>([^<]*)<", RegexOption.IGNORE_CASE)
    private val NUMBER_TOKEN = Regex("\\\$Number(%0(\\d+)d)?\\$")

    /** Guards against a hostile/garbled manifest asking for millions of fetches. */
    const val MAX_SEGMENTS = 10_000

    /**
     * @param mpd      Manifest XML text.
     * @param mpdUrl   Where it came from; relative URLs resolve against it (and
     *                 any `BaseURL`). Tidal's are absolute, so this rarely matters.
     */
    fun parse(mpd: String, mpdUrl: String): DashPlan {
        val templateAttrs = TEMPLATE.find(mpd)?.groupValues?.get(1)
            ?: throw DashManifestException("no SegmentTemplate")
        val attrs = attributes(templateAttrs)
        val init = attrs["initialization"]?.takeIf { it.isNotBlank() }
            ?: throw DashManifestException("no initialization URL")
        val media = attrs["media"]?.takeIf { it.isNotBlank() }
            ?: throw DashManifestException("no media template")
        if (!NUMBER_TOKEN.containsMatchIn(media)) throw DashManifestException("media template lacks \$Number\$")
        val startNumber = attrs["startNumber"]?.let {
            it.toLongOrNull() ?: throw DashManifestException("bad startNumber '$it'")
        } ?: 1L

        val timeline = TIMELINE.find(mpd)?.groupValues?.get(1)
            ?: throw DashManifestException("no SegmentTimeline")
        var count = 0L
        for (s in S_ELEMENT.findAll(timeline)) {
            val r = attributes(s.groupValues[1])["r"]?.let {
                it.toLongOrNull() ?: throw DashManifestException("bad r '$it'")
            } ?: 0L
            // r=-1 means "repeat until the period ends" — needs durations we don't model.
            if (r < 0) throw DashManifestException("open-ended repeat (r=$r) unsupported")
            count += 1 + r
            if (count > MAX_SEGMENTS) throw DashManifestException("too many segments")
        }
        if (count == 0L) throw DashManifestException("empty SegmentTimeline")

        val base = baseFor(mpd, mpdUrl)
        val mediaUrls = (0 until count).map { i -> resolve(base, expand(media, startNumber + i)) }
        return DashPlan(initUrl = resolve(base, init), mediaUrls = mediaUrls)
    }

    private fun attributes(raw: String): Map<String, String> =
        ATTR.findAll(raw).associate { m ->
            val value = m.groups[3]?.value ?: m.groups[4]?.value ?: ""
            m.groupValues[1].substringAfter(':') to decodeEntities(value)
        }

    private fun expand(template: String, number: Long): String {
        val out = NUMBER_TOKEN.replace(template) { m ->
            val width = m.groups[2]?.value?.toIntOrNull()
            if (width != null) number.toString().padStart(width, '0') else number.toString()
        }
        // Any other identifier ($RepresentationID$, $Time$...) means a manifest shape we don't handle.
        if (Regex("\\$\\w+\\$").containsMatchIn(out)) throw DashManifestException("unsupported template token")
        return out.replace("$$", "$")
    }

    /** Lazy: Tidal's URLs are absolute, so a base that java.net.URI rejects must not fail them. */
    private fun baseFor(mpd: String, mpdUrl: String): Lazy<URI> = lazy {
        try {
            val docUri = URI(mpdUrl)
            val baseText = BASE_URL.find(mpd)?.groupValues?.get(1)?.trim()?.let(::decodeEntities)
            if (baseText.isNullOrEmpty()) docUri else docUri.resolve(baseText)
        } catch (e: IllegalArgumentException) {
            throw DashManifestException("unresolvable base URL")
        } catch (e: java.net.URISyntaxException) {
            throw DashManifestException("unresolvable base URL")
        }
    }

    // Absolute URLs pass through verbatim: CDN signatures can hold characters
    // java.net.URI is strict about, and re-encoding would break the signature.
    private fun resolve(base: Lazy<URI>, ref: String): String {
        val r = ref.trim()
        if (r.startsWith("https://", ignoreCase = true) || r.startsWith("http://", ignoreCase = true)) return r
        return try {
            base.value.resolve(r).toString()
        } catch (e: IllegalArgumentException) {
            throw DashManifestException("unresolvable segment URL")
        }
    }

    /** XML attribute text is entity-encoded: Tidal's signed URLs arrive with `&amp;`. */
    internal fun decodeEntities(s: String): String =
        s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#38;", "&")
            .replace("&amp;", "&") // last, so "&amp;lt;" stays "&lt;"
}
