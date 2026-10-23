package com.stash.core.data.weblibrary

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The export picker's last choice (link-sync spec §2.3: "The last selection is remembered"). Playlists are remembered by what
 * was *un*ticked, so a playlist made later is ticked the first time it appears, as everything is the first time.
 * [Remembered.listens] is "Your whole listening history" (on the first time too).
 */
@Singleton
class WebLibraryPickerPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    data class Remembered(
        val likes: Boolean = true,
        val plays: Boolean = true,
        val unticked: Set<Long> = emptySet(),
        val listens: Boolean = true,
    )

    fun load(): Remembered = Remembered(
        likes = prefs.getBoolean(KEY_LIKES, true),
        plays = prefs.getBoolean(KEY_PLAYS, true),
        unticked = prefs.getString(KEY_UNTICKED, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet(),
        listens = prefs.getBoolean(KEY_LISTENS, true),
    )

    fun save(r: Remembered) {
        prefs.edit()
            .putBoolean(KEY_LIKES, r.likes)
            .putBoolean(KEY_PLAYS, r.plays)
            .putString(KEY_UNTICKED, r.unticked.sorted().joinToString(","))
            .putBoolean(KEY_LISTENS, r.listens)
            .apply()
    }

    private companion object {
        const val FILE = "web_library_picker"
        const val KEY_LIKES = "likes"
        const val KEY_PLAYS = "plays"
        const val KEY_UNTICKED = "unticked_playlists"
        const val KEY_LISTENS = "listens"
    }
}
