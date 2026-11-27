/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class YtDlpRuntimeTest {
    @Test
    fun environment_points_everything_at_the_app_and_lets_python_keep_compiled_code() {
        val env = environment(File("/h"), File("/c"), File("/p"), File("/k.pem"))
        assertEquals(
            mapOf(
                "PYTHONHOME" to "/p",
                "LD_LIBRARY_PATH" to "/p/lib",
                "HOME" to "/h",
                "XDG_CACHE_HOME" to "/c",
                "SSL_CERT_FILE" to "/k.pem",
            ),
            env,
        )
    }

    private fun timed(block: () -> RunResult): Pair<RunResult, Long> {
        val t = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t) / 1_000_000
    }

    @Test
    fun runWithLimit_kills_on_timeout() {
        val (r, ms) = timed { runWithLimit(ProcessBuilder("sleep", "30"), 300, { false }) }
        assertTrue(r.timedOut)
        assertFalse(r.cancelled)
        assertTrue("took $ms ms", ms < 2000)
    }

    @Test
    fun runWithLimit_kills_on_cancel() {
        val start = System.nanoTime()
        val (r, ms) = timed {
            runWithLimit(ProcessBuilder("sleep", "30"), 30_000, { System.nanoTime() - start > 200_000_000 })
        }
        assertTrue(r.cancelled)
        assertFalse(r.timedOut)
        assertTrue("took $ms ms", ms < 2000)
    }

    @Test
    fun runWithLimit_kills_on_stall() {
        val (r, ms) = timed {
            runWithLimit(ProcessBuilder("sleep", "30"), Long.MAX_VALUE, { false }, stallMs = 300, growing = { 5L })
        }
        assertTrue(r.timedOut)
        assertTrue("took $ms ms", ms < 2000)
    }

    @Test
    fun runWithLimit_stall_clock_starts_at_the_first_byte() {
        // yt-dlp looks the video up before writing anything: that's not a stall
        val start = System.nanoTime()
        val (r, ms) = timed {
            runWithLimit(
                ProcessBuilder("sleep", "30"), 1_500, { false }, stallMs = 300,
                growing = { if (System.nanoTime() - start > 1_000_000_000) 5L else 0L },
            )
        }
        assertTrue(r.timedOut)
        assertTrue("stopped as a stall before any byte, after $ms ms", ms >= 1_200)
    }

    @Test
    fun runWithLimit_keeps_output() {
        val r = runWithLimit(ProcessBuilder("sh", "-c", "echo out; echo err >&2"), 10_000, { false })
        assertEquals(0, r.code)
        assertEquals("out\n", r.out)
        assertEquals("err\n", r.err)
    }
}
