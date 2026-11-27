/*
 * SPDX-FileCopyrightText: Jelly Holo contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.adblock

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One block list the user has. [url] is null for a list added from a file,
 * which is copied in once and never refreshed.
 */
data class BlockList(
    val id: String,
    val name: String,
    val url: String?,
    val on: Boolean = true,
    /** When its text was last downloaded or copied in; 0 = never. */
    val updated: Long = 0,
    /** How long the list says it stays fresh. */
    val expiresMs: Long = BlockLists.DEFAULT_EXPIRY_MS,
    /** Why the last download failed, or null. */
    val error: String? = null,
    /** When a download was last tried, so a failing one isn't retried at every start. */
    val triedAt: Long = 0,
)

/** A well-known list offered with one tap. */
data class SuggestedList(val name: String, val url: String, val about: String)

object BlockLists {
    const val DEFAULT_EXPIRY_MS = 7 * DAY
    private const val MIN_EXPIRY_MS = DAY
    private const val MAX_EXPIRY_MS = 14 * DAY
    private const val RETRY_MS = 60 * 60_000L

    val SUGGESTED = listOf(
        SuggestedList("EasyList", "https://easylist.to/easylist/easylist.txt", "Most ads"),
        SuggestedList("EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt", "Trackers"),
        SuggestedList("uBlock filters", "https://ublockorigin.github.io/uAssets/filters/filters.txt", "Ads EasyList misses"),
        SuggestedList(
            "AdGuard Base", "https://filters.adtidy.org/extension/ublock/filters/2.txt",
            "Ads; already includes EasyList",
        ),
        SuggestedList(
            "Peter Lowe's list",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext",
            "Ad and tracker servers",
        ),
        SuggestedList("Fanboy's Annoyance List", "https://secure.fanboy.co.nz/fanboy-annoyance.txt", "Pop-ups, chat boxes and banners"),
        SuggestedList("EasyList Cookie List", "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt", "Cookie banners"),
    )

    /** What a new install starts with: EasyList and EasyPrivacy, downloaded when online. */
    private val DEFAULTS = SUGGESTED.take(2)

    fun newId(): String = UUID.randomUUID().toString()

    /** The saved lists, or the defaults when nothing was ever saved. */
    fun firstRun(stored: String?): List<BlockList> =
        parse(stored) ?: DEFAULTS.map { BlockList(newId(), it.name, it.url) }

    fun encode(lists: List<BlockList>): String = JSONArray().apply {
        for (l in lists) {
            put(
                JSONObject()
                    .put("id", l.id).put("name", l.name).put("url", l.url ?: JSONObject.NULL)
                    .put("on", l.on).put("updated", l.updated).put("expires", l.expiresMs)
                    .put("error", l.error ?: JSONObject.NULL).put("tried", l.triedAt)
            )
        }
    }.toString()

    /** Null when nothing was saved, or what was saved can't be read. */
    fun parse(stored: String?): List<BlockList>? {
        if (stored == null) return null
        return runCatching {
            val a = JSONArray(stored)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                BlockList(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    url = o.optString("url").takeUnless { o.isNull("url") },
                    on = o.optBoolean("on", true),
                    updated = o.optLong("updated"),
                    expiresMs = o.optLong("expires", DEFAULT_EXPIRY_MS),
                    error = o.optString("error").takeUnless { o.isNull("error") || !o.has("error") },
                    triedAt = o.optLong("tried"),
                )
            }
        }.getOrNull()
    }

    /** The list's own name, from its "! Title:" line near the top. */
    fun titleOf(text: String): String? = header(text, "title")?.takeIf { it.isNotBlank() }

    /** How long the list says it stays fresh ("! Expires: 4 days"), kept between a day and two weeks. */
    fun expiryOf(text: String): Long {
        val m = header(text, "expires")?.let { Regex("""(\d+)\s*(day|hour)""", RegexOption.IGNORE_CASE).find(it) }
            ?: return DEFAULT_EXPIRY_MS
        val n = m.groupValues[1].toLong()
        val ms = if (m.groupValues[2].lowercase() == "day") n * DAY else n * 60 * 60_000L
        return ms.coerceIn(MIN_EXPIRY_MS, MAX_EXPIRY_MS)
    }

    private fun header(text: String, key: String): String? = text.lineSequence().take(HEADER_LINES)
        .map { it.trim() }
        .firstOrNull { it.startsWith("!") && it.drop(1).trimStart().startsWith("$key:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()

    /**
     * Lists from an address that are on and past their refresh. A failed one waits an hour,
     * unless [retryFailed] (the connection just came back).
     */
    fun due(lists: List<BlockList>, now: Long, retryFailed: Boolean = false): List<BlockList> = lists.filter {
        it.on && it.url != null &&
            (it.updated == 0L || now - it.updated >= it.expiresMs) &&
            (retryFailed || it.error == null || now - it.triedAt >= RETRY_MS)
    }

    fun suggestionsLeft(lists: List<BlockList>): List<SuggestedList> =
        SUGGESTED.filter { s -> lists.none { it.url == s.url } }

    fun isAddress(text: String): Boolean {
        val t = text.trim()
        val rest = when {
            t.startsWith("https://", ignoreCase = true) -> t.substring(8)
            t.startsWith("http://", ignoreCase = true) -> t.substring(7)
            else -> return false
        }
        return rest.substringBefore('/').contains('.')
    }

    /** False for a web page (a wrong address often gives one) or nothing at all. */
    fun looksLikeList(text: String): Boolean {
        val start = text.trimStart().take(100).lowercase()
        return start.isNotEmpty() && !start.startsWith("<!doctype") && !start.startsWith("<html")
    }

    private const val HEADER_LINES = 40
}

private const val DAY = 24 * 60 * 60 * 1000L
