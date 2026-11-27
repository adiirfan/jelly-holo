/*
 * SPDX-FileCopyrightText: Jelly Holo contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.adblock

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import org.lineageos.jelly.R

/** Settings → Block lists: the lists ad blocking uses, and adding the user's own. */
class BlockListsActivity : AppCompatActivity(R.layout.activity_block_lists) {
    private val root by lazy { findViewById<View>(R.id.blockListsRoot) }
    private val empty by lazy { findViewById<View>(R.id.blockListsEmpty) }
    private val adapter = ListsAdapter()

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<RecyclerView>(R.id.blockLists).apply {
            layoutManager = LinearLayoutManager(this@BlockListsActivity)
            adapter = this@BlockListsActivity.adapter
        }
    }

    override fun onStart() {
        super.onStart()
        AdBlock.onListsChanged = ::show
        show()
    }

    override fun onStop() {
        AdBlock.onListsChanged = null
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_block_lists, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        R.id.menu_block_lists_add -> {
            askHowToAdd()
            true
        }
        R.id.menu_block_lists_update -> {
            AdBlock.updateNow(this)
            Snackbar.make(root, R.string.block_lists_updating, Snackbar.LENGTH_SHORT).show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun show() {
        val lists = AdBlock.lists(this)
        adapter.submit(lists)
        empty.isVisible = lists.isEmpty()
    }

    private fun askHowToAdd() {
        val choices = arrayOf(
            getString(R.string.block_lists_add_suggested),
            getString(R.string.block_lists_add_address),
            getString(R.string.block_lists_add_file),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.block_lists_add)
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> askSuggested()
                    1 -> askAddress()
                    else -> pickFile.launch(arrayOf("text/*", "application/octet-stream"))
                }
            }
            .show()
    }

    private fun askSuggested() {
        val left = BlockLists.suggestionsLeft(AdBlock.lists(this))
        if (left.isEmpty()) {
            Snackbar.make(root, R.string.block_lists_all_added, Snackbar.LENGTH_SHORT).show()
            return
        }
        val chosen = BooleanArray(left.size)
        AlertDialog.Builder(this)
            .setTitle(R.string.block_lists_add_suggested)
            .setMultiChoiceItems(left.map { "${it.name} — ${it.about}" }.toTypedArray(), chosen) { _, i, on ->
                chosen[i] = on
            }
            .setPositiveButton(R.string.block_lists_add_button) { _, _ ->
                left.filterIndexed { i, _ -> chosen[i] }.forEach { AdBlock.addAddress(this, it.url, it.name) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askAddress() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val field = EditText(this).apply {
            hint = "https://"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = LinearLayout(this).apply {
            setPadding(pad + pad / 2, pad / 2, pad + pad / 2, 0)
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.block_lists_add_address)
            .setMessage(R.string.block_lists_address_hint)
            .setView(box)
            .setPositiveButton(R.string.block_lists_add_button) { _, _ ->
                val url = field.text.toString().trim()
                when {
                    !BlockLists.isAddress(url) ->
                        Snackbar.make(root, R.string.block_lists_bad_address, Snackbar.LENGTH_LONG).show()
                    AdBlock.lists(this).any { it.url == url } ->
                        Snackbar.make(root, R.string.block_lists_already, Snackbar.LENGTH_SHORT).show()
                    else -> AdBlock.addAddress(this, url, Uri.parse(url).host ?: url)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addFile(uri: Uri) {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.substringBeforeLast('.') ?: getString(R.string.block_lists_from_file)
        AdBlock.addFile(this, uri, name) { problem ->
            val text = if (problem == null) getString(R.string.block_lists_file_added) else getString(R.string.block_lists_file_failed, problem)
            Snackbar.make(root, text, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun askRemove(list: BlockList) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.block_lists_remove_title, list.name))
            .setPositiveButton(R.string.block_lists_remove) { _, _ -> AdBlock.remove(this, list.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "easylist.to · updated 2 hours ago", "From a file · added 3 Dec", or what went wrong. */
    private fun meta(l: BlockList): String {
        val source = l.url?.let { Uri.parse(it).host } ?: getString(R.string.block_lists_from_file)
        val now = System.currentTimeMillis()
        val time = if (now - l.updated < DateUtils.MINUTE_IN_MILLIS) {
            getString(R.string.block_lists_just_now)
        } else {
            DateUtils.getRelativeTimeSpanString(l.updated, now, DateUtils.MINUTE_IN_MILLIS)
        }
        val state = when {
            l.error != null -> getString(R.string.block_lists_failed, l.error)
            l.updated == 0L -> getString(R.string.block_lists_not_yet)
            l.url == null -> getString(R.string.block_lists_added_at, time)
            else -> getString(R.string.block_lists_updated_at, time)
        }
        return "$source · $state"
    }

    private inner class ListsAdapter : RecyclerView.Adapter<ListsAdapter.Holder>() {
        private var lists: List<BlockList> = emptyList()

        fun submit(next: List<BlockList>) {
            lists = next
            notifyDataSetChanged()
        }

        override fun getItemCount() = lists.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_block_list, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(lists[position])

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val name = view.findViewById<TextView>(R.id.blockListName)
            private val meta = view.findViewById<TextView>(R.id.blockListMeta)
            private val on = view.findViewById<MaterialSwitch>(R.id.blockListOn)
            private val more = view.findViewById<ImageButton>(R.id.blockListMore)

            fun bind(l: BlockList) {
                name.text = l.name
                name.alpha = if (l.on) 1f else 0.5f
                meta.text = meta(l)
                meta.setTextColor(
                    if (l.error != null) getColor(android.R.color.holo_red_light) else name.currentTextColor
                )
                meta.alpha = if (l.error != null) 1f else 0.7f
                on.setOnCheckedChangeListener(null)
                on.isChecked = l.on
                on.setOnCheckedChangeListener { _, checked -> AdBlock.setOn(this@BlockListsActivity, l.id, checked) }
                more.setOnClickListener { v ->
                    PopupMenu(this@BlockListsActivity, v).apply {
                        menu.add(R.string.block_lists_remove).setOnMenuItemClickListener {
                            askRemove(l)
                            true
                        }
                        show()
                    }
                }
            }
        }
    }
}
