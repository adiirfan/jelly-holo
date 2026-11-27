/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.video

import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** What the page says about the video being watched (see assets/video.js). */
data class VideoState(
    val playing: Boolean,
    val time: Double,
    val duration: Double,
    val rate: Double,
    val kind: String,
    val src: String,
    val w: Int,
    val h: Int,
    val x: Int,
    val y: Int,
    val videoW: Int,
    val videoH: Int,
    val viewW: Int,
    val top: Boolean,
    val fullscreen: Boolean,
    val gesture: Boolean,
    val muted: Boolean,
    val title: String,
) {
    companion object {
        fun parse(json: String): VideoState? = runCatching {
            val o = JSONObject(json)
            if (o.optString("type") != "state") return null
            VideoState(
                playing = o.optBoolean("playing"),
                time = o.optDouble("time", 0.0),
                duration = o.optDouble("duration", -1.0),
                rate = o.optDouble("rate", 1.0),
                kind = o.optString("kind"),
                src = o.optString("src"),
                w = o.optInt("w"),
                h = o.optInt("h"),
                x = o.optInt("x"),
                y = o.optInt("y"),
                videoW = o.optInt("vw"),
                videoH = o.optInt("vh"),
                viewW = o.optInt("viewW"),
                top = o.optBoolean("top"),
                fullscreen = o.optBoolean("fullscreen"),
                gesture = o.optBoolean("gesture"),
                muted = o.optBoolean("muted"),
                title = o.optString("title"),
            )
        }.getOrNull()
    }
}

/**
 * The app's line to the page script in every frame. The frame that last
 * reported a video is the one commands go to, so a video inside another
 * site's frame (an embedded player) is driven just like a page's own.
 */
class VideoBridge(private val onState: (VideoState) -> Unit) {
    private var proxy: JavaScriptReplyProxy? = null

    var state: VideoState? = null
        private set

    /** Hook into `webView`; call before its first page loads. False if the WebView is too old. */
    fun attach(webView: WebView): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return false
        }
        WebViewCompat.addWebMessageListener(webView, CHANNEL, setOf("*")) { _, message, _, _, reply ->
            val s = message.data?.let { VideoState.parse(it) } ?: return@addWebMessageListener
            // A paused video elsewhere doesn't take over from the one being watched
            if (s.playing || s.fullscreen || proxy == null || reply == proxy) {
                proxy = reply
                state = s
                onState(s)
            }
        }
        return true
    }

    /** A new page: forget the old one's video. */
    fun reset() {
        proxy = null
        state = null
    }

    fun send(cmd: String, vararg args: Pair<String, Any>) {
        val o = JSONObject().put("cmd", cmd)
        args.forEach { (k, v) -> o.put(k, v) }
        runCatching { proxy?.postMessage(o.toString()) }
    }

    companion object {
        const val CHANNEL = "jhVideo"
    }
}
