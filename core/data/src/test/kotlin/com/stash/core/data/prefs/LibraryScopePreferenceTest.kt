package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LibraryScopePreferenceTest {
    private lateinit var context: Context
    private lateinit var preference: LibraryScopePreference
    private lateinit var file: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // A DataStore file, so the settings backup (datastore/) carries the choice.
        file = context.preferencesDataStoreFile("library_scope_preference")
        if (file.exists()) file.delete()
        preference = LibraryScopePreference(context)
    }

    @After fun tearDown() {
        if (file.exists()) file.delete()
    }

    @Test fun `Everything until the user picks In your library, then it stays`() = runTest {
        assertFalse(preference.current())

        preference.setInLibraryOnly(true)
        assertTrue(preference.current())
        assertTrue(preference.inLibraryOnly.first())

        preference.setInLibraryOnly(false)
        assertFalse(preference.current())
    }
}
