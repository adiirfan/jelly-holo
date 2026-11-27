/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.torrent

import java.net.URLDecoder

fun isMagnet(url: String): Boolean = url.startsWith("magnet:", ignoreCase = true)

/** A download that is a .torrent file, by its type, its address or its file name. */
fun isTorrentDownload(url: String?, mime: String?, contentDisposition: String?): Boolean {
    if (mime.equals("application/x-bittorrent", ignoreCase = true)) return true
    val path = url?.substringBefore('#')?.substringBefore('?')
    if (path != null && path.endsWith(".torrent", ignoreCase = true)) return true
    val name = contentDisposition?.let { Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(it) }
    return name?.groupValues?.get(1)?.trim()?.endsWith(".torrent", ignoreCase = true) == true
}

/** The name a magnet link gives its torrent (its `dn`), or null. */
fun magnetName(uri: String): String? = uri.substringAfter('?', "").split('&')
    .firstOrNull { it.startsWith("dn=", ignoreCase = true) }
    ?.substring(3)
    ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
    ?.takeIf { it.isNotBlank() }

/** A magnet starts on a tap, or on a link that forwards to one right after a tap. Never by a page alone. */
fun allowMagnet(hasGesture: Boolean, isRedirect: Boolean, msSinceTap: Long): Boolean =
    hasGesture || (isRedirect && msSinceTap in 0..TAP_WINDOW_MS)

private const val TAP_WINDOW_MS = 5_000L
