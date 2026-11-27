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

class YtDlpCookiesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun cookie_domain_covers_the_site() {
        assertEquals(".youtube.com", cookieDomain("https://m.youtube.com/watch?v=x"))
        assertEquals(".reddit.com", cookieDomain("https://www.reddit.com/r/a"))
        assertEquals(".old.reddit.com", cookieDomain("https://old.reddit.com/"))
        assertEquals(".instagram.com", cookieDomain("https://instagram.com/reel/x/"))
        assertNull(cookieDomain("file:///x"))
        assertNull(cookieDomain("not a url"))
    }

    @Test
    fun writes_each_cookie_for_the_site_only() {
        val f = tmp.root.resolve("c.txt")
        assertTrue(writeCookies(f, "https://m.youtube.com/watch?v=x", "a=1; b=two=2", private = false))
        val lines = f.readLines()
        assertEquals("# Netscape HTTP Cookie File", lines[0])
        val rows = lines.drop(1).filter { it.isNotBlank() }
        assertEquals(
            listOf(
                ".youtube.com\tTRUE\t/\tTRUE\t0\ta\t1",
                ".youtube.com\tTRUE\t/\tTRUE\t0\tb\ttwo=2",
            ),
            rows,
        )
    }

    @Test
    fun private_tab_writes_nothing() {
        val f = tmp.root.resolve("c.txt")
        assertFalse(writeCookies(f, "https://www.youtube.com/", "a=1", private = true))
        assertFalse(f.exists())
    }

    @Test
    fun blank_header_writes_nothing() {
        val f = tmp.root.resolve("c.txt")
        assertFalse(writeCookies(f, "https://www.youtube.com/", "  ", private = false))
        assertFalse(writeCookies(f, "https://www.youtube.com/", null, private = false))
        assertFalse(f.exists())
    }
}
