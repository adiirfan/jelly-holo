/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

/**
 * The Rust stream joiner (blocker/src/remux): TS pieces to one MP4, every
 * frame at its own time, far faster than Android's MediaExtractor/MediaMuxer.
 */
object NativeRemux {
    const val DONE = 0
    const val NOT_MINE = 1
    const val FAILED = 2

    init {
        System.loadLibrary("jellyblock")
    }

    /** Writes [video] (and the separate [audio]) as an MP4 to [fd], which the caller keeps. */
    fun tsToMp4(video: String, audio: String?, fd: Int): Int = nativeTsToMp4(video, audio, fd)

    @JvmStatic
    private external fun nativeTsToMp4(video: String, audio: String?, fd: Int): Int
}
