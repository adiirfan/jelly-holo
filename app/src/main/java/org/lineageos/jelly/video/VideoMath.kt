/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.video

/** "1:05", "1:02:03"; "--:--" for an unknown length. */
fun formatTime(seconds: Double): String {
    if (seconds < 0 || seconds.isNaN()) return "--:--"
    val s = seconds.toLong()
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

enum class Side { BACK, MIDDLE, FORWARD }

/** Which third of the screen a double tap landed in. */
fun sideOf(x: Float, width: Float): Side = when {
    x < width / 3 -> Side.BACK
    x > width * 2 / 3 -> Side.FORWARD
    else -> Side.MIDDLE
}

/** Where a sideways swipe moves playback to: 90 s per screen width, inside the video. */
fun scrubTo(start: Double, dx: Float, width: Float, duration: Double): Double {
    val t = start + dx / width * SCRUB_SECONDS_PER_WIDTH
    val end = if (duration > 0) duration else Double.MAX_VALUE
    return t.coerceIn(0.0, end)
}

/** A level (brightness, volume) after a vertical swipe: a full screen height is the whole range. */
fun levelAfterSwipe(start: Float, dy: Float, height: Float): Float =
    (start - dy / height).coerceIn(0f, 1f)

const val SCRUB_SECONDS_PER_WIDTH = 90.0
