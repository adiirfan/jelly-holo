/*
 * SPDX-FileCopyrightText: Jelly Holo contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockListsTest {
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun the_first_run_adds_easylist_and_easyprivacy_waiting_to_download() {
        val lists = BlockLists.firstRun(null)
        assertEquals(listOf("EasyList", "EasyPrivacy"), lists.map { it.name })
        assertEquals(
            listOf("https://easylist.to/easylist/easylist.txt", "https://easylist.to/easylist/easyprivacy.txt"),
            lists.map { it.url },
        )
        assertTrue(lists.all { it.on && it.updated == 0L })
        assertEquals(2, lists.map { it.id }.toSet().size)
    }

    @Test
    fun removing_every_list_is_kept_and_does_not_bring_the_defaults_back() {
        assertEquals(emptyList<BlockList>(), BlockLists.firstRun(BlockLists.encode(emptyList())))
    }

    @Test
    fun lists_survive_being_saved_and_read_back() {
        val lists = listOf(
            BlockList("a", "EasyList", "https://easylist.to/easylist/easylist.txt", on = true, updated = 5, expiresMs = 4 * day),
            BlockList("b", "My list", null, on = false, updated = 7, error = "couldn't read it"),
        )
        assertEquals(lists, BlockLists.parse(BlockLists.encode(lists)))
    }

    @Test
    fun a_damaged_saved_value_reads_as_never_saved() {
        assertNull(BlockLists.parse("{not json"))
    }

    @Test
    fun the_name_comes_from_the_lists_own_title() {
        val text = "[Adblock Plus 2.0]\n! Version: 1\n! Title: Fanboy's Annoyance List\n||ads.example^\n"
        assertEquals("Fanboy's Annoyance List", BlockLists.titleOf(text))
        assertNull(BlockLists.titleOf("||ads.example^\n"))
    }

    @Test
    fun how_often_to_refresh_comes_from_the_lists_expires_line() {
        assertEquals(4 * day, BlockLists.expiryOf("! Title: EasyList\n! Expires: 4 days (update frequency)\n"))
        assertEquals(2 * day, BlockLists.expiryOf("! Expires: 48 hours\n"))
        // None given: a week. Silly values are kept between a day and two weeks.
        assertEquals(7 * day, BlockLists.expiryOf("||ads.example^\n"))
        assertEquals(1 * day, BlockLists.expiryOf("! Expires: 1 hours\n"))
        assertEquals(14 * day, BlockLists.expiryOf("! Expires: 90 days\n"))
    }

    @Test
    fun only_lists_that_are_on_from_an_address_and_past_their_refresh_are_due() {
        val now = 100 * day
        val never = BlockList("1", "New", "https://a.example/l.txt")
        val fresh = BlockList("2", "Fresh", "https://b.example/l.txt", updated = now - day, expiresMs = 4 * day)
        val stale = BlockList("3", "Stale", "https://c.example/l.txt", updated = now - 5 * day, expiresMs = 4 * day)
        val off = BlockList("4", "Off", "https://d.example/l.txt", on = false)
        val file = BlockList("5", "File", null, updated = 0)
        assertEquals(listOf("1", "3"), BlockLists.due(listOf(never, fresh, stale, off, file), now).map { it.id })
    }

    @Test
    fun a_failed_download_is_tried_again_after_an_hour_not_every_start() {
        val now = 100 * day
        val failed = BlockList("1", "X", "https://a.example/l.txt", error = "no internet connection", triedAt = now - 10 * 60_000)
        assertTrue(BlockLists.due(listOf(failed), now).isEmpty())
        assertEquals(1, BlockLists.due(listOf(failed.copy(triedAt = now - 2 * 60 * 60_000)), now).size)
    }

    @Test
    fun suggestions_leave_out_lists_already_added() {
        val added = BlockLists.firstRun(null)
        val left = BlockLists.suggestionsLeft(added).map { it.url }
        assertFalse("https://easylist.to/easylist/easylist.txt" in left)
        assertTrue("https://ublockorigin.github.io/uAssets/filters/filters.txt" in left)
    }

    @Test
    fun only_web_addresses_can_be_added_as_lists() {
        assertTrue(BlockLists.isAddress("https://example.com/list.txt"))
        assertTrue(BlockLists.isAddress("  http://example.com/list.txt "))
        assertFalse(BlockLists.isAddress("example.com/list.txt"))
        assertFalse(BlockLists.isAddress("file:///sdcard/list.txt"))
        assertFalse(BlockLists.isAddress("https://"))
    }

    @Test
    fun a_web_page_is_not_a_block_list() {
        assertFalse(BlockLists.looksLikeList("<!DOCTYPE html>\n<html><body>Not found</body></html>"))
        assertFalse(BlockLists.looksLikeList("   <html lang=en>"))
        assertFalse(BlockLists.looksLikeList(""))
        assertTrue(BlockLists.looksLikeList("[Adblock Plus 2.0]\n||ads.example^"))
        assertTrue(BlockLists.looksLikeList("0.0.0.0 ads.example\n"))
    }
}
