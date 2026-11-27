/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.lineageos.jelly.R
import org.lineageos.jelly.model.DownloadItem
import org.lineageos.jelly.model.DownloadKind
import org.lineageos.jelly.model.DownloadState

class DownloadsAdapter(
    private val onTap: (DownloadItem) -> Unit,
    private val onAction: (DownloadItem, View) -> Unit,
) : ListAdapter<Row, RecyclerView.ViewHolder>(DIFF) {
    /** A row to flash once, from a notification tap. */
    var highlight = -1L

    override fun getItemViewType(position: Int) = if (getItem(position) is Row.Header) HEADER else ENTRY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == HEADER) {
            object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_download_section, parent, false)) {}
        } else {
            EntryHolder(inflater.inflate(R.layout.item_download, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder.itemView as TextView).setText(
                if (row.active) R.string.downloads_active else R.string.downloads_finished
            )
            is Row.Entry -> (holder as EntryHolder).bind(row.item)
        }
    }

    private inner class EntryHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val kind = view.findViewById<ImageView>(R.id.downloadKind)
        private val name = view.findViewById<TextView>(R.id.downloadName)
        private val meta = view.findViewById<TextView>(R.id.downloadMeta)
        private val bar = view.findViewById<ProgressBar>(R.id.downloadProgress)
        private val action = view.findViewById<ImageButton>(R.id.downloadAction)

        fun bind(i: DownloadItem) {
            kind.setImageResource(
                when (i.kind) {
                    DownloadKind.FILE -> R.drawable.ic_download
                    DownloadKind.STREAM, DownloadKind.YTDLP -> R.drawable.ic_vp_video
                    DownloadKind.TORRENT -> R.drawable.ic_share
                }
            )
            name.text = i.name
            meta.text = DownloadText.meta(i, System.currentTimeMillis())
            val active = DownloadText.isActive(i.state)
            bar.isVisible = active || i.state == DownloadState.SHARING
            bar.isIndeterminate = active && i.totalBytes <= 0 && i.percent < 0
            bar.progress = when {
                i.state == DownloadState.CONVERTING -> i.percent * 10
                i.totalBytes > 0 -> (i.doneBytes * 1000 / i.totalBytes).toInt()
                else -> 0
            }
            action.setImageResource(if (active) R.drawable.ic_close_plain else R.drawable.ic_more_vert)
            action.contentDescription = action.context.getString(
                if (active) R.string.downloads_cancel else R.string.downloads_more
            )
            action.setOnClickListener { onAction(i, it) }
            itemView.setOnClickListener { onTap(i) }
            itemView.isActivated = i.id == highlight
        }
    }

    companion object {
        private const val HEADER = 0
        private const val ENTRY = 1

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = when {
                a is Row.Header && b is Row.Header -> a.active == b.active
                a is Row.Entry && b is Row.Entry -> a.item.id == b.item.id
                else -> false
            }

            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }
    }
}
