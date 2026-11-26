/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ui

import android.content.Context
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.isVisible
import org.lineageos.jelly.R

/** The early-Chrome menu: forward, bookmark and reload on top, a plain list below. */
class MenuDialog(
    context: Context,
    desktopMode: Boolean,
    private val onClickListener: (option: Option) -> Unit
) {
    private val layoutInflater = LayoutInflater.from(context)

    private val view = layoutInflater.inflate(R.layout.menu_dialog, FrameLayout(context), false)

    private val forwardButton by lazy { view.findViewById<ImageButton>(R.id.forwardButton) }
    private val refreshButton by lazy { view.findViewById<ImageButton>(R.id.refreshButton) }
    private val addToFavoriteButton by lazy { view.findViewById<ImageButton>(R.id.addToFavoriteButton) }

    private val row = { id: Int -> view.findViewById<View>(id) }
    private val desktopViewCheckBox by lazy { view.findViewById<CheckBox>(R.id.desktopViewCheckBox) }
    private val darkPagesButton by lazy { view.findViewById<View>(R.id.darkPagesButton) }
    private val darkPagesCheckBox by lazy { view.findViewById<CheckBox>(R.id.darkPagesCheckBox) }
    private val adsBlockedRow by lazy { view.findViewById<TextView>(R.id.adsBlockedRow) }

    private val popupWindow = PopupWindow(
        view,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        true
    ).apply {
        elevation = context.resources.getDimension(R.dimen.toolbar_elevation)
    }

    var isDesktopMode = false
        set(value) {
            field = value
            desktopViewCheckBox.isChecked = value
        }

    /** Dark pages only works while the app is dark, so it is hidden otherwise. */
    var darkPagesAvailable = true
        set(value) {
            field = value
            darkPagesButton.isVisible = value
            (darkPagesButton.tag as? View)?.isVisible = value
        }

    var isDarkPages = false
        set(value) {
            field = value
            darkPagesCheckBox.isChecked = value
        }

    var isFavorite = false
        set(value) {
            field = value
            addToFavoriteButton.setImageResource(
                if (value) R.drawable.ic_star_filled else R.drawable.ic_star
            )
            addToFavoriteButton.imageTintList = view.context.getColorStateList(
                if (value) R.color.holo_blue else R.color.holo_icon
            )
        }

    /** Requests blocked on the page showing; the row is hidden when blocking is off. */
    fun setBlockedCount(count: Int?) {
        adsBlockedRow.isVisible = count != null
        (adsBlockedRow.tag as? View)?.isVisible = count != null
        count?.let {
            adsBlockedRow.text = view.context.getString(R.string.adblock_blocked_count, it)
        }
    }

    enum class Option {
        FORWARD,
        REFRESH,
        ADD_TO_FAVORITE,
        SHARE,
        NEW_TAB,
        NEW_PRIVATE_TAB,
        FAVORITES,
        HISTORY,
        DOWNLOADS,
        ADD_TO_HOME_SCREEN,
        FIND_IN_PAGE,
        DESKTOP_VIEW,
        DARK_PAGES,
        PRINT,
        DESKTOP_SHORTCUTS,
        BACKGROUND_SHORTCUTS,
        SETTINGS,
    }

    init {
        forwardButton.setOnClickListener { triggerOption(Option.FORWARD) }
        refreshButton.setOnClickListener { triggerOption(Option.REFRESH) }
        addToFavoriteButton.setOnClickListener { triggerOption(Option.ADD_TO_FAVORITE) }

        mapOf(
            R.id.shareButton to Option.SHARE,
            R.id.newTabButton to Option.NEW_TAB,
            R.id.newPrivateTabButton to Option.NEW_PRIVATE_TAB,
            R.id.favoritesButton to Option.FAVORITES,
            R.id.historyButton to Option.HISTORY,
            R.id.downloadsButton to Option.DOWNLOADS,
            R.id.addToHomeScreenButton to Option.ADD_TO_HOME_SCREEN,
            R.id.findInPageButton to Option.FIND_IN_PAGE,
            R.id.desktopViewButton to Option.DESKTOP_VIEW,
            R.id.darkPagesButton to Option.DARK_PAGES,
            R.id.printButton to Option.PRINT,
            R.id.desktopShortcutsButton to Option.DESKTOP_SHORTCUTS,
            R.id.backgroundShortcutsButton to Option.BACKGROUND_SHORTCUTS,
            R.id.settingsButton to Option.SETTINGS,
        ).forEach { (id, option) -> row(id).setOnClickListener { triggerOption(option) } }

        desktopViewCheckBox.isChecked = desktopMode

        // Each hideable row takes its divider (the view just above it) with it
        listOf(darkPagesButton, adsBlockedRow).forEach { v ->
            val parent = v.parent as ViewGroup
            v.tag = parent.getChildAt(parent.indexOfChild(v) - 1)
        }
    }

    fun showAsDropdownMenu(anchor: View, isReachMode: Boolean = false) {
        view.scrollTo(0, 0)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        // Fit the screen: the list scrolls when it is taller than the room left
        val frame = Rect().also { anchor.getWindowVisibleDisplayFrame(it) }
        val location = IntArray(2).also { anchor.getLocationInWindow(it) }
        val room = if (isReachMode) {
            location[1] - frame.top
        } else {
            frame.bottom - (location[1] + anchor.height)
        }
        val height = minOf(view.measuredHeight, room)
        popupWindow.height = height
        val xOffset = anchor.width - view.measuredWidth
        val yOffset = if (isReachMode) -(anchor.height + height) else 0
        popupWindow.showAsDropDown(anchor, xOffset, yOffset)
        dimBehind()
    }

    /** Darken the page behind the menu, as Holo menus did. */
    private fun dimBehind() {
        val root = popupWindow.contentView.rootView
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        params.dimAmount = 0.5f
        view.context.getSystemService(WindowManager::class.java).updateViewLayout(root, params)
    }

    fun dismiss() {
        popupWindow.dismiss()
    }

    private fun triggerOption(option: Option) {
        onClickListener(option)
    }
}
