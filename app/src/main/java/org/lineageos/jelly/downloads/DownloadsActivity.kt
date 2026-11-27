/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.jelly.R
import org.lineageos.jelly.database.DownloadDatabase
import org.lineageos.jelly.download.DownloadService
import org.lineageos.jelly.model.DownloadItem
import org.lineageos.jelly.model.DownloadState

/** Every download the browser made, newest first, running ones on top. */
class DownloadsActivity : AppCompatActivity(R.layout.activity_downloads) {
    private val dao by lazy { DownloadDatabase.get(this).downloadDao() }
    private val list by lazy { findViewById<RecyclerView>(R.id.downloadsListView) }
    private val empty by lazy { findViewById<View>(R.id.downloadsEmptyLayout) }
    private val adapter = DownloadsAdapter(::open, ::action)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        list.layoutManager = LinearLayoutManager(this)
        list.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL).apply {
                ContextCompat.getDrawable(this@DownloadsActivity, R.drawable.holo_list_divider)?.let { setDrawable(it) }
            }
        )
        list.adapter = adapter
        adapter.highlight = intent.getLongExtra(EXTRA_SHOW_ID, -1)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    dao.all().collect { items ->
                        val rows = sections(items)
                        adapter.submitList(rows) {
                            val at = rows.indexOfFirst { it is Row.Entry && it.item.id == adapter.highlight }
                            if (at >= 0) list.scrollToPosition(at)
                        }
                        empty.isVisible = rows.isEmpty()
                    }
                }
                // Downloads through Android's own manager report there, not here
                while (true) {
                    withContext(Dispatchers.IO) { runCatching { refreshDm() } }
                    delay(1_000)
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

    private suspend fun refreshDm() {
        val rows = dao.activeDm()
        if (rows.isEmpty()) return
        val dm = getSystemService(DownloadManager::class.java)
        for (row in rows) {
            dm.query(DownloadManager.Query().setFilterById(row.dmId)).use { c ->
                if (!c.moveToFirst()) {
                    dao.update(row.copy(state = DownloadState.CANCELLED, updated = System.currentTimeMillis()))
                    return@use
                }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val now = System.currentTimeMillis()
                val next = when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> row.copy(
                        state = DownloadState.DONE, doneBytes = done, totalBytes = done,
                        uri = dm.getUriForDownloadedFile(row.dmId)?.toString(), updated = now,
                    )
                    DownloadManager.STATUS_FAILED -> row.copy(state = DownloadState.FAILED, updated = now)
                    DownloadManager.STATUS_PAUSED -> row.copy(state = DownloadState.PAUSED, doneBytes = done, totalBytes = total, updated = now)
                    else -> row.copy(state = DownloadState.RUNNING, doneBytes = done, totalBytes = total, updated = now)
                }
                if (next != row.copy(updated = now)) dao.update(next)
            }
        }
    }

    /** Tapping a finished download opens it. */
    private fun open(i: DownloadItem) {
        val uri = i.uri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), i.mime ?: contentResolver.getType(Uri.parse(uri)))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(list, R.string.downloads_no_app, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun action(i: DownloadItem, anchor: View) {
        if (DownloadText.isActive(i.state)) {
            cancel(i)
            return
        }
        PopupMenu(this, anchor).apply {
            if (i.uri != null) {
                menu.add(0, OPEN, 0, R.string.downloads_open)
                menu.add(0, SHARE, 1, R.string.downloads_share)
                menu.add(0, DELETE, 2, R.string.downloads_delete_file)
            }
            menu.add(0, REMOVE, 3, R.string.downloads_remove)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    OPEN -> open(i)
                    SHARE -> startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType(i.mime ?: "*/*")
                                .putExtra(Intent.EXTRA_STREAM, Uri.parse(i.uri))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            null,
                        )
                    )
                    DELETE -> forget(i, deleteFile = true)
                    REMOVE -> forget(i, deleteFile = false)
                }
                true
            }
        }.show()
    }

    private fun cancel(i: DownloadItem) {
        if (i.dmId >= 0) {
            getSystemService(DownloadManager::class.java).remove(i.dmId)
        } else {
            DownloadService.cancelRecord(this, i.id)
        }
    }

    private fun forget(i: DownloadItem, deleteFile: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (deleteFile) {
                runCatching {
                    if (i.dmId >= 0) {
                        getSystemService(DownloadManager::class.java).remove(i.dmId)
                    } else {
                        i.uri?.let { contentResolver.delete(Uri.parse(it), null, null) }
                    }
                }
            }
            dao.delete(i.id)
        }
    }

    companion object {
        /** A download's row to show, from its notification. */
        const val EXTRA_SHOW_ID = "dev.jellyholo.downloads.show"
        private const val OPEN = 1
        private const val SHARE = 2
        private const val DELETE = 3
        private const val REMOVE = 4
    }
}
