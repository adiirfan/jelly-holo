/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Test

class YtDlpErrorsTest {
    @Test
    fun reddit_login_wall_means_sign_in() = assertEquals(
        Problem.SIGN_IN,
        problemOf(
            "ERROR: [Reddit] 90bu6w: Account authentication is required. Use --cookies-from-browser or " +
                "--cookies for the authentication. See  https://github.com/yt-dlp/yt-dlp/wiki/FAQ"
        ),
    )

    @Test
    fun youtube_bot_check_means_sign_in() = assertEquals(
        Problem.SIGN_IN,
        problemOf("ERROR: [youtube] x: Sign in to confirm you’re not a bot."),
    )

    @Test
    fun unsupported_url_means_no_video() =
        assertEquals(Problem.NO_VIDEO, problemOf("ERROR: Unsupported URL: https://example.com/"))

    @Test
    fun drm_means_protected() =
        assertEquals(Problem.PROTECTED, problemOf("ERROR: [youtube] x: This video is DRM protected"))

    @Test
    fun private_video_means_unavailable() =
        assertEquals(Problem.UNAVAILABLE, problemOf("ERROR: [youtube] x: Private video. Sign in if you've been granted access"))

    @Test
    fun live_event_means_live() =
        assertEquals(Problem.LIVE, problemOf("ERROR: [youtube] x: This live event will begin in 3 hours."))

    @Test
    fun not_currently_live_is_something_else() =
        assertEquals(Problem.OTHER, problemOf("ERROR: [twitch:stream] x: The channel is not currently live"))

    @Test
    fun the_last_error_line_counts() = assertEquals(
        Problem.NO_VIDEO,
        problemOf("WARNING: [generic] login form found\nERROR: Unsupported URL: https://example.com/\n"),
    )

    @Test
    fun anything_else_is_other() = assertEquals(Problem.OTHER, problemOf("ERROR: something odd"))

    @Test
    fun no_error_line_is_other() = assertEquals(Problem.OTHER, problemOf("Traceback (most recent call last):"))
}
