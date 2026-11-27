/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ytdlp

import org.lineageos.jelly.R

/** Why yt-dlp couldn't get a video, in the terms the user is told. */
enum class Problem(val message: Int) {
    SIGN_IN(R.string.ytdlp_sign_in),
    NO_VIDEO(R.string.ytdlp_no_video),
    PROTECTED(R.string.ytdlp_protected),
    UNAVAILABLE(R.string.ytdlp_unavailable),
    LIVE(R.string.ytdlp_live),
    OTHER(R.string.ytdlp_failed),
}

/** Reads yt-dlp's last "ERROR:" line. More specific reasons are checked first. */
fun problemOf(stderr: String): Problem {
    val line = stderr.lineSequence().lastOrNull { it.startsWith("ERROR:") }?.lowercase() ?: return Problem.OTHER
    return when {
        "drm" in line -> Problem.PROTECTED
        "private video" in line || "not available" in line || "unavailable" in line -> Problem.UNAVAILABLE
        "is live" in line || "live event" in line -> Problem.LIVE
        listOf("authentication", "sign in", "log in", "login").any { it in line } -> Problem.SIGN_IN
        "unsupported url" in line -> Problem.NO_VIDEO
        else -> Problem.OTHER
    }
}
