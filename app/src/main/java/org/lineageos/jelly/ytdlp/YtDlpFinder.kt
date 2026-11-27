/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ytdlp

import android.util.Log
import org.lineageos.jelly.adblock.AdBlock
import java.io.File

/** Asks yt-dlp which versions of the video on a page exist. */
class YtDlpFinder(
    private val prepare: (cancelled: () -> Boolean) -> Boolean,
    private val update: (force: Boolean) -> UpdateResult,
    private val run: (args: List<String>, cancelled: () -> Boolean) -> RunResult,
    private val log: (String) -> Unit,
) {
    constructor(runtime: YtDlpRuntime) : this(
        prepare = runtime::prepare,
        update = runtime::update,
        run = { args, cancelled -> runWithLimit(runtime.command(args), LIMIT_MS, cancelled) },
        log = { Log.i(AdBlock.TAG, it) },
    )

    sealed class Found {
        class Ok(val info: YtDlpInfo) : Found()
        class Failed(val problem: Problem) : Found()
        object Cancelled : Found()
    }

    /** Call off the main thread. [cookies] is a file from [writeCookies], or null. */
    fun find(page: String, cookies: File?, cancelled: () -> Boolean): Found {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        if (!prepare(cancelled)) return if (cancelled()) Found.Cancelled else Found.Failed(Problem.OTHER)
        val ready = ms()
        // At most once a day; a failed check never stops the download
        runCatching { update(false) }
        val checked = ms()
        if (cancelled()) return Found.Cancelled
        val args = listOf("-J", "--no-warnings", "--no-playlist") +
            (cookies?.let { listOf("--cookies", it.path) } ?: emptyList()) + page
        val first = lookup(args, cancelled)
        log("yt-dlp lookup: ready $ready ms, update check ${checked - ready} ms, lookup ${ms() - checked} ms")
        // A failure yt-dlp doesn't explain is often a site change a newer yt-dlp already handles
        if (first is Found.Failed && first.problem == Problem.OTHER && !cancelled()) {
            val r = runCatching { update(true) }.getOrNull()
            if (r?.outcome == Outcome.UPDATED) {
                log("yt-dlp lookup: retrying with ${r.version}")
                return lookup(args, cancelled)
            }
        }
        return first
    }

    private fun lookup(args: List<String>, cancelled: () -> Boolean): Found {
        val r = run(args, cancelled)
        if (r.cancelled) return Found.Cancelled
        if (r.timedOut) return Found.Failed(Problem.OTHER)
        if (r.code != 0) {
            log("yt-dlp lookup failed (exit ${r.code}): ${r.err.lineSequence().lastOrNull { it.startsWith("ERROR:") } ?: r.err.takeLast(300)}")
            return Found.Failed(problemOf(r.err))
        }
        return runCatching { Found.Ok(YtDlpInfo.parse(r.out)) }.getOrElse { Found.Failed(Problem.OTHER) }
    }

    companion object {
        const val LIMIT_MS = 90_000L
    }
}
