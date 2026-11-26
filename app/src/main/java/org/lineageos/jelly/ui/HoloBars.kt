/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ui

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Black status and navigation bars, as on Android 4, in both looks.
 * Android 15 draws apps under the bars and ignores bar colours, so each
 * screen gets two black strips the height of the bars, kept in step with
 * them (they shrink to nothing when the bars hide, e.g. for full-screen video).
 */
object HoloBars {
    private const val TAG_TOP = "holo_status_scrim"
    private const val TAG_BOTTOM = "holo_nav_scrim"

    fun apply(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(TAG_TOP) != null) return
        WindowCompat.getInsetsController(activity.window, decor).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val top = scrim(activity, TAG_TOP, Gravity.TOP)
        val bottom = scrim(activity, TAG_BOTTOM, Gravity.BOTTOM)
        decor.addView(top)
        decor.addView(bottom)
        ViewCompat.setOnApplyWindowInsetsListener(top) { v, insets ->
            v.layoutParams.height = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.requestLayout()
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottom) { v, insets ->
            v.layoutParams.height =
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.requestLayout()
            insets
        }
        ViewCompat.requestApplyInsets(decor)
    }

    private fun scrim(activity: Activity, tag: String, gravity: Int) = View(activity).apply {
        this.tag = tag
        setBackgroundColor(Color.BLACK)
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, gravity)
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        elevation = 100f
    }
}
