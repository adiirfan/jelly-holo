/*
 * SPDX-FileCopyrightText: Jelly Holo contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MenuItem
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

/** Settings → Open-source licences: the page the build makes from every part the app ships. */
class LicensesActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val page = WebView(this).apply {
            // Only for unfolding a licence when its name is tapped; the page is the app's own
            settings.javaScriptEnabled = true
            loadUrl(PAGE)
        }
        findViewById<FrameLayout>(R.id.settings).addView(page)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private companion object {
        const val PAGE = "file:///android_asset/licenses.html"
    }
}
