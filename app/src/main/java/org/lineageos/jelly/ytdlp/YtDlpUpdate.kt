/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ytdlp

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** True when version [latest] ("2026.12.01", "2026.11.23.1") comes after [current]. */
fun isNewer(latest: String, current: String): Boolean {
    val a = latest.split('.').map { it.toIntOrNull() ?: 0 }
    val b = current.split('.').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { -1 }
        if (x != y) return x > y
    }
    return false
}

/** The checksum listed for exactly [name] in a SHA2-256SUMS file. */
fun sumFor(sums: String, name: String): String? = sums.lineSequence()
    .map { it.trim().split(Regex("\\s+"), limit = 2) }
    .firstOrNull { it.size == 2 && it[1].removePrefix("*") == name }
    ?.get(0)?.lowercase()

/** Where the time of the last check is kept. */
interface UpdateStore {
    var lastCheck: Long
}

enum class Outcome { UP_TO_DATE, UPDATED, SKIPPED, FAILED }

class UpdateResult(val outcome: Outcome, val version: String? = null)

/**
 * Checks yt-dlp's latest release, at most once a day unless forced, and swaps
 * in the new file only when it matches the checksum published with it.
 */
class YtDlpUpdate(
    private val fetch: (String) -> ByteArray,
    private val now: () -> Long,
    private val store: UpdateStore,
) {
    fun check(current: String, target: File, force: Boolean): UpdateResult {
        val t = now()
        if (!force && t - store.lastCheck < DAY) return UpdateResult(Outcome.SKIPPED)
        store.lastCheck = t
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            val tag = JSONObject(fetch(API).decodeToString()).getString("tag_name")
            if (!isNewer(tag, current)) return UpdateResult(Outcome.UP_TO_DATE, current)
            val body = fetch("$RELEASES/$tag/yt-dlp")
            val expected = sumFor(fetch("$RELEASES/$tag/SHA2-256SUMS").decodeToString(), "yt-dlp")
            val actual = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
            if (expected == null || expected != actual) return UpdateResult(Outcome.FAILED)
            tmp.writeBytes(body)
            if (!tmp.renameTo(target)) throw IOException("couldn't replace $target")
            UpdateResult(Outcome.UPDATED, tag)
        } catch (e: Exception) {
            UpdateResult(Outcome.FAILED)
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private const val DAY = 24L * 60 * 60 * 1000
        private const val API = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
        private const val RELEASES = "https://github.com/yt-dlp/yt-dlp/releases/download"

        /** Plain HTTPS, with short limits. */
        fun httpFetch(url: String): ByteArray {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("Accept", "application/json, application/octet-stream")
            try {
                if (c.responseCode !in 200..299) throw IOException("the server answered ${c.responseCode}")
                return c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        }

        /** The real updater, keeping its check time in the app's preferences. */
        fun forApp(context: Context): YtDlpUpdate {
            val prefs = context.applicationContext.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
            val store = object : UpdateStore {
                override var lastCheck: Long
                    get() = prefs.getLong("last_check", 0L)
                    set(v) = prefs.edit().putLong("last_check", v).apply()
            }
            return YtDlpUpdate(::httpFetch, System::currentTimeMillis, store)
        }
    }
}
