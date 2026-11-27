/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

/**
 * The Rust stream joiner (blocker/src/remux): stream pieces to one MP4, every
 * frame at its own time, far faster than Android's MediaExtractor/MediaMuxer.
 * TS pieces (H.264 + AAC), or fragmented MP4 pieces of any picture format.
 */
object NativeRemux {
    const val DONE = 0
    const val NOT_MINE = 1
    const val FAILED = 2

    init {
        System.loadLibrary("jellyblock")
    }

    /**
     * Writes [video] (and the separate [audio]) as an MP4 to [fd], which the
     * caller keeps. [fragmented]: the pieces are fragmented MP4, not TS.
     */
    fun join(video: String, audio: String?, fragmented: Boolean, fd: Int): Int =
        nativeJoin(video, audio, fragmented, fd)

    @JvmStatic
    private external fun nativeJoin(video: String, audio: String?, fragmented: Boolean, fd: Int): Int
}
