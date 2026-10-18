package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Dedicated DataStore for the Library Artists and Albums "Show" choice. */
private val Context.libraryScopeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "library_scope_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The Library sort sheet's "Show" choice on Artists and Albums, in Stream only: "Everything"
 * (false, the default: every song in the app) or "In your library" (true: only the songs the
 * Songs tab lists, `IN_LIBRARY` in TrackDao.kt).
 *
 * One value for the tabs, their counts, the artist and album pages and the artist-photo
 * backfill: [com.stash.core.data.repository.MusicRepositoryImpl] applies it to every Library
 * artist/album read, so a card's count always matches the page it opens. Download mode ignores
 * it (everything listed there is downloaded, so in the library already); the saved choice is
 * kept and comes back with Stream only.
 */
@Singleton
class LibraryScopePreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val inLibraryOnlyKey = booleanPreferencesKey("artists_albums_in_library_only")

    val inLibraryOnly: Flow<Boolean> = context.libraryScopeDataStore.data.map { prefs ->
        prefs[inLibraryOnlyKey] ?: false
    }

    suspend fun current(): Boolean = inLibraryOnly.first()

    suspend fun setInLibraryOnly(value: Boolean) {
        context.libraryScopeDataStore.edit { it[inLibraryOnlyKey] = value }
    }
}
