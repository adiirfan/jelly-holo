/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.tabs

import org.lineageos.jelly.utils.IntentUtils
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import org.lineageos.jelly.MainActivity

/** One open tab: an entry of this app in Android's recent apps. */
class Tab(val task: ActivityManager.AppTask, val id: Int, val title: String?, val private: Boolean = false)

/** This app's tabs, oldest first (the newest is drawn in front). */
fun tabTasks(context: Context): List<Tab> {
    val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
    val main = MainActivity::class.java.name
    return am.appTasks.mapNotNull { task ->
        val info = runCatching { task.taskInfo }.getOrNull() ?: return@mapNotNull null
        if (info.baseActivity?.className != main && info.baseIntent.component?.className != main) {
            return@mapNotNull null
        }
        @Suppress("DEPRECATION")
        val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else info.persistentId
        @Suppress("DEPRECATION")
        val label = info.taskDescription?.label?.toString()
        val private = runCatching { info.baseIntent.getBooleanExtra(IntentUtils.EXTRA_INCOGNITO, false) }.getOrDefault(false)
        Tab(task, id, label, private)
    }.asReversed()
}
