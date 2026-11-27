/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ytdlp

import android.util.Log
import org.lineageos.jelly.adblock.AdBlock
import java.io.File

/** Asks yt-dlp which versions of the video on a page exist. */
class YtDlpFinder(private val runtime: YtDlpRuntime) {
    sealed class Found {
        class Ok(val info: YtDlpInfo) : Found()
        class Failed(val problem: Problem) : Found()
        object Cancelled : Found()
    }

    /** Call off the main thread. [cookies] is a file from [writeCookies], or null. */
    fun find(page: String, cookies: File?, cancelled: () -> Boolean): Found {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        if (!runtime.prepare()) return Found.Failed(Problem.OTHER)
        val ready = ms()
        // At most once a day; a failed check never stops the download
        runCatching { runtime.update(force = false) }
        val checked = ms()
        if (cancelled()) return Found.Cancelled
        val args = listOf("-J", "--no-warnings", "--no-playlist") +
            (cookies?.let { listOf("--cookies", it.path) } ?: emptyList()) + page
        val r = runWithLimit(runtime.command(args), LIMIT_MS, cancelled)
        Log.i(AdBlock.TAG, "yt-dlp lookup: ready ${ready} ms, update check ${checked - ready} ms, lookup ${ms() - checked} ms, exit ${r.code}")
        if (r.cancelled) return Found.Cancelled
        if (r.timedOut) return Found.Failed(Problem.OTHER)
        if (r.code != 0) {
            Log.w(AdBlock.TAG, "yt-dlp lookup failed: ${r.err.lineSequence().lastOrNull { it.startsWith("ERROR:") } ?: r.err.takeLast(300)}")
            return Found.Failed(problemOf(r.err))
        }
        return runCatching { Found.Ok(YtDlpInfo.parse(r.out)) }.getOrElse { Found.Failed(Problem.OTHER) }
    }

    companion object {
        const val LIMIT_MS = 90_000L
    }
}
