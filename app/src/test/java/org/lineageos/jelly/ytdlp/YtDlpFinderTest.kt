/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpFinderTest {
    private val ok = RunResult(0, """{"title":"t","formats":[]}""", "", false, false)
    private val broken = RunResult(1, "", "ERROR: [youtube] abc: Some new site change", false, false)
    private val signIn = RunResult(1, "", "ERROR: Sign in to confirm you're not a bot", false, false)

    private fun finder(runs: MutableList<RunResult>, updates: MutableList<Boolean>, outcome: Outcome) = YtDlpFinder(
        prepare = { true },
        update = { force -> updates += force; UpdateResult(outcome, if (outcome == Outcome.UPDATED) "2099.01.01" else null) },
        run = { _, _ -> runs.removeAt(0) },
        log = {},
    )

    @Test
    fun an_unknown_failure_forces_an_update_and_tries_once_more() {
        val runs = mutableListOf(broken, ok)
        val updates = mutableListOf<Boolean>()
        val found = finder(runs, updates, Outcome.UPDATED).find("https://example.invalid/v", null) { false }
        assertTrue(found is YtDlpFinder.Found.Ok)
        assertEquals(listOf(false, true), updates)
    }

    @Test
    fun no_retry_when_the_update_finds_nothing_newer() {
        val runs = mutableListOf(broken, ok)
        val updates = mutableListOf<Boolean>()
        val found = finder(runs, updates, Outcome.UP_TO_DATE).find("https://example.invalid/v", null) { false }
        assertTrue(found is YtDlpFinder.Found.Failed)
        assertEquals(1, runs.size)
    }

    @Test
    fun a_known_problem_doesnt_force_an_update() {
        val runs = mutableListOf(signIn)
        val updates = mutableListOf<Boolean>()
        val found = finder(runs, updates, Outcome.UPDATED).find("https://example.invalid/v", null) { false }
        assertEquals(Problem.SIGN_IN, (found as YtDlpFinder.Found.Failed).problem)
        assertEquals(listOf(false), updates)
    }
}
