/*
 * SPDX-FileCopyrightText: Jelly Holo contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.webview

import java.net.URI

/**
 * Which new tabs a page may open. Ad pages open another site in a new tab on
 * any tap anywhere; real new tabs come from a tapped link, stay on the same
 * site, or are a sign-in window.
 */
object Popups {
    /** Sign-in windows that open from a button, not a link ("Sign in with …"). */
    private val SIGN_IN_HOSTS = setOf(
        "accounts.google.com",
        "appleid.apple.com",
        "login.microsoftonline.com",
        "login.live.com",
        "facebook.com",
        "github.com",
        "discord.com",
        "x.com",
        "twitter.com",
        "api.twitter.com",
    )

    fun allow(opener: String?, target: String, tappedLink: String?): Boolean {
        val to = host(target) ?: return false
        if (SIGN_IN_HOSTS.any { to == it || to == "www.$it" }) return true
        val from = opener?.let(::host)
        if (from != null && sameSite(from, to)) return true
        val link = tappedLink?.let(::host)
        return link != null && sameSite(link, to)
    }

    private fun host(url: String): String? =
        runCatching { URI(url.trim()).host?.lowercase()?.removePrefix("www.") }.getOrNull()?.takeIf { it.contains('.') }

    /** Same site, roughly: the last two parts of the name match (three for names like example.co.uk). */
    private fun sameSite(a: String, b: String): Boolean = site(a) == site(b)

    private fun site(host: String): String {
        val parts = host.split('.')
        val keep = if (parts.size >= 3 && parts[parts.size - 2].length <= 3 && parts.last().length == 2) 3 else 2
        return parts.takeLast(keep).joinToString(".")
    }
}
