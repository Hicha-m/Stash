package com.stash.core.data.weblibrary

import com.stash.core.data.db.dao.WebLibraryExportDao
import com.stash.core.data.db.dao.WebLibraryExportDao.ListenRow
import com.stash.core.data.db.dao.WebLibraryExportDao.SkipRow
import com.stash.core.data.weblink.handoff.HandoffWire
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.FilterOutputStream
import java.io.OutputStream

/** What the `listens` section of an export holds. */
data class ListensWritten(val plays: Int, val skips: Int)

/**
 * The library file's optional `listens` section (stash-player `docs/library-file-v1.md`, "Listens (optional, added
 * 2026-10-23)"): this phone's whole listening history, for Your record on the web.
 *
 * ```
 * "listens": { "device": "phone", "plays": [{t,a,al?,s,ms?,d?}], "skips": [{t,a,al?,s,ms}] }
 * ```
 *
 * It is too big to build in memory (a decade is about 100,000 plays), so [write] streams it: the database is read a page at
 * a time ([PAGE] rows, newest first) and each entry is written as it is read. A v1 reader that doesn't know `listens`
 * ignores it, so the file stays `stash-web-library` v1.
 */
object WebLibraryListens {
    /** A reader takes at most this many plays; the newest are kept. */
    const val MAX_PLAYS = 500_000

    /** A reader takes at most this many skips; the newest are kept. */
    const val MAX_SKIPS = 200_000

    /** Rows read from the database at a time. */
    const val PAGE = 5_000

    /** A song's words are cut as a Song's are (title, artist, album: 300). */
    const val MAX_TEXT = 300

    /** Informational: where these plays were made. */
    const val DEVICE = "phone"

    /** One play: the song's words, when it started, how long it played (when known), the song's length (when known). */
    @Serializable
    data class Play(val t: String, val a: String, val al: String? = null, val s: Long, val ms: Long? = null, val d: Long? = null)

    /** One skip: the song's words, when that play started, and how far it got. */
    @Serializable
    data class Skip(val t: String, val a: String, val al: String? = null, val s: Long, val ms: Long)

    /** Compact, nulls left out: `{"t":"Ivy","a":"Frank Ocean","s":1759838400000}`. */
    private val json = Json { explicitNulls = false }

    /** [row] as a play, or null when it has no words left after cleaning or no start time. */
    fun play(row: ListenRow): Play? {
        val t = HandoffWire.cleanText(row.title, MAX_TEXT).ifEmpty { return null }
        val a = HandoffWire.cleanText(row.artist, MAX_TEXT).ifEmpty { return null }
        if (row.startedAt <= 0) return null
        return Play(
            t = t,
            a = a,
            al = HandoffWire.cleanText(row.album, MAX_TEXT).ifEmpty { null },
            s = row.startedAt,
            // Unknown (a row from before v59, or the app died first): left out, and the reader falls back to `d`.
            ms = row.listenedMs?.takeIf { it > 0 },
            d = row.durationMs.takeIf { it > 0 },
        )
    }

    /** [row] as a skip, `s` being when that play started (the skip's time minus its position), or null when it can't be. */
    fun skip(row: SkipRow): Skip? {
        val t = HandoffWire.cleanText(row.title, MAX_TEXT).ifEmpty { return null }
        val a = HandoffWire.cleanText(row.artist, MAX_TEXT).ifEmpty { return null }
        val position = row.positionMs.coerceAtLeast(0)
        val startedAt = row.skippedAt - position
        if (startedAt <= 0) return null
        return Skip(t = t, a = a, al = HandoffWire.cleanText(row.album, MAX_TEXT).ifEmpty { null }, s = startedAt, ms = position)
    }

    /**
     * Writes `,"listens":{…}` to [out], the part after the rest of the file and before its closing brace, reading the plays
     * and skips from [dao]. At most [budgetBytes] go out: when the next entry wouldn't fit, that list ends there (the
     * newest are already in, since pages run newest first). Plays come first, as they matter more to the record.
     */
    suspend fun write(
        dao: WebLibraryExportDao,
        out: OutputStream,
        budgetBytes: Long,
        maxPlays: Int = MAX_PLAYS,
        maxSkips: Int = MAX_SKIPS,
        pageSize: Int = PAGE,
    ): ListensWritten {
        val w = BudgetedWriter(out, budgetBytes)
        w.raw(""","listens":{"device":"$DEVICE","plays":[""")

        var plays = 0
        var at = Long.MAX_VALUE
        var id = Long.MAX_VALUE
        pages@ while (plays < maxPlays) {
            currentCoroutineContext().ensureActive()
            val rows = dao.ownPlaysBefore(at, id, pageSize)
            for (row in rows) {
                at = row.startedAt
                id = row.id
                val p = play(row) ?: continue
                if (!w.entry(json.encodeToString(Play.serializer(), p))) break@pages
                if (++plays >= maxPlays) break@pages
            }
            if (rows.size < pageSize) break
        }

        w.raw("""],"skips":[""")
        w.first = true
        var skips = 0
        at = Long.MAX_VALUE
        id = Long.MAX_VALUE
        pages@ while (skips < maxSkips) {
            currentCoroutineContext().ensureActive()
            val rows = dao.ownSkipsBefore(at, id, pageSize)
            for (row in rows) {
                at = row.skippedAt
                id = row.id
                val s = skip(row) ?: continue
                if (!w.entry(json.encodeToString(Skip.serializer(), s))) break@pages
                if (++skips >= maxSkips) break@pages
            }
            if (rows.size < pageSize) break
        }
        w.raw("]}")
        return ListensWritten(plays, skips)
    }

    /**
     * Writes entries of a JSON array while they fit in [budget] bytes, always keeping [TAIL] bytes back for what closes the
     * section and the file (`],"skips":[` `]}` `}`), so the file never goes over even when an entry is refused.
     */
    private class BudgetedWriter(private val out: OutputStream, private val budget: Long) {
        private var written = 0L
        var first = true

        fun raw(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(b)
            written += b.size
        }

        /** Writes [entry] (with its comma) and returns true, or returns false when it would not fit. */
        fun entry(entry: String): Boolean {
            val b = entry.toByteArray(Charsets.UTF_8)
            val need = b.size + (if (first) 0 else 1)
            if (written + need + TAIL > budget) return false
            if (!first) out.write(','.code)
            out.write(b)
            written += need
            first = false
            return true
        }

        companion object {
            /** `],"skips":[` (11) + `]}` (2) + the file's `}` (1), rounded up. */
            const val TAIL = 16
        }
    }

    /**
     * Passes everything through except the last byte written, which it holds back: the rest of the file is written through
     * it, so its closing `}` stays here while `listens` goes in before it. Doesn't close the stream under it.
     */
    class HoldLastByte(out: OutputStream) : FilterOutputStream(out) {
        private var held = -1

        override fun write(b: Int) {
            if (held >= 0) out.write(held)
            held = b and 0xff
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            if (held >= 0) out.write(held)
            out.write(b, off, len - 1)
            held = b[off + len - 1].toInt() and 0xff
        }

        /** The byte held back (-1 when nothing was written), forgotten here. */
        fun release(): Int = held.also { held = -1 }

        override fun close() = flush()
    }
}
