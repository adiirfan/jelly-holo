/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.lineageos.jelly.R
import org.lineageos.jelly.database.DownloadDatabase
import org.lineageos.jelly.model.DownloadItem
import org.lineageos.jelly.model.DownloadState
import org.lineageos.jelly.torrent.TorrentFile
import org.lineageos.jelly.torrent.TorrentService
import org.lineageos.jelly.torrent.isMagnet
import org.lineageos.jelly.torrent.parseFiles
import java.net.URLEncoder

/** One torrent: progress, pause, stop sharing, remove, which files to get, and where it came from. */
class TorrentDetailActivity : AppCompatActivity(R.layout.activity_torrent_detail) {
    private val rowId by lazy { intent.getLongExtra(EXTRA_ID, -1) }
    private val root by lazy { findViewById<View>(R.id.torrentDetailRoot) }
    private val state by lazy { findViewById<TextView>(R.id.torrentState) }
    private val progress by lazy { findViewById<ProgressBar>(R.id.torrentProgress) }
    private val speeds by lazy { findViewById<TextView>(R.id.torrentSpeeds) }
    private val pause by lazy { findViewById<Button>(R.id.torrentPause) }
    private val stopSharing by lazy { findViewById<Button>(R.id.torrentStopSharing) }
    private val remove by lazy { findViewById<Button>(R.id.torrentRemove) }
    private val noFiles by lazy { findViewById<View>(R.id.torrentNoFiles) }
    private val info by lazy { findViewById<LinearLayout>(R.id.torrentInfo) }
    private val files = FilesAdapter()
    private var item: DownloadItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<RecyclerView>(R.id.torrentFiles).apply {
            layoutManager = LinearLayoutManager(this@TorrentDetailActivity)
            adapter = files
        }
        pause.setOnClickListener {
            val i = item ?: return@setOnClickListener
            if (i.state == DownloadState.PAUSED) TorrentService.resume(this, i.id) else TorrentService.pause(this, i.id)
        }
        stopSharing.setOnClickListener { item?.let { TorrentService.pause(this, it.id) } }
        remove.setOnClickListener { item?.let { askRemove(this, it) { finish() } } }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadDatabase.get(this@TorrentDetailActivity).downloadDao().watch(rowId).collect { row ->
                    if (row == null) finish() else show(row)
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun show(i: DownloadItem) {
        val first = item == null
        item = i
        title = i.name
        val now = System.currentTimeMillis()
        val parts = DownloadText.meta(i, now).split(" · ")
        // The page's one line, split: what it is doing above the bar, speeds below
        val speedParts = parts.filter { it.startsWith("↓") || it.startsWith("↑") || it.endsWith("people") || it.startsWith("shared") }
        state.text = parts.filterNot { it in speedParts }.joinToString(" · ")
        speeds.text = speedParts.joinToString(" · ")
        speeds.isVisible = speedParts.isNotEmpty()
        progress.progress = if (i.totalBytes > 0) (i.doneBytes * 1000 / i.totalBytes).toInt() else 0
        pause.setText(if (i.state == DownloadState.PAUSED) R.string.torrent_resume else R.string.torrent_pause)
        pause.isVisible = i.state != DownloadState.DONE && i.state != DownloadState.SHARING && i.state != DownloadState.FAILED
        stopSharing.isVisible = i.state == DownloadState.SHARING
        val list = parseFiles(i.files)
        files.submit(list)
        noFiles.isVisible = list.isEmpty()
        if (first) showInfo(i)
    }

    private fun showInfo(i: DownloadItem) {
        info.removeAllViews()
        fun row(label: Int, value: String, onTap: (() -> Unit)? = null) {
            val v = layoutInflater.inflate(R.layout.item_torrent_info, info, false)
            v.findViewById<TextView>(R.id.infoLabel).setText(label)
            v.findViewById<TextView>(R.id.infoValue).apply {
                text = value
                if (onTap != null) {
                    setTextColor(getColor(R.color.holo_blue))
                    setOnClickListener { onTap() }
                }
            }
            info.addView(v)
        }
        row(R.string.torrent_saved_in, getString(R.string.torrent_saved_in_value))
        row(
            R.string.torrent_added_at,
            DateUtils.getRelativeDateTimeString(this, i.created, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0)
                .toString(),
        )
        row(
            R.string.torrent_from,
            getString(if (i.source?.let(::isMagnet) == true) R.string.torrent_from_magnet else R.string.torrent_from_file),
        )
        magnetOf(i)?.let { magnet ->
            row(R.string.torrent_magnet, getString(R.string.torrent_copy)) {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(getString(R.string.torrent_magnet), magnet))
                Snackbar.make(root, R.string.torrent_copied, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    /** Ticks a file in or out; the engine gets the whole new choice. */
    private fun toggle(index: Int) {
        val i = item ?: return
        val list = parseFiles(i.files)
        val chosen = list.indices.filter { if (it == index) !list[it].selected else list[it].selected }
        if (chosen.isEmpty()) {
            Snackbar.make(root, R.string.torrent_keep_one, Snackbar.LENGTH_SHORT).show()
            return
        }
        TorrentService.selectFiles(this, i.id, chosen.toIntArray())
        // Shown at once; the engine's answer follows within a second
        files.submit(list.mapIndexed { n, f -> if (n == index) f.copy(selected = !f.selected) else f })
    }

    private inner class FilesAdapter : RecyclerView.Adapter<FilesAdapter.Holder>() {
        private var list: List<TorrentFile> = emptyList()

        fun submit(next: List<TorrentFile>) {
            if (next == list) return
            val sameShape = next.size == list.size
            list = next
            if (sameShape) notifyItemRangeChanged(0, next.size) else notifyDataSetChanged()
        }

        override fun getItemCount() = list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_torrent_file, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(list[position], position)

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val check = view.findViewById<CheckBox>(R.id.torrentFileCheck)
            private val name = view.findViewById<TextView>(R.id.torrentFileName)
            private val meta = view.findViewById<TextView>(R.id.torrentFileMeta)

            fun bind(f: TorrentFile, index: Int) {
                check.isChecked = f.selected
                name.text = f.name.substringAfterLast('/')
                name.alpha = if (f.selected) 1f else 0.5f
                val size = DownloadText.size(f.size)
                meta.text = when {
                    !f.selected -> getString(R.string.torrent_file_skipped, size)
                    f.size > 0 && f.done >= f.size -> getString(R.string.torrent_file_done, size)
                    else -> getString(R.string.torrent_file_progress, size, if (f.size > 0) (f.done * 100 / f.size).toInt() else 0)
                }
                itemView.setOnClickListener { toggle(index) }
            }
        }
    }

    companion object {
        const val EXTRA_ID = "dev.jellyholo.torrent.id"

        /** A link to give others: the one it came from, or one made from its hash. */
        fun magnetOf(i: DownloadItem): String? = when {
            i.source != null && isMagnet(i.source) -> i.source
            i.infoHash != null -> "magnet:?xt=urn:btih:${i.infoHash}&dn=" + URLEncoder.encode(i.name, "UTF-8")
            else -> null
        }

        /** "Remove <name>?" with Keep files / Delete files; [after] runs once it is asked for. */
        fun askRemove(context: Context, i: DownloadItem, after: () -> Unit = {}) {
            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.torrent_remove_title, i.name))
                .setMessage(R.string.torrent_remove_message)
                .setPositiveButton(R.string.torrent_keep_files) { _, _ ->
                    TorrentService.remove(context, i.id, deleteFiles = false)
                    after()
                }
                .setNeutralButton(R.string.torrent_delete_files) { _, _ ->
                    TorrentService.remove(context, i.id, deleteFiles = true)
                    after()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
