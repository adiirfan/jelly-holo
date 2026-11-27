/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.lineageos.jelly.model.DownloadItem

@Dao
interface DownloadDao {
    /** Active downloads first, then the rest; newest first within each. */
    @Query(
        "SELECT * FROM downloads ORDER BY " +
            "CASE WHEN state IN ('DONE', 'FAILED', 'CANCELLED') THEN 1 ELSE 0 END, updated DESC"
    )
    fun all(): Flow<List<DownloadItem>>

    @Insert
    suspend fun insert(item: DownloadItem): Long

    @Update
    suspend fun update(item: DownloadItem)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun byId(id: Long): DownloadItem?

    @Query("SELECT * FROM downloads WHERE infoHash = :hash LIMIT 1")
    suspend fun byInfoHash(hash: String): DownloadItem?

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM downloads WHERE dmId >= 0 AND state IN ('QUEUED', 'RUNNING', 'PAUSED')")
    suspend fun activeDm(): List<DownloadItem>
}
