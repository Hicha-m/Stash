package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.stash.core.data.db.entity.TidalMatchEntity

/** Storage for [TidalMatchEntity]. Freshness (TTLs) is the caller's concern. */
@Dao
interface TidalMatchDao {

    @Query("SELECT * FROM tidal_match_cache WHERE `key` = :key")
    suspend fun get(key: String): TidalMatchEntity?

    @Upsert
    suspend fun upsert(entity: TidalMatchEntity)

    /** Drops rows whose newest stamp is older than [before]; keeps the table small. */
    @Query(
        "DELETE FROM tidal_match_cache WHERE searched_at < :before " +
            "AND COALESCE(lossless_miss_at, 0) < :before AND COALESCE(aac_miss_at, 0) < :before",
    )
    suspend fun deleteOlderThan(before: Long): Int
}
