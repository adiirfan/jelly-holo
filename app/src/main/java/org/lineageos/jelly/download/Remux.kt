/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Puts the picture and sound of a downloaded stream into one ordinary MP4,
 * with Android's own reader and writer: no re-encoding, so it is quick and
 * loses nothing.
 */
object Remux {
    private class Source(val extractor: MediaExtractor, val format: MediaFormat) {
        var muxerTrack = -1
        var first = Long.MIN_VALUE
        var shift = 0L
        var done = false

        /** The last sound time written: sound must never go back in time. */
        var last = Long.MIN_VALUE

        /** AAC sample rate, for splitting clumps of frames; 0 for anything else. */
        val aacRate: Int = if (format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC &&
            format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
        ) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            0
        }

        // Split frames are timed by counting them (1024 samples each) from an anchor
        var anchor = Long.MIN_VALUE
        var counted = 0L

        fun frameTime(n: Long) = anchor + n * 1024L * 1_000_000L / aacRate
    }

    /**
     * Writes [video] (and the separate [audio], if any) to [out] as an MP4.
     * False if Android can't read the pieces or can't put them in an MP4.
     */
    fun toMp4(video: File, audio: File?, out: FileDescriptor, log: (String) -> Unit = {}): Boolean {
        val sources = mutableListOf<Source>()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            sources += open(video, "video/") ?: run {
                log("no picture found")
                return false
            }
            val sound = if (audio != null) open(audio, "audio/") else open(video, "audio/")
            if (audio != null && sound == null) {
                log("no sound found in the sound track")
                return false
            }
            sound?.let { sources += it }

            muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            sources.forEach { it.muxerTrack = muxer.addTrack(it.format) }
            muxer.start()
            started = true

            // Streams count time from wherever the broadcast began. The file starts
            // at the first picture: Android's MP4 writer drops any gap before a
            // track's first sample, so the sound that came before the first picture
            // (a fraction of a second) is left out instead, keeping the two in step.
            // Tracks whose starts are far apart are on different clocks and each
            // start at 0.
            sources.forEach { it.first = it.extractor.sampleTime.coerceAtLeast(0) }
            val start = sources[0].first
            sources.forEach { it.shift = if (abs(it.first - start) > 5_000_000) it.first else start }

            val size = sources.maxOf { s ->
                if (s.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) s.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            }.coerceAtLeast(2 shl 20)
            val buffer = ByteBuffer.allocateDirect(size)
            val frame = ByteBuffer.allocateDirect(size)
            var bytes = ByteArray(0)
            val info = MediaCodec.BufferInfo()
            while (true) {
                // Interleave: always write the sample that comes next in time
                val s = sources.filter { !it.done }.minByOrNull { it.extractor.sampleTime } ?: break
                buffer.clear()
                val n = s.extractor.readSampleData(buffer, 0)
                if (n < 0) {
                    s.done = true
                    continue
                }
                val sound = s !== sources[0]
                val time = (s.extractor.sampleTime - s.shift).let { if (sound) it else it.coerceAtLeast(0) }
                val flags = if (s.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                // Sound handed over as a clump of framed AAC: one bare frame per sample
                val parts = if (s.aacRate > 0) {
                    if (bytes.size < n) bytes = ByteArray(n)
                    buffer.position(0)
                    buffer.limit(n)
                    buffer.get(bytes, 0, n)
                    Adts.payloads(bytes, n)
                } else {
                    null
                }
                if (parts == null) {
                    if (time < 0) {
                        if (!s.extractor.advance()) s.done = true
                        continue
                    }
                    buffer.position(0)
                    buffer.limit(n)
                    info.set(0, n, if (sound) nextSoundTime(s, time) else time, flags)
                    muxer.writeSampleData(s.muxerTrack, buffer, info)
                } else {
                    // Frames follow on from the last one; only a real gap in the
                    // sound (over 0.1 s) starts the count again from the clump's time
                    if (s.anchor == Long.MIN_VALUE || time - s.frameTime(s.counted) > 100_000) {
                        s.anchor = time
                        s.counted = 0
                    }
                    parts.forEach { r ->
                        val at = s.frameTime(s.counted++)
                        if (at < 0) return@forEach
                        frame.clear()
                        frame.put(bytes, r.first, r.last - r.first + 1)
                        frame.flip()
                        info.set(0, r.last - r.first + 1, nextSoundTime(s, at), MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        muxer.writeSampleData(s.muxerTrack, frame, info)
                    }
                }
                if (!s.extractor.advance()) s.done = true
            }
            muxer.stop()
            started = false
            return true
        } catch (e: Exception) {
            log("couldn't make the MP4: $e")
            return false
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            sources.forEach { runCatching { it.extractor.release() } }
        }
    }

    private fun nextSoundTime(s: Source, t: Long): Long = maxOf(t, s.last + 1).also { s.last = it }

    /** The first track of [file] whose type starts with [kind], selected and ready to read. */
    private fun open(file: File, kind: String): Source? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            for (i in 0 until ex.trackCount) {
                val format = ex.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true) {
                    ex.selectTrack(i)
                    return Source(ex, format)
                }
            }
        } catch (e: Exception) {
            // Not readable: fall through
        }
        ex.release()
        return null
    }
}
