/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TsJoinTest {
    /** A 188-byte packet for [pid] with counter [cc]; payload unless [adaptationOnly]. */
    private fun packet(pid: Int, cc: Int, adaptationOnly: Boolean = false): ByteArray =
        ByteArray(188).also {
            it[0] = 0x47
            it[1] = ((pid shr 8) and 0x1F).toByte()
            it[2] = (pid and 0xFF).toByte()
            it[3] = ((if (adaptationOnly) 0x20 else 0x10) or cc).toByte()
            it[4] = 0x55
        }

    private fun counters(data: ByteArray, pid: Int) = (data.indices step 188)
        .filter { (((data[it + 1].toInt() and 0x1F) shl 8) or (data[it + 2].toInt() and 0xFF)) == pid }
        .map { data[it + 3].toInt() and 0x0F }

    @Test
    fun countersCarryOnAcrossPieces() {
        val join = TsJoin()
        val a = packet(258, 0) + packet(258, 1) + packet(256, 0)
        val b = packet(258, 0) + packet(258, 1) + packet(256, 0)
        join.fix(a, a.size)
        join.fix(b, b.size)
        assertEquals(listOf(0, 1), counters(a, 258))
        assertEquals(listOf(2, 3), counters(b, 258))
        assertEquals(listOf(1), counters(b, 256))
    }

    @Test
    fun countersWrapAtSixteen() {
        val join = TsJoin()
        val a = (0 until 15).map { packet(258, it) }.reduce { x, y -> x + y }
        val b = packet(258, 0) + packet(258, 1)
        join.fix(a, a.size)
        join.fix(b, b.size)
        assertEquals(listOf(15, 0), counters(b, 258))
    }

    @Test
    fun packetsWithoutPayloadKeepTheLastCount() {
        val join = TsJoin()
        val a = packet(258, 0) + packet(258, 0, adaptationOnly = true) + packet(258, 1)
        val b = packet(258, 0) + packet(258, 0, adaptationOnly = true)
        join.fix(a, a.size)
        join.fix(b, b.size)
        assertEquals(listOf(0, 0, 1), counters(a, 258))
        assertEquals(listOf(2, 2), counters(b, 258))
    }

    @Test
    fun onlyTheCounterChanges() {
        val join = TsJoin()
        val first = packet(258, 0)
        val b = packet(258, 0)
        join.fix(first, first.size)
        join.fix(b, b.size)
        val expected = packet(258, 1)
        assertArrayEquals(expected, b)
    }

    @Test
    fun anythingElseIsLeftAlone() {
        val join = TsJoin()
        val notTs = ByteArray(376) { 7 }
        val odd = packet(258, 0).copyOf(200)
        join.fix(notTs, notTs.size)
        join.fix(odd, odd.size)
        assertArrayEquals(ByteArray(376) { 7 }, notTs)
        assertArrayEquals(packet(258, 0).copyOf(200), odd)
    }
}
