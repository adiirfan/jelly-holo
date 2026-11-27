/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.video

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import org.lineageos.jelly.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The app's own controls over a full-screen video. The page keeps playing
 * the video; this view sends it commands and shows what it reports.
 *
 * Tap: show/hide controls. Double tap on the left or right third: skip 10 s.
 * Swipe sideways: scrub. Swipe up/down: brightness (left half), volume (right).
 */
class VideoPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    interface Host {
        fun send(cmd: String, vararg args: Pair<String, Any>)
        fun download(state: VideoState)
        fun pictureInPicture(state: VideoState)
        fun close()
    }

    var host: Host? = null

    private val controls by lazy { findViewById<View>(R.id.vpControls) }
    private val title by lazy { findViewById<TextView>(R.id.vpTitle) }
    private val speed by lazy { findViewById<TextView>(R.id.vpSpeed) }
    private val play by lazy { findViewById<ImageButton>(R.id.vpPlay) }
    private val seek by lazy { findViewById<SeekBar>(R.id.vpSeek) }
    private val timeText by lazy { findViewById<TextView>(R.id.vpTime) }
    private val durationText by lazy { findViewById<TextView>(R.id.vpDuration) }
    private val download by lazy { findViewById<View>(R.id.vpDownload) }
    private val unlock by lazy { findViewById<View>(R.id.vpUnlock) }
    private val feedback by lazy { findViewById<TextView>(R.id.vpFeedback) }

    private var state: VideoState? = null
    private var locked = false
    private var seeking = false

    private val audio by lazy { context.getSystemService(AudioManager::class.java) }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val hideControls = Runnable { if (state?.playing == true && !seeking) controls.isVisible = false }
    private val hideUnlock = Runnable { unlock.isVisible = false }
    private val hideFeedback = Runnable { feedback.isVisible = false }

    override fun onFinishInflate() {
        super.onFinishInflate()
        findViewById<View>(R.id.vpClose).setOnClickListener { host?.close() }
        findViewById<View>(R.id.vpBack).setOnClickListener { skip(-10) }
        findViewById<View>(R.id.vpForward).setOnClickListener { skip(10) }
        play.setOnClickListener { host?.send("toggle"); poke() }
        speed.setOnClickListener {
            val next = SPEEDS[(SPEEDS.indexOfFirst { abs(it - (state?.rate ?: 1.0)) < 0.01 } + 1) % SPEEDS.size]
            host?.send("rate", "v" to next)
            speed.text = speedLabel(next)
            poke()
        }
        download.setOnClickListener { state?.let { host?.download(it) } }
        findViewById<View>(R.id.vpPip).setOnClickListener { state?.let { host?.pictureInPicture(it) } }
        findViewById<View>(R.id.vpLock).setOnClickListener { setLocked(true) }
        unlock.setOnClickListener { setLocked(false) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val d = state?.duration ?: return
                if (fromUser && d > 0) timeText.text = formatTime(d * progress / bar.max)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
                removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                val d = state?.duration ?: return
                if (d > 0) host?.send("seekTo", "t" to d * bar.progress / bar.max)
                poke()
            }
        })
    }

    /** Show the controls again, and hide them in 3 s while playing. */
    fun poke() {
        if (locked) return
        controls.isVisible = true
        removeCallbacks(hideControls)
        postDelayed(hideControls, 3000)
    }

    fun update(s: VideoState) {
        val first = state == null
        state = s
        title.text = s.title
        play.setImageResource(if (s.playing) R.drawable.ic_vp_pause else R.drawable.ic_vp_play)
        speed.text = speedLabel(s.rate)
        download.alpha = if (s.kind == "file") 1f else 0.4f
        if (!seeking && !scrubbing) {
            timeText.text = formatTime(s.time)
            if (s.duration > 0) seek.progress = (s.time / s.duration * seek.max).roundToInt()
        }
        durationText.text = formatTime(s.duration)
        seek.isEnabled = s.duration > 0
        val wasPlaying = playingBefore
        playingBefore = s.playing
        when {
            first -> poke()
            // Paused: controls stay up until playback resumes
            !s.playing && !locked -> {
                controls.isVisible = true
                removeCallbacks(hideControls)
            }
            // Resumed: the 3 s countdown starts now (progress reports don't restart it)
            s.playing && !wasPlaying && controls.isVisible -> poke()
        }
    }

    private var playingBefore = false

    private fun setLocked(on: Boolean) {
        locked = on
        controls.isVisible = !on
        unlock.isVisible = on
        removeCallbacks(hideUnlock)
        if (on) postDelayed(hideUnlock, 2500) else poke()
    }

    private fun skip(by: Int) {
        host?.send("seekBy", "s" to by)
        show(context.getString(if (by < 0) R.string.vp_skip_back else R.string.vp_skip_forward))
    }

    private fun show(text: String) {
        feedback.text = text
        feedback.isVisible = true
        removeCallbacks(hideFeedback)
        postDelayed(hideFeedback, 900)
    }

    // Gestures on the empty parts of the player (buttons take their own taps)
    private enum class Drag { NONE, SCRUB, BRIGHTNESS, VOLUME }

    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var startLevel = 0f
    private var scrubStart = 0.0
    private var scrubbing = false

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (locked) {
                unlock.isVisible = true
                removeCallbacks(hideUnlock)
                postDelayed(hideUnlock, 2500)
            } else if (controls.isVisible) {
                controls.isVisible = false
            } else {
                poke()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (locked) return true
            when (sideOf(e.x, width.toFloat())) {
                Side.BACK -> skip(-10)
                Side.FORWARD -> skip(10)
                Side.MIDDLE -> host?.send("toggle")
            }
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        taps.onTouchEvent(e)
        if (locked) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = Drag.NONE
                downX = e.x
                downY = e.y
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (drag == Drag.NONE && (abs(dx) > touchSlop * 2 || abs(dy) > touchSlop * 2)) {
                    drag = when {
                        abs(dx) > abs(dy) -> Drag.SCRUB
                        downX < width / 2 -> Drag.BRIGHTNESS
                        else -> Drag.VOLUME
                    }
                    startDrag()
                }
                moveDrag(dx, dy)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag == Drag.SCRUB && e.actionMasked == MotionEvent.ACTION_UP) {
                    val s = state
                    if (s != null) {
                        host?.send("seekTo", "t" to scrubTo(scrubStart, e.x - downX, width.toFloat(), s.duration))
                    }
                }
                scrubbing = false
                drag = Drag.NONE
            }
        }
        return true
    }

    private fun startDrag() {
        when (drag) {
            Drag.SCRUB -> {
                scrubStart = state?.time ?: 0.0
                scrubbing = true
            }

            Drag.BRIGHTNESS -> startLevel = brightness()
            Drag.VOLUME -> startLevel = volume()
            Drag.NONE -> Unit
        }
    }

    private fun moveDrag(dx: Float, dy: Float) {
        when (drag) {
            Drag.SCRUB -> {
                val s = state ?: return
                val t = scrubTo(scrubStart, dx, width.toFloat(), s.duration)
                show("${formatTime(t)} / ${formatTime(s.duration)}")
            }

            Drag.BRIGHTNESS -> {
                val level = levelAfterSwipe(startLevel, dy, height.toFloat())
                setBrightness(level)
                show(context.getString(R.string.vp_brightness, (level * 100).roundToInt()))
            }

            Drag.VOLUME -> {
                val level = levelAfterSwipe(startLevel, dy, height.toFloat())
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt(), 0)
                show(context.getString(R.string.vp_volume, (level * 100).roundToInt()))
            }

            Drag.NONE -> Unit
        }
    }

    private fun window() = (context as? Activity)?.window

    private fun brightness(): Float {
        val set = window()?.attributes?.screenBrightness ?: -1f
        if (set >= 0) return set
        return runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
    }

    private fun setBrightness(level: Float) {
        val w = window() ?: return
        w.attributes = w.attributes.apply { screenBrightness = level.coerceIn(0.01f, 1f) }
    }

    private fun volume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    override fun onDetachedFromWindow() {
        // Brightness follows the phone again once the player closes
        window()?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        removeCallbacks(hideControls)
        removeCallbacks(hideUnlock)
        removeCallbacks(hideFeedback)
        super.onDetachedFromWindow()
    }

    companion object {
        private val SPEEDS = doubleArrayOf(1.0, 1.25, 1.5, 2.0, 0.5, 0.75)

        private fun speedLabel(rate: Double) =
            (if (rate % 1.0 == 0.0) rate.toInt().toString() else rate.toString().trimEnd('0')) + "×"
    }
}
