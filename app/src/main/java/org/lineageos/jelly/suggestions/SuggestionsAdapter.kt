/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.suggestions

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import org.lineageos.jelly.R
import java.util.Locale

class SuggestionsAdapter(context: Context) : BaseAdapter(), Filterable {
    private val inflater = LayoutInflater.from(context)
    private val list = Suggestions()
    private val items get() = list.items
    private val filter = ItemFilter()

    var suggestionProvider: SuggestionProvider? = null

    override fun getCount() = items.size

    override fun getItem(position: Int) = items[position]

    override fun getItemId(position: Int) = 0L

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val layout = (convertView ?: inflater.inflate(
            R.layout.item_suggestion, parent, false
        )) as LinearLayout
        val suggestion = items[position]
        val title = layout.findViewById<TextView>(R.id.suggest_title)
        val url = layout.findViewById<TextView>(R.id.suggest_url)
        url.isVisible = suggestion.url != null
        list.query?.also { query ->
            title.text = getSpannable(query, suggestion.title)
            url.text = getSpannable(query, suggestion.url ?: "")
        } ?: run {
            title.text = suggestion.title
            url.text = suggestion.url
        }
        return layout
    }

    private fun getSpannable(query: String, text: String): SpannableStringBuilder {
        val spannable = SpannableStringBuilder(text)
        val lcSuggestion = text.lowercase(Locale.getDefault())
        var queryTextPos = lcSuggestion.indexOf(query)
        while (queryTextPos >= 0) {
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                queryTextPos,
                queryTextPos + query.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            queryTextPos = lcSuggestion.indexOf(query, queryTextPos + query.length)
        }
        return spannable
    }

    override fun getFilter(): Filter = filter

    private inner class ItemFilter : Filter() {
        // Runs in the background: only looks up, the list on screen changes in publishResults
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val provider = suggestionProvider
            val found = list.lookUp(constraint) { provider?.fetchResults(it) ?: emptyList() }
            return FilterResults().apply {
                values = found
                count = found.items.size
            }
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults) {
            (results.values as? Suggestions.Found)?.let(list::show)
            notifyDataSetChanged()
        }
    }
}

/**
 * The suggestions the list shows. [lookUp] runs in the background and changes nothing;
 * [show] puts its answer on screen, on the main thread. Changing the list from the
 * background while it was drawing crashed the browser when typing fast.
 */
internal class Suggestions {
    class Found(val query: String?, val items: List<SuggestItem>)

    var items: List<SuggestItem> = emptyList()
        private set
    var query: String? = null
        private set

    fun lookUp(typed: CharSequence?, fetch: (String) -> List<SuggestItem>): Found {
        if (typed.isNullOrBlank()) return Found(null, emptyList())
        val query = typed.toString().lowercase(Locale.getDefault()).trim()
        return Found(query, fetch(query))
    }

    fun show(found: Found) {
        items = found.items
        query = found.query
    }
}
