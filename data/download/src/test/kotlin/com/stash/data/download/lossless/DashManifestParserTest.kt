package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class DashManifestParserTest {

    private val mpdUrl = "https://relay.example/dash/td/1.mpd?exp=1&sig=x"
    private val n = "\$Number\$"

    private fun mpd(template: String, timeline: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
          <Period><AdaptationSet mimeType="audio/mp4"><Representation id="FLAC" codecs="flac">
            $template
              <SegmentTimeline>$timeline</SegmentTimeline>
            </SegmentTemplate>
          </Representation></AdaptationSet></Period>
        </MPD>
    """.trimIndent()

    @Test fun `timeline with repeats gives every segment from startNumber`() {
        val plan = DashManifestParser.parse(
            mpd(
                """<SegmentTemplate timescale="44100" initialization="https://cdn/t/0.mp4?a=1&amp;b=2" media="https://cdn/t/$n.mp4?a=1&amp;b=2" startNumber="1">""",
                """<S d="176128" r="51"/><S d="118808"/>""",
            ),
            mpdUrl,
        )
        assertThat(plan.mediaUrls).hasSize(53)
        assertThat(plan.initUrl).isEqualTo("https://cdn/t/0.mp4?a=1&b=2")
        assertThat(plan.mediaUrls.first()).isEqualTo("https://cdn/t/1.mp4?a=1&b=2")
        assertThat(plan.mediaUrls.last()).isEqualTo("https://cdn/t/53.mp4?a=1&b=2")
    }

    @Test fun `multiple S with t attrs and no startNumber default to 1`() {
        val plan = DashManifestParser.parse(
            mpd(
                """<SegmentTemplate initialization="https://cdn/i.mp4" media="https://cdn/$n.mp4">""",
                """<S t="0" d="10"/><S t="10" d="10" r="2"/><S d="5"/>""",
            ),
            mpdUrl,
        )
        assertThat(plan.mediaUrls).containsExactly(
            "https://cdn/1.mp4", "https://cdn/2.mp4", "https://cdn/3.mp4",
            "https://cdn/4.mp4", "https://cdn/5.mp4",
        ).inOrder()
    }

    @Test fun `startNumber offset, padded Number and relative URLs`() {
        val plan = DashManifestParser.parse(
            mpd(
                """<SegmentTemplate initialization="init.mp4" media="seg-${'$'}Number%03d${'$'}.mp4" startNumber="7">""",
                """<S d="1" r="1"/>""",
            ),
            mpdUrl,
        )
        assertThat(plan.initUrl).isEqualTo("https://relay.example/dash/td/init.mp4")
        assertThat(plan.mediaUrls).containsExactly(
            "https://relay.example/dash/td/seg-007.mp4",
            "https://relay.example/dash/td/seg-008.mp4",
        ).inOrder()
    }

    @Test fun `missing initialization is an error`() {
        assertThrows(DashManifestException::class.java) {
            DashManifestParser.parse(
                mpd("""<SegmentTemplate media="https://cdn/$n.mp4">""", """<S d="1"/>"""),
                mpdUrl,
            )
        }
    }

    @Test fun `open-ended repeat and empty timeline are errors`() {
        assertThrows(DashManifestException::class.java) {
            DashManifestParser.parse(
                mpd("""<SegmentTemplate initialization="https://cdn/i" media="https://cdn/$n">""", """<S d="1" r="-1"/>"""),
                mpdUrl,
            )
        }
        assertThrows(DashManifestException::class.java) {
            DashManifestParser.parse(
                mpd("""<SegmentTemplate initialization="https://cdn/i" media="https://cdn/$n">""", ""),
                mpdUrl,
            )
        }
    }

    @Test fun `no SegmentTemplate is an error`() {
        assertThrows(DashManifestException::class.java) {
            DashManifestParser.parse("<MPD/>", mpdUrl)
        }
    }

    @Test fun `entity decoding leaves a literal amp-escape intact`() {
        assertThat(DashManifestParser.decodeEntities("a&amp;b&amp;lt;c")).isEqualTo("a&b&lt;c")
    }
}
