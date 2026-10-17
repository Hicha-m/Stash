package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v55 -> v56 adds the empty `tidal_match_cache` table and leaves every existing row alone. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV55V56Test {

    private val DB_NAME = "migration-v55v56-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `55 to 56 adds tidal_match_cache and keeps tracks`() {
        helper.createDatabase(DB_NAME, 55).use { db ->
            db.execSQL(
                "INSERT INTO tracks (id, title, artist, album, duration_ms, file_format, quality_kbps, " +
                    "file_size_bytes, source, date_added, play_count, is_downloaded, canonical_title, " +
                    "canonical_artist, match_confidence, match_dismissed, match_flagged) " +
                    "VALUES (1, 'Song', 'A', '', 1000, 'opus', 160, 0, 'YOUTUBE', 0, 0, 0, 'song', 'a', 0.0, 0, 0)",
            )
        }

        // validateDroppedTables = true also checks the new table against the v56 schema JSON.
        val db = helper.runMigrationsAndValidate(DB_NAME, 56, true, StashDatabase.MIGRATION_55_56)

        db.query("SELECT COUNT(*) FROM tracks").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.execSQL(
            "INSERT INTO tidal_match_cache (`key`, tidal_id, title, artist, artwork, confidence, searched_at, " +
                "lossless_miss_at, aac_miss_at) VALUES ('isrc:X', 'td:1', 'Song', 'A', NULL, 0.9, 5, NULL, NULL)",
        )
        db.query("SELECT tidal_id, searched_at FROM tidal_match_cache WHERE `key` = 'isrc:X'").use { c ->
            c.moveToFirst()
            assertEquals("td:1", c.getString(0))
            assertEquals(5L, c.getLong(1))
        }
    }
}
