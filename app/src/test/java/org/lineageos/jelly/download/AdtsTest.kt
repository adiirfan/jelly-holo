/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdtsTest {
    /** One ADTS frame around [payload]; with [crc], the 2-byte check follows the header. */
    private fun frame(payload: ByteArray, crc: Boolean = false): ByteArray {
        val header = if (crc) 9 else 7
        val len = header + payload.size
        val h = byteArrayOf(
            0xFF.toByte(),
            (if (crc) 0xF0 else 0xF1).toByte(),
            0x50, // AAC LC, 44.1 kHz
            (0x40 or ((len shr 11) and 0x03)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 0x07) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
        return h + (if (crc) byteArrayOf(0x12, 0x34) else byteArrayOf()) + payload
    }

    @Test
    fun clumpOfFramesSplitsIntoBarePayloads() {
        val a = ByteArray(100) { 1 }
        val b = ByteArray(250) { 2 }
        val c = ByteArray(3) { 3 }
        val data = frame(a) + frame(b) + frame(c)
        assertEquals(listOf(7 until 107, 114 until 364, 371 until 374), Adts.payloads(data, data.size))
    }

    @Test
    fun checkBytesAreSkipped() {
        val data = frame(ByteArray(40), crc = true)
        assertEquals(listOf(9 until 49), Adts.payloads(data, data.size))
    }

    @Test
    fun bareSoundIsLeftAlone() {
        val data = byteArrayOf(0x21, 0x10, 0x05, 0x00, 0x11)
        assertNull(Adts.payloads(data, data.size))
    }

    @Test
    fun aCutOffFrameMeansLeaveItAlone() {
        val data = frame(ByteArray(100)) + frame(ByteArray(100)).copyOf(50)
        assertNull(Adts.payloads(data, data.size))
    }

    @Test
    fun onlyTheFilledPartOfTheBufferCounts() {
        val one = frame(ByteArray(20))
        val data = one + ByteArray(64)
        assertEquals(listOf(7 until 27), Adts.payloads(data, one.size))
    }
}
