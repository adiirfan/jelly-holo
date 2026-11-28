/*
 * SPDX-FileCopyrightText: Jelly Holo contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupsTest {
    private val page = "https://www.example-news.com/article/1"

    @Test
    fun a_tab_on_the_same_site_opens() {
        assertTrue(Popups.allow(page, "https://example-news.com/gallery", tappedLink = null))
        assertTrue(Popups.allow(page, "https://video.example-news.com/play", tappedLink = null))
    }

    @Test
    fun a_tapped_link_opens_where_it_points() {
        assertTrue(Popups.allow(page, "https://other-site.org/story", tappedLink = "https://other-site.org/story"))
    }

    @Test
    fun a_tap_anywhere_that_opens_another_site_is_a_pop_up() {
        assertFalse(Popups.allow(page, "https://ads.popnet.example/click?id=9", tappedLink = null))
        // A link to one place, and a tab to somewhere else
        assertFalse(Popups.allow(page, "https://ads.popnet.example/x", tappedLink = "https://www.example-news.com/next"))
    }

    @Test
    fun sign_in_windows_always_open() {
        for (url in listOf(
            "https://accounts.google.com/o/oauth2/auth?client_id=1",
            "https://appleid.apple.com/auth/authorize",
            "https://login.microsoftonline.com/common/oauth2",
            "https://www.facebook.com/v19.0/dialog/oauth",
            "https://github.com/login/oauth/authorize",
            "https://discord.com/oauth2/authorize",
            "https://x.com/i/oauth2/authorize",
        )) {
            assertTrue(url, Popups.allow(page, url, tappedLink = null))
        }
    }

    @Test
    fun a_look_alike_address_is_not_a_sign_in_window() {
        assertFalse(Popups.allow(page, "https://accounts.google.com.evil.example/login", tappedLink = null))
        assertFalse(Popups.allow(page, "https://notgithub.com/login", tappedLink = null))
    }

    @Test
    fun blank_and_unknown_openers_do_not_crash_and_stay_closed() {
        assertFalse(Popups.allow(null, "https://ads.popnet.example/", tappedLink = null))
        assertFalse(Popups.allow(page, "not a url", tappedLink = null))
    }
}
