/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

/**
 * Joins MPEG-TS stream pieces into one stream the phone reads without loss.
 *
 * Each TS packet carries a 4-bit counter per stream. Many servers start
 * every piece's counters again at 0, and Android's reader takes the jump at
 * a join as damage and throws away the sound and picture it was holding
 * (about a third of a second each time). Numbering the packets on from the
 * previous piece makes the joins invisible.
 */
class TsJoin {
    private val last = IntArray(8192) { -1 }

    /** Renumbers the first [size] bytes of [data] in place; leaves anything not TS alone. */
    fun fix(data: ByteArray, size: Int) {
        if (size == 0 || size % PACKET != 0) return
        for (at in 0 until size step PACKET) if (data[at] != SYNC) return
        for (at in 0 until size step PACKET) {
            val pid = ((data[at + 1].toInt() and 0x1F) shl 8) or (data[at + 2].toInt() and 0xFF)
            val b3 = data[at + 3].toInt() and 0xFF
            val payload = (b3 shr 4) and 0x01 == 1
            val prev = last[pid]
            val cc = when {
                prev < 0 -> b3 and 0x0F
                payload -> (prev + 1) and 0x0F
                else -> prev
            }
            last[pid] = cc
            data[at + 3] = ((b3 and 0xF0) or cc).toByte()
        }
    }

    private companion object {
        const val PACKET = 188
        const val SYNC: Byte = 0x47
    }
}
