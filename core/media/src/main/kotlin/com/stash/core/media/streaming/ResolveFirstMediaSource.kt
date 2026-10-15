package com.stash.core.media.streaming

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A `stash-resolve://` placeholder that resolves BEFORE it picks its media source.
 *
 * [LazyResolvingDataSource] resolves inside `DataSource.open()`, which fixes the
 * media source as progressive before the URL is known — fine for every rung but
 * Tidal, whose Hi-Res answer is usually a DASH MPD that a progressive extractor
 * cannot read. This source runs [resolve] (which warms [StreamUrlCache]) when the
 * player prepares it, then hands the result to [childFor]: a DASH source for an
 * MPD, otherwise the existing lazy progressive source, which then opens straight
 * from the warm cache. Used only while a Tidal server is configured, so the
 * proven lazy path is untouched for everyone else.
 *
 * Threading: [prepareSourceInternal] and every child call run on the playback
 * thread; the resolve runs on [scope] and posts its result back through a handler
 * on that thread. A release before the result lands drops it ([released]).
 */
@OptIn(UnstableApi::class)
internal class ResolveFirstMediaSource(
    private val mediaItem: MediaItem,
    private val scope: CoroutineScope,
    private val resolve: suspend () -> StreamUrl?,
    private val childFor: (StreamUrl?) -> MediaSource,
) : CompositeMediaSource<Unit>() {

    private var handler: Handler? = null
    private var job: Job? = null
    private var child: MediaSource? = null
    private var released = false

    override fun getMediaItem(): MediaItem = mediaItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        released = false
        val h = Util.createHandlerForCurrentLooper()
        handler = h
        job = scope.launch {
            val resolved = try {
                resolve()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A miss here is not the error to surface: the lazy child re-resolves
                // and reports its own IOException through the normal path.
                null
            }
            h.post {
                if (released) return@post
                val c = childFor(resolved)
                child = c
                prepareChildSource(Unit, c)
            }
        }
    }

    override fun onChildSourceInfoRefreshed(childSourceId: Unit, mediaSource: MediaSource, newTimeline: Timeline) {
        refreshSourceInfo(newTimeline)
    }

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        checkNotNull(child) { "createPeriod before the child was prepared" }
            .createPeriod(id, allocator, startPositionUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        child?.releasePeriod(mediaPeriod)
    }

    override fun releaseSourceInternal() {
        released = true
        job?.cancel()
        job = null
        handler?.removeCallbacksAndMessages(null)
        handler = null
        child = null
        super.releaseSourceInternal()
    }
}
