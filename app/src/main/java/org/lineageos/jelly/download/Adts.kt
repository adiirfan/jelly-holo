/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

/**
 * AAC sound in a stream comes wrapped in ADTS frame headers, and some
 * phones' readers hand over several such frames as one sample. An MP4 wants
 * one bare frame per sample, so [Remux] splits them with this.
 */
object Adts {
    /**
     * Where each frame's sound bytes lie in the first [size] bytes of [data],
     * or null when they don't start with an ADTS header or a frame is cut off
     * (then the sample is written as it is).
     */
    fun payloads(data: ByteArray, size: Int): List<IntRange>? {
        val out = mutableListOf<IntRange>()
        var at = 0
        while (at < size) {
            if (size - at < 7) return null
            val b0 = data[at].toInt() and 0xFF
            val b1 = data[at + 1].toInt() and 0xFF
            if (b0 != 0xFF || b1 and 0xF6 != 0xF0) return null
            val header = if (b1 and 0x01 == 1) 7 else 9
            val length = ((data[at + 3].toInt() and 0x03) shl 11) or
                ((data[at + 4].toInt() and 0xFF) shl 3) or
                ((data[at + 5].toInt() and 0xE0) ushr 5)
            if (length <= header || at + length > size) return null
            out += (at + header) until (at + length)
            at += length
        }
        return out.ifEmpty { null }
    }
}
