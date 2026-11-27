/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SoundFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun the_picked_sound_wins() {
        val picked = tmp.newFile("audio")
        val video = tmp.newFile("video")
        tmp.newFile("video-sound").writeText("x")
        assertEquals(picked, soundFile(picked, video))
    }

    @Test
    fun a_playlists_separate_sound_is_used_when_none_was_picked() {
        val video = tmp.newFile("video")
        val extra = tmp.newFile("video-sound").apply { writeText("x") }
        assertEquals(extra, soundFile(null, video))
    }

    @Test
    fun no_sound_when_there_is_none() {
        val video = tmp.newFile("video")
        assertNull(soundFile(null, video))
        tmp.newFile("video-sound")
        assertNull("an empty file is no sound", soundFile(null, video))
        assertNull(soundFile(null, null))
    }
}
