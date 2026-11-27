/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer

/** A bitrate that keeps H.264 close to the original at this size. */
fun bitrateFor(width: Int, height: Int, fps: Double): Int =
    (width.toLong() * height * fps.coerceIn(24.0, 60.0) * 0.12).toInt().coerceIn(1_000_000, 20_000_000)

/** [width] x [height] shrunk to fit [maxW] x [maxH], same shape, even sides, never bigger. */
fun fitSize(width: Int, height: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
    val scale = minOf(1.0, maxW.toDouble() / width, maxH.toDouble() / height)
    val w = (width * scale).toInt() and 1.inv()
    val h = (height * scale).toInt() and 1.inv()
    return w to h
}

/**
 * Converts a video the phone can't play well into H.264, with Android's own
 * decoder and the phone's hardware encoder. Every frame keeps its original
 * time, so picture and sound stay in step. Sound is copied when it's AAC,
 * else converted to AAC.
 */
object Transcode {
    private const val TIMEOUT_US = 10_000L

    class Cancelled : IOException("cancelled")

    /** Whether the phone's H.264 encoder takes pictures of this size. */
    fun encodable(width: Int, height: Int): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .any { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities.isSizeSupported(width, height) }
    }.getOrDefault(false)

    /**
     * Writes [video]'s picture (and [audio]'s sound, or [video]'s own) to [out]
     * as an MP4. [progress] gets 0..100. False if [cancelled] stopped it.
     */
    fun toH264(
        video: File,
        audio: File?,
        out: FileDescriptor,
        cache: File,
        progress: (Int) -> Unit,
        cancelled: () -> Boolean,
        log: (String) -> Unit,
    ): Boolean {
        var tempSound: File? = null
        try {
            // The sound first: copied as it is, or converted to AAC in a file of its own
            val soundSource = audio ?: video
            var sound = openTrack(soundSource, "audio/")
            if (sound != null && sound.second.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) {
                sound.first.release()
                tempSound = File(cache, "sound.m4a")
                if (!soundToAac(soundSource, tempSound, cancelled, log)) return false
                sound = openTrack(tempSound, "audio/")
            }
            return pictures(video, sound, out, progress, cancelled, log)
        } catch (e: Cancelled) {
            return false
        } finally {
            tempSound?.delete()
        }
    }

    private fun openTrack(file: File, prefix: String): Pair<MediaExtractor, MediaFormat>? {
        val ex = MediaExtractor()
        ex.setDataSource(file.path)
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) {
                ex.selectTrack(i)
                return ex to f
            }
        }
        ex.release()
        return null
    }

    private fun pictures(
        file: File,
        sound: Pair<MediaExtractor, MediaFormat>?,
        out: FileDescriptor,
        progress: (Int) -> Unit,
        cancelled: () -> Boolean,
        log: (String) -> Unit,
    ): Boolean {
        val (ex, inFormat) = openTrack(file, "video/") ?: throw IOException("no picture found")
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxing = false
        try {
            val width = inFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = inFormat.getInteger(MediaFormat.KEY_HEIGHT)
            if (!encodable(width, height)) throw IOException("this phone can't make H.264 at ${width}x$height")
            val fps = if (inFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                runCatching { inFormat.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                    .getOrElse { inFormat.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }
            } else {
                30.0
            }
            val duration = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val outFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(width, height, fps))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.toInt().coerceAtLeast(1))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            log("converting ${inFormat.getString(MediaFormat.KEY_MIME)} ${width}x$height at $fps fps")
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()
            decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(inFormat, surface, null, 0)
            decoder.start()

            muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (inFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(inFormat.getInteger(MediaFormat.KEY_ROTATION))
            }
            var videoTrack = -1
            var soundTrack = -1
            val soundBuf = ByteBuffer.allocate(
                sound?.second?.takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }
                    ?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)?.coerceAtLeast(64 * 1024) ?: (256 * 1024)
            )
            val soundInfo = MediaCodec.BufferInfo()
            var soundDone = sound == null

            // Copies the sound up to [until] (microseconds), so the file is interleaved
            fun copySound(until: Long) {
                val s = sound ?: return
                while (!soundDone && muxing) {
                    val t = s.first.sampleTime
                    if (t < 0) {
                        soundDone = true
                        break
                    }
                    if (t > until) break
                    val n = s.first.readSampleData(soundBuf, 0)
                    if (n < 0) {
                        soundDone = true
                        break
                    }
                    soundInfo.set(0, n, t, if (s.first.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(soundTrack, soundBuf, soundInfo)
                    s.first.advance()
                }
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var decodeDone = false
            var encodeDone = false
            var lastShown = -1
            while (!encodeDone) {
                if (cancelled()) throw Cancelled()
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = decoder.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                if (!decodeDone) {
                    val o = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        // Hand the frame to the encoder with its own time
                        decoder.releaseOutputBuffer(o, info.size > 0 || !eos)
                        if (eos) {
                            encoder.signalEndOfInputStream()
                            decodeDone = true
                        }
                    }
                }
                while (true) {
                    val e = encoder.dequeueOutputBuffer(info, if (decodeDone) TIMEOUT_US else 0)
                    if (e == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (e == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        sound?.let { soundTrack = muxer.addTrack(it.second) }
                        muxer.start()
                        muxing = true
                        continue
                    }
                    if (e < 0) continue
                    val data = encoder.getOutputBuffer(e)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        copySound(info.presentationTimeUs)
                        muxer.writeSampleData(videoTrack, data, info)
                        if (duration > 0) {
                            val pct = (info.presentationTimeUs * 100 / duration).toInt().coerceIn(0, 100)
                            if (pct != lastShown) {
                                lastShown = pct
                                progress(pct)
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(e, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encodeDone = true
                        break
                    }
                }
            }
            if (!muxing) throw IOException("the encoder made nothing")
            copySound(Long.MAX_VALUE)
            muxer.stop()
            muxing = false
            return true
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxing) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            ex.release()
            sound?.first?.release()
        }
    }

    /** Converts [file]'s sound to stereo AAC in its own MP4. */
    private fun soundToAac(file: File, out: File, cancelled: () -> Boolean, log: (String) -> Unit): Boolean {
        val (ex, inFormat) = openTrack(file, "audio/") ?: throw IOException("no sound found")
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxing = false
        try {
            decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                inFormat.setInteger(MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, 2)
            }
            decoder.configure(inFormat, null, null, 0)
            decoder.start()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtMost(2)
            var firstTime = -1L
            var framesFed = 0L
            val pending = java.io.ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var decodeDone = false
            var encodeDone = false
            var encInputDone = false

            fun startEncoder() {
                val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                }
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
                log("converting sound ${inFormat.getString(MediaFormat.KEY_MIME)} to AAC, $rate Hz, $channels channels")
            }

            while (!encodeDone) {
                if (cancelled()) throw Cancelled()
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val n = ex.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                if (!decodeDone) {
                    val o = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (channels > 2) throw IOException("can't convert $channels-channel sound")
                    } else if (o >= 0) {
                        if (encoder == null) startEncoder()
                        if (firstTime < 0 && info.size > 0) firstTime = info.presentationTimeUs
                        val b = decoder.getOutputBuffer(o)!!
                        val chunk = ByteArray(info.size)
                        b.position(info.offset)
                        b.get(chunk)
                        pending.write(chunk)
                        decoder.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decodeDone = true
                    }
                }
                val enc = encoder ?: continue
                // Feed what's decoded, timed by counting samples
                while (!encInputDone) {
                    val whole = pending.size() and (2 * channels - 1).inv()
                    if (whole == 0 && !decodeDone) break
                    val i = enc.dequeueInputBuffer(0)
                    if (i < 0) break
                    val buf = enc.getInputBuffer(i)!!
                    val bytes = pending.toByteArray()
                    val n = minOf(bytes.size, buf.capacity()) and (2 * channels - 1).inv()
                    val time = firstTime.coerceAtLeast(0) + framesFed * 1_000_000 / rate
                    if (n == 0 && decodeDone) {
                        enc.queueInputBuffer(i, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encInputDone = true
                        break
                    }
                    buf.clear()
                    buf.put(bytes, 0, n)
                    enc.queueInputBuffer(i, 0, n, time, 0)
                    framesFed += n / (2 * channels)
                    pending.reset()
                    pending.write(bytes, n, bytes.size - n)
                }
                while (true) {
                    val e = enc.dequeueOutputBuffer(info, 0)
                    if (e == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (e == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(enc.outputFormat)
                        muxer.start()
                        muxing = true
                        continue
                    }
                    if (e < 0) continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) muxer.writeSampleData(track, enc.getOutputBuffer(e)!!, info)
                    enc.releaseOutputBuffer(e, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encodeDone = true
                        break
                    }
                }
            }
            if (!muxing) throw IOException("the sound encoder made nothing")
            muxer.stop()
            muxing = false
            return true
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxing) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            ex.release()
        }
    }
}
