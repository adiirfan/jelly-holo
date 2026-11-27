/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest

class YtDlpUpdateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val api = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
    private fun file(tag: String) = "https://github.com/yt-dlp/yt-dlp/releases/download/$tag/yt-dlp"
    private fun sums(tag: String) = "https://github.com/yt-dlp/yt-dlp/releases/download/$tag/SHA2-256SUMS"
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private class Store : UpdateStore {
        override var lastCheck = 0L
    }

    private val day = 24L * 60 * 60 * 1000

    private fun setup(tag: String, body: ByteArray, sum: String = sha(body)): Pair<MutableList<String>, (String) -> ByteArray> {
        val asked = mutableListOf<String>()
        val pages = mapOf(
            api to """{"tag_name": "$tag", "name": "yt-dlp $tag"}""".toByteArray(),
            file(tag) to body,
            sums(tag) to "$sum  yt-dlp\n${"0".repeat(64)}  yt-dlp.exe\n${"1".repeat(64)}  yt-dlp_linux\n".toByteArray(),
        )
        return asked to { url: String -> asked += url; pages[url] ?: throw IOException("404 $url") }
    }

    @Test
    fun newer_versions() {
        assertTrue(isNewer("2026.11.23.1", "2026.11.23"))
        assertTrue(isNewer("2026.12.01", "2026.11.23.1"))
        assertTrue(isNewer("2027.01.02", "2026.12.31"))
        assertFalse(isNewer("2026.11.23", "2026.11.23"))
        assertFalse(isNewer("2026.11.22", "2026.11.23"))
        assertTrue(isNewer("2026.11.23", ""))
    }

    @Test
    fun sum_for_the_exact_name() {
        val text = "${"a".repeat(64)}  yt-dlp.exe\n${"b".repeat(64)}  yt-dlp\n${"c".repeat(64)}  yt-dlp_linux\n"
        assertEquals("b".repeat(64), sumFor(text, "yt-dlp"))
        assertNull(sumFor(text, "yt-dlp_macos"))
    }

    @Test
    fun updates_when_newer_and_sum_matches() {
        val target = tmp.root.resolve("yt-dlp").apply { writeText("old") }
        val (_, fetch) = setup("2026.12.01", "new".toByteArray())
        val r = YtDlpUpdate(fetch, { 10 * day }, Store()).check("2026.11.23", target, force = false)
        assertEquals(Outcome.UPDATED, r.outcome)
        assertEquals("2026.12.01", r.version)
        assertEquals("new", target.readText())
        assertFalse(tmp.root.resolve("yt-dlp.tmp").exists())
    }

    @Test
    fun keeps_old_file_on_sum_mismatch() {
        val target = tmp.root.resolve("yt-dlp").apply { writeText("old") }
        val (_, fetch) = setup("2026.12.01", "new".toByteArray(), sum = "f".repeat(64))
        val r = YtDlpUpdate(fetch, { 10 * day }, Store()).check("2026.11.23", target, force = false)
        assertEquals(Outcome.FAILED, r.outcome)
        assertEquals("old", target.readText())
        assertFalse(tmp.root.resolve("yt-dlp.tmp").exists())
    }

    @Test
    fun skips_within_a_day_unless_forced() {
        val target = tmp.root.resolve("yt-dlp").apply { writeText("old") }
        val (asked, fetch) = setup("2026.11.23", "same".toByteArray())
        val store = Store()
        var now = 10 * day
        val u = YtDlpUpdate(fetch, { now }, store)
        assertEquals(Outcome.UP_TO_DATE, u.check("2026.11.23", target, force = false).outcome)
        now += day - 1
        assertEquals(Outcome.SKIPPED, u.check("2026.11.23", target, force = false).outcome)
        assertEquals(Outcome.UP_TO_DATE, u.check("2026.11.23", target, force = true).outcome)
        now += day
        assertEquals(Outcome.UP_TO_DATE, u.check("2026.11.23", target, force = false).outcome)
        assertEquals(3, asked.size)
    }

    @Test
    fun up_to_date_downloads_nothing_else() {
        val target = tmp.root.resolve("yt-dlp").apply { writeText("old") }
        val (asked, fetch) = setup("2026.11.23", "same".toByteArray())
        YtDlpUpdate(fetch, { 10 * day }, Store()).check("2026.11.23", target, force = false)
        assertEquals(listOf(api), asked)
        assertEquals("old", target.readText())
    }

    @Test
    fun network_failure_is_failed_and_keeps_the_file() {
        val target = tmp.root.resolve("yt-dlp").apply { writeText("old") }
        val r = YtDlpUpdate({ throw IOException("offline") }, { 10 * day }, Store()).check("2026.11.23", target, force = true)
        assertEquals(Outcome.FAILED, r.outcome)
        assertEquals("old", target.readText())
    }
}
