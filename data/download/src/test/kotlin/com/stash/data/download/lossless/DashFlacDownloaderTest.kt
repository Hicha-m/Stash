package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.audio.FFmpegBridge
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DashFlacDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    /** Fake ffmpeg: "remuxes" by copying input to output, recording what it was fed. */
    private var remuxInput: String? = null
    private var remuxFails = false
    private val ffmpeg = object : FFmpegBridge {
        override suspend fun runWithStderrCapture(args: List<String>): String {
            if (remuxFails) throw IOException("ffmpeg exited with code 1")
            val input = File(args[args.indexOf("-i") + 1])
            remuxInput = input.readText()
            input.copyTo(File(args.last()), overwrite = true)
            return ""
        }
    }

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun mpd(segments: Int): String {
        val init = server.url("/init.mp4")
        val media = server.url("/seg/").toString() + "\$Number\$.mp4"
        return """<MPD><SegmentTemplate initialization="$init" media="$media" startNumber="1">
            <SegmentTimeline><S d="1" r="${segments - 1}"/></SegmentTimeline></SegmentTemplate></MPD>"""
    }

    private fun dest() = File(tmp.newFolder(), "t.flac")

    @Test fun `concatenates init and segments in order then remuxes`() = runTest {
        server.enqueue(MockResponse().setBody(mpd(3)))
        server.enqueue(MockResponse().setBody("I"))
        server.enqueue(MockResponse().setBody("1"))
        server.enqueue(MockResponse().setResponseCode(503)) // retried
        server.enqueue(MockResponse().setBody("2"))
        server.enqueue(MockResponse().setBody("3"))
        val dest = dest()
        val progress = mutableListOf<Pair<Long, Long>>()

        val r = DashFlacDownloader(ffmpeg).download(
            OkHttpClient(), server.url("/m.mpd").toString(), emptyMap(), dest,
        ) { d, t -> progress += d to t }

        assertThat(r.isSuccess).isTrue()
        assertThat(remuxInput).isEqualTo("I123")
        assertThat(dest.readText()).isEqualTo("I123")
        assertThat(progress.last()).isEqualTo(4L to 4L)
        assertThat(dest.parentFile!!.listFiles()!!.map { it.name }).containsExactly("t.flac")
    }

    @Test fun `a segment 404 fails the download and leaves nothing behind`() = runTest {
        server.enqueue(MockResponse().setBody(mpd(2)))
        server.enqueue(MockResponse().setBody("I"))
        server.enqueue(MockResponse().setResponseCode(404))
        val dest = dest()

        val r = DashFlacDownloader(ffmpeg).download(OkHttpClient(), server.url("/m.mpd").toString(), emptyMap(), dest)

        assertThat(r.isFailure).isTrue()
        assertThat(dest.parentFile!!.listFiles()!!.toList()).isEmpty()
    }

    @Test fun `remux failure is a failure, not an mp4 named flac`() = runTest {
        remuxFails = true
        server.enqueue(MockResponse().setBody(mpd(1)))
        server.enqueue(MockResponse().setBody("I"))
        server.enqueue(MockResponse().setBody("1"))
        val dest = dest()

        val r = DashFlacDownloader(ffmpeg).download(OkHttpClient(), server.url("/m.mpd").toString(), emptyMap(), dest)

        assertThat(r.isFailure).isTrue()
        assertThat(dest.parentFile!!.listFiles()!!.toList()).isEmpty()
    }

    @Test fun `remux args stream-copy the first audio stream into flac`() {
        val args = DashFlacDownloader.buildRemuxArgs(File("/a/in.mp4"), File("/a/out.flac"))
        assertThat(args).containsAtLeast("-map", "0:a:0", "-c:a", "copy", "-vn", "-f", "flac").inOrder()
        assertThat(args.last()).endsWith("out.flac")
    }
}
