/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ytdlp

import java.io.File
import java.net.URI

/** The cookie domain for a page: its host without a leading "www." or "m.", covering its subdomains. */
fun cookieDomain(pageUrl: String): String? {
    val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    // Drop a leading "www." or "m.", but never down to a bare top-level domain
    val site = listOf("www.", "m.").fold(host) { h, p ->
        if (h.startsWith(p) && '.' in h.removePrefix(p)) h.removePrefix(p) else h
    }
    return ".$site"
}

/**
 * Writes the browser's cookies for [pageUrl] (as the browser sends them, "a=1; b=2")
 * in the file format yt-dlp reads with --cookies. Nothing is written for a
 * private tab or when there are no cookies; returns whether a file was written.
 */
fun writeCookies(file: File, pageUrl: String, cookieHeader: String?, private: Boolean): Boolean {
    if (private || cookieHeader.isNullOrBlank()) return false
    val domain = cookieDomain(pageUrl) ?: return false
    val rows = cookieHeader.split(';').mapNotNull { part ->
        val c = part.trim()
        val eq = c.indexOf('=')
        if (eq <= 0) null else "$domain\tTRUE\t/\tTRUE\t0\t${c.substring(0, eq)}\t${c.substring(eq + 1)}"
    }
    if (rows.isEmpty()) return false
    file.writeText((listOf("# Netscape HTTP Cookie File") + rows).joinToString("\n", postfix = "\n"))
    return true
}

/**
 * The Cookie header to send to [url]: the browser's cookies for that address
 * itself (not the page's), and none at all from a private tab.
 */
fun cookieHeaderFor(url: String, private: Boolean, lookup: (String) -> String?): String? =
    if (private) null else lookup(url)?.takeIf { it.isNotBlank() }
