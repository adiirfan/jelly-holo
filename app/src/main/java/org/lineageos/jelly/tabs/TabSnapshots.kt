/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.tabs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Small screenshots of each tab's page for the tab screen, one per tab,
 * kept in memory and in the cache folder (cache/tabs/<task id>.jpg).
 * Site icons are kept the same way (<task id>_icon.png).
 */
object TabSnapshots {
    private const val SCALE = 1f / 3f
    private const val QUALITY = 80

    private val shots = ConcurrentHashMap<Int, Bitmap>()
    private val icons = ConcurrentHashMap<Int, Bitmap>()
    private val writer = Executors.newSingleThreadExecutor()

    private fun dir(context: Context) = File(context.cacheDir, "tabs").apply { mkdirs() }
    private fun shotFile(context: Context, taskId: Int) = File(dir(context), "$taskId.jpg")
    private fun iconFile(context: Context, taskId: Int) = File(dir(context), "${taskId}_icon.png")

    /** Draw `view` at a third of its size and keep it as the tab's picture. */
    fun save(context: Context, taskId: Int, view: View) {
        if (view.width == 0 || view.height == 0) return
        val bitmap = runCatching {
            Bitmap.createBitmap(
                (view.width * SCALE).toInt().coerceAtLeast(1),
                (view.height * SCALE).toInt().coerceAtLeast(1),
                Bitmap.Config.RGB_565
            ).also { b ->
                Canvas(b).apply {
                    scale(SCALE, SCALE)
                    translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
                    view.draw(this)
                }
            }
        }.getOrNull() ?: return
        shots[taskId] = bitmap
        val file = shotFile(context.applicationContext, taskId)
        writer.execute {
            runCatching {
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            }
        }
    }

    fun saveIcon(context: Context, taskId: Int, icon: Bitmap) {
        val copy = icon.copy(Bitmap.Config.ARGB_8888, false) ?: return
        icons[taskId] = copy
        val file = iconFile(context.applicationContext, taskId)
        writer.execute {
            runCatching { file.outputStream().use { copy.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }

    fun load(context: Context, taskId: Int): Bitmap? =
        shots[taskId] ?: shotFile(context, taskId).takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.path) }?.also { shots[taskId] = it }

    fun loadIcon(context: Context, taskId: Int): Bitmap? =
        icons[taskId] ?: iconFile(context, taskId).takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.path) }?.also { icons[taskId] = it }

    fun delete(context: Context, taskId: Int) {
        shots.remove(taskId)
        icons.remove(taskId)
        writer.execute {
            shotFile(context, taskId).delete()
            iconFile(context, taskId).delete()
        }
    }

    /** Forget tabs that no longer exist (closed here, or swiped away in recent apps). */
    fun prune(context: Context, liveTaskIds: Set<Int>) {
        shots.keys.filter { it !in liveTaskIds }.forEach { shots.remove(it) }
        icons.keys.filter { it !in liveTaskIds }.forEach { icons.remove(it) }
        val folder = dir(context)
        writer.execute {
            folder.listFiles()?.forEach { f ->
                val id = f.name.substringBefore('.').substringBefore('_').toIntOrNull()
                if (id != null && id !in liveTaskIds) f.delete()
            }
        }
    }
}
