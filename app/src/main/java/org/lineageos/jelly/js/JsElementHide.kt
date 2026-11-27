/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.js

import android.net.Uri
import android.webkit.JavascriptInterface
import org.lineageos.jelly.adblock.AdBlock
import org.lineageos.jelly.webview.WebViewExt

/**
 * The page side of "Hide an element": pages ask for their hiding stylesheet
 * as they start, and the picker reports what's chosen. A page only gets the
 * rules for the site this tab is loading, never another site's.
 */
class JsElementHide(private val webView: WebViewExt) {
    @JavascriptInterface
    fun hideCss(url: String): String {
        val host = hostOf(url) ?: return ""
        val loading = listOfNotNull(hostOf(webView.pageUrl), hostOf(webView.startedUrl))
        if (loading.none { sameSite(it, host) }) return ""
        return AdBlock.hideCss(url)
    }

    @JavascriptInterface
    fun onPick(label: String) {
        webView.post { webView.onElementPicked(label) }
    }

    companion object {
        const val INTERFACE = "JellyHolo"

        /** Adds (or refreshes) the page's hiding stylesheet. Top page only; safe to run twice. */
        const val SCRIPT = """
            (function () {
                try {
                    if (window.top !== window || !window.$INTERFACE) return;
                    var css = $INTERFACE.hideCss(location.href);
                    var style = document.getElementById('__jh_hide');
                    if (!css && !style) return;
                    if (!style) {
                        style = document.createElement('style');
                        style.id = '__jh_hide';
                        (document.head || document.documentElement).appendChild(style);
                    }
                    style.textContent = css || '';
                } catch (e) {}
            })();
        """

        private fun hostOf(url: String): String? =
            runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }

        /** Same host, or one is a subdomain of the other (redirects like example.com → www.example.com). */
        private fun sameSite(a: String, b: String) =
            a == b || a.endsWith(".$b") || b.endsWith(".$a")
    }
}
