/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.tabs

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.lineageos.jelly.R
import org.lineageos.jelly.utils.TabUtils

/**
 * The tab screen, opened from the tab counter: the app's tabs as stacked
 * cards, newest in front. The tabs themselves stay entries in recent apps;
 * this is a second way to reach and close them.
 */
class TabsActivity : AppCompatActivity() {
    private val list by lazy { findViewById<RecyclerView>(R.id.tabsList) }
    private val countText by lazy { findViewById<TextView>(R.id.tabCountText) }
    private val adapter = TabsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tabs)
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter
        list.addItemDecoration(Overlap())
        findViewById<View>(R.id.newTabButton).setOnClickListener {
            TabUtils.openInNewTab(this, null, false)
            finish()
        }
        findViewById<View>(R.id.tabsButton).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** Read the tabs afresh: some may have been closed in recent apps meanwhile. */
    private fun reload() {
        val tabs = tabTasks(this)
        TabSnapshots.prune(this, tabs.map { it.id }.toSet())
        if (tabs.isEmpty()) {
            finish()
            return
        }
        adapter.tabs = tabs
        adapter.notifyDataSetChanged()
        countText.text = if (tabs.size > 99) "∞" else tabs.size.toString()
        list.scrollToPosition(tabs.size - 1)
    }

    private fun open(tab: Tab) {
        if (tab.id != taskId) tab.task.moveToFront()
        finish()
    }

    private fun close(tab: Tab) {
        TabSnapshots.delete(this, tab.id)
        if (tab.id == taskId) {
            // Closing the tab this screen sits on: bring another one forward first
            tabTasks(this).lastOrNull { it.id != taskId }?.task?.moveToFront()
            tab.task.finishAndRemoveTask()
            return
        }
        tab.task.finishAndRemoveTask()
        reload()
    }

    /** Each card overlaps the one before it so only its title and a strip of page show. */
    private inner class Overlap : RecyclerView.ItemDecoration() {
        private val visible = resources.getDimensionPixelSize(R.dimen.tab_card_peek)
        private val height = resources.getDimensionPixelSize(R.dimen.tab_card_height)

        override fun getItemOffsets(
            outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
        ) {
            if (parent.getChildAdapterPosition(view) > 0) outRect.top = visible - height
        }
    }

    private inner class TabsAdapter : RecyclerView.Adapter<TabsAdapter.Holder>() {
        var tabs: List<Tab> = emptyList()

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.tabIcon)
            val letter: TextView = view.findViewById(R.id.tabLetter)
            val title: TextView = view.findViewById(R.id.tabTitle)
            val close: ImageButton = view.findViewById(R.id.tabClose)
            val shot: ImageView = view.findViewById(R.id.tabShot)
            val strip: View = view.findViewById(R.id.tabStrip)
            val titleColor = title.currentTextColor
            val closeTint = close.imageTintList
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_tab_card, parent, false)
        )

        override fun getItemCount() = tabs.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val tab = tabs[position]
            val ctx = holder.itemView.context
            val title = tab.title?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.tab_untitled)
            // Private tabs: purple strip and a "Private ·" label, never mistaken for normal ones
            holder.title.text = if (tab.private) "${ctx.getString(R.string.tab_private_label)} · $title" else title
            holder.strip.setBackgroundColor(
                ctx.getColor(if (tab.private) R.color.holo_private_bar else R.color.holo_tab_title)
            )
            holder.title.setTextColor(if (tab.private) android.graphics.Color.WHITE else holder.titleColor)
            holder.close.imageTintList = if (tab.private) {
                android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            } else {
                holder.closeTint
            }
            val icon = TabSnapshots.loadIcon(ctx, tab.id)
            holder.icon.setImageBitmap(icon)
            holder.icon.visibility = if (icon != null) View.VISIBLE else View.GONE
            holder.letter.visibility = if (icon != null) View.GONE else View.VISIBLE
            holder.letter.text = title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
            holder.shot.setImageBitmap(TabSnapshots.load(ctx, tab.id))
            holder.itemView.setOnClickListener { open(tab) }
            holder.close.setOnClickListener { close(tab) }
            holder.close.contentDescription = ctx.getString(R.string.tab_close, title)
            // Drawn in order, so the newest (last) card sits on top
            holder.itemView.translationZ = position.toFloat()
        }
    }
}
