/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import org.lineageos.jelly.dao.DownloadDao
import org.lineageos.jelly.model.DownloadKind
import org.lineageos.jelly.model.DownloadState

class DownloadConverters {
    @TypeConverter fun kind(k: DownloadKind): String = k.name
    @TypeConverter fun kind(s: String): DownloadKind = DownloadKind.valueOf(s)
    @TypeConverter fun state(k: DownloadState): String = k.name
    @TypeConverter fun state(s: String): DownloadState = DownloadState.valueOf(s)
}

@Database(entities = [org.lineageos.jelly.model.DownloadItem::class], version = 1)
@TypeConverters(DownloadConverters::class)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: DownloadDatabase? = null

        /** Shared by the browser and the torrent process: both see each other's changes. */
        fun get(context: Context): DownloadDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, DownloadDatabase::class.java, "DownloadDatabase")
                .enableMultiInstanceInvalidation()
                .build()
                .also { INSTANCE = it }
        }
    }
}
