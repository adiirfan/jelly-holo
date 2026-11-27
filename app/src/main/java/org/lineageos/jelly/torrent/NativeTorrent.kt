/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.torrent

import android.content.Context

/**
 * The Rust torrent engine (torrent/, on librqbit). One engine per process;
 * every call blocks, so call from a worker thread. Results that carry data
 * are JSON: an added torrent is {"id","info_hash","name"}, a failure is
 * {"error": "…"}, and [stats] is a list of torrents.
 */
object NativeTorrent {
    init {
        System.loadLibrary("jellytorrent")
    }

    /** Why the last [start] failed, or null. */
    @Volatile
    var lastError: String? = null
        private set

    /** Lets the engine check HTTPS certificates; call once before [start]. */
    fun init(context: Context): Boolean = nativeInit(context.applicationContext)

    /**
     * Starts the engine, stopping any running one. [port] 0 picks any free
     * one; limits are bytes a second, 0 for none.
     */
    fun start(folder: String, stateDir: String, port: Int, down: Int, up: Int): Boolean {
        lastError = nativeStart(folder, stateDir, port, down, up)
        return lastError == null
    }

    /** Waits for the torrent's details from other people, which can take a while. */
    fun addMagnet(uri: String): String = nativeAddMagnet(uri)
    fun addBytes(bytes: ByteArray): String = nativeAddBytes(bytes)
    fun addUrl(url: String): String = nativeAddUrl(url)

    fun pause(id: Int): Boolean = nativePause(id)
    fun resume(id: Int): Boolean = nativeResume(id)
    fun remove(id: Int, deleteFiles: Boolean): Boolean = nativeRemove(id, deleteFiles)

    /** Waits up to 2 minutes for a new torrent's check of files already on disk. */
    fun selectFiles(id: Int, files: IntArray): Boolean = nativeSelectFiles(id, files)
    fun setLimits(down: Int, up: Int) = nativeSetLimits(down, up)

    /** The port other people can reach the engine on, or 0. */
    fun listenPort(): Int = nativeListenPort()
    fun stats(): String = nativeStats()
    fun stop() = nativeStop()

    @JvmStatic private external fun nativeInit(context: Context): Boolean
    @JvmStatic private external fun nativeStart(folder: String, stateDir: String, port: Int, down: Int, up: Int): String?
    @JvmStatic private external fun nativeAddMagnet(uri: String): String
    @JvmStatic private external fun nativeAddBytes(bytes: ByteArray): String
    @JvmStatic private external fun nativeAddUrl(url: String): String
    @JvmStatic private external fun nativePause(id: Int): Boolean
    @JvmStatic private external fun nativeResume(id: Int): Boolean
    @JvmStatic private external fun nativeRemove(id: Int, deleteFiles: Boolean): Boolean
    @JvmStatic private external fun nativeSelectFiles(id: Int, files: IntArray): Boolean
    @JvmStatic private external fun nativeSetLimits(down: Int, up: Int)
    @JvmStatic private external fun nativeListenPort(): Int
    @JvmStatic private external fun nativeStats(): String
    @JvmStatic private external fun nativeStop()
}
