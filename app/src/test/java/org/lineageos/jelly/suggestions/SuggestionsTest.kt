/*
 * SPDX-FileCopyrightText: Jelly Holo contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.suggestions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuggestionsTest {
    private val a = SuggestItem("apple")
    private val b = SuggestItem("apricot")

    @Test
    fun looking_up_leaves_the_shown_list_alone_until_it_is_shown() {
        val list = Suggestions()
        list.show(list.lookUp("ap") { listOf(a, b) })

        // The look-up runs in the background while the list may be drawing the old items
        val found = list.lookUp("apx") { emptyList() }
        assertEquals(listOf(a, b), list.items)
        assertEquals("ap", list.query)

        list.show(found)
        assertEquals(emptyList<SuggestItem>(), list.items)
        assertEquals("apx", list.query)
    }

    @Test
    fun a_blank_address_shows_nothing_and_asks_nobody() {
        val list = Suggestions()
        list.show(list.lookUp("ap") { listOf(a) })
        var asked = false
        list.show(list.lookUp("   ") { asked = true; listOf(b) })
        assertEquals(false, asked)
        assertEquals(emptyList<SuggestItem>(), list.items)
        assertNull(list.query)
    }

    @Test
    fun the_typed_text_is_looked_up_in_small_letters_without_spaces_around() {
        var asked: String? = null
        Suggestions().lookUp("  ApPle ") { asked = it; emptyList() }
        assertEquals("apple", asked)
    }
}
