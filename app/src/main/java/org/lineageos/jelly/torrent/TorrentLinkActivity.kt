/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.torrent

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.jelly.R

/**
 * Magnet links opened from other apps. Starts the torrent and says so, without
 * opening a browser window over the app the link came from.
 */
class TorrentLinkActivity : ComponentActivity() {
    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) add() else {
            Toast.makeText(this, R.string.torrent_needs_storage, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val uri = intent.dataString
        if (uri == null || !isMagnet(uri)) {
            finish()
            return
        }
        if (TorrentService.canSave(this)) add() else askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun add() {
        val uri = intent.dataString ?: return finish()
        val name = magnetName(uri) ?: getString(R.string.torrent_unnamed)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { TorrentService.addMagnet(applicationContext, uri, name) }
            Toast.makeText(applicationContext, getString(R.string.torrent_added, name), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
