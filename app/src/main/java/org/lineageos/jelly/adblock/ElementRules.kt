/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.adblock

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Elements the user chose to hide, one "site##selector" rule per line in
 * files/element_rules.txt (the uBlock format). Matching happens in Rust.
 */
object ElementRules {
    private const val FILE = "element_rules.txt"

    @Volatile
    private var rules: List<String> = emptyList()

    /** Read the saved rules and hand them to the matcher. Safe off the main thread. */
    fun load(context: Context) {
        val file = File(context.filesDir, FILE)
        val saved = runCatching {
            if (file.isFile) file.readLines().map { it.trim() }.filter { it.contains("##") } else emptyList()
        }.getOrDefault(emptyList())
        synchronized(this) {
            rules = saved
            AdBlock.setElementRules(saved)
        }
    }

    fun all(): List<String> = rules

    /** Hide `selector` on the site of `pageUrl` from now on. Returns the rule, or null if the page has no site. */
    fun add(context: Context, pageUrl: String?, selector: String): String? {
        val site = siteOf(pageUrl) ?: return null
        val sel = selector.trim()
        if (sel.isEmpty() || sel.contains('\n') || sel.contains('\r')) return null
        val rule = "$site##$sel"
        synchronized(this) {
            if (rule !in rules) save(context, rules + rule)
        }
        return rule
    }

    fun remove(context: Context, rule: String) {
        synchronized(this) { save(context, rules - rule) }
    }

    fun site(rule: String) = rule.substringBefore("##")
    fun selector(rule: String) = rule.substringAfter("##")

    /** The site a rule is saved under: the page's host without a leading "www.". */
    fun siteOf(url: String?): String? {
        val host = url?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: return null
        return host.lowercase().removePrefix("www.").takeIf { it.isNotEmpty() }
    }

    private fun save(context: Context, list: List<String>) {
        val dir = context.applicationContext.filesDir
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(list.joinToString("\n", postfix = if (list.isEmpty()) "" else "\n"))
        tmp.renameTo(File(dir, FILE))
        rules = list
        AdBlock.setElementRules(list)
    }
}
