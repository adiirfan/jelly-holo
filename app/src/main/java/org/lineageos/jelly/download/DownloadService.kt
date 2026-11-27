/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.lineageos.jelly.R
import org.lineageos.jelly.adblock.AdBlock
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import android.media.MediaMuxer
import org.lineageos.jelly.ytdlp.Format
import org.lineageos.jelly.ytdlp.Output
import org.lineageos.jelly.ytdlp.YtDlpJob
import org.lineageos.jelly.ytdlp.YtDlpRuntime
import org.lineageos.jelly.ytdlp.fileName
import org.lineageos.jelly.ytdlp.problemOf
import org.lineageos.jelly.ytdlp.runWithLimit
import org.lineageos.jelly.ytdlp.writeCookies
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Downloads files in several parts at once (see [ParallelDownload]) straight
 * into the phone's Downloads folder, with a progress notification that can
 * cancel and, when done, opens the file.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class DownloadService : Service() {
    private val running = ConcurrentHashMap<Int, Job>()
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    private class Job(val name: String) {
        @Volatile
        var cancelled = false

        /** The job's latest progress notification. */
        @Volatile
        var note: Notification? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        when (intent?.action) {
            ACTION_CANCEL -> running[intent.getIntExtra(EXTRA_ID, -1)]?.cancelled = true

            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val name = intent.getStringExtra(EXTRA_NAME) ?: "download"
                val id = nextId.incrementAndGet()
                val job = Job(name)
                running[id] = job
                job.note = progress(id, name, 0, -1, 0)
                startForeground(id, job.note!!, foregroundType())
                val headers = buildMap {
                    intent.getStringExtra(EXTRA_USER_AGENT)?.let { put("User-Agent", it) }
                    intent.getStringExtra(EXTRA_COOKIE)?.let { put("Cookie", it) }
                    intent.getStringExtra(EXTRA_REFERER)?.let { put("Referer", it) }
                }
                thread(name = "Download$id") {
                    run(id, job, url, headers, intent.getStringExtra(EXTRA_MIME))
                }
            }

            ACTION_START_STREAM -> {
                val streams = intent.getStringArrayExtra(EXTRA_STREAMS)?.toList() ?: return START_NOT_STICKY
                val name = (intent.getStringExtra(EXTRA_NAME) ?: "video") + ".mp4"
                val id = nextId.incrementAndGet()
                val job = Job(name)
                running[id] = job
                job.note = progress(id, name, 0, -1, 0)
                startForeground(id, job.note!!, foregroundType())
                val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
                val referer = intent.getStringExtra(EXTRA_REFERER)
                val duration = intent.getDoubleExtra(EXTRA_DURATION, -1.0)
                val private = intent.getBooleanExtra(EXTRA_PRIVATE, false)
                thread(name = "Download$id") { runStream(id, job, streams, duration, userAgent, referer, private) }
            }

            ACTION_START_YTDLP -> {
                val y = intent.getStringExtra(EXTRA_JOB)?.let { YtDlpJob.fromJson(it) } ?: return START_NOT_STICKY
                val ext = (y.option.video ?: y.option.audio)?.let { if (y.option.output == Output.AUDIO) y.option.audio?.ext else it.ext }
                val name = fileName(y.title, y.option.output, ext)
                val id = nextId.incrementAndGet()
                val job = Job(name)
                running[id] = job
                job.note = progress(id, name, 0, -1, 0)
                startForeground(id, job.note!!, foregroundType())
                thread(name = "Download$id") { runYtDlp(id, job, y) }
            }

            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun foregroundType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

    private fun run(id: Int, job: Job, url: String, headers: Map<String, String>, mimeHint: String?) {
        var item: Uri? = null
        val started = SystemClock.elapsedRealtime()
        // Parts of at least 4 MiB: below about 8 MiB the extra connections
        // cost more time than they save.
        val download = ParallelDownload(url, headers, minPartBytes = 4L shl 20, log = { if (AdBlock.logging) Log.d(AdBlock.TAG, "dl ${job.name}: $it") })
        try {
            val info = download.probe()
            val mime = mimeHint?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
                ?: info.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
                ?: MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(job.name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            item = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, job.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                },
            ) ?: throw java.io.IOException("couldn't create the file")
            var lastShown = 0L
            val ok = contentResolver.openFileDescriptor(item, "rw").use { pfd ->
                FileOutputStream(pfd!!.fileDescriptor).channel.use { channel ->
                    download.download(
                        info,
                        channel,
                        progress = { done ->
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastShown >= 700) {
                                lastShown = now
                                val speed = done * 1000 / (now - started).coerceAtLeast(1)
                                job.note = progress(id, job.name, done, info.length, speed)
                                nm.notify(id, job.note!!)
                            }
                        },
                        cancelled = { job.cancelled },
                    )
                }
            }
            if (!ok) {
                contentResolver.delete(item, null, null)
                return
            }
            contentResolver.update(
                item,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            val secs = (SystemClock.elapsedRealtime() - started) / 1000.0
            Log.i(AdBlock.TAG, "downloaded ${job.name}: ${info.length} bytes in ${download.partsUsed} parts, $secs s")
            finished(id, job.name, item, mime)
        } catch (e: Exception) {
            Log.w(AdBlock.TAG, "download failed: ${job.name}", e)
            item?.let { runCatching { contentResolver.delete(it, null, null) } }
            failed(id, job.name, e)
        } finally {
            download.close()
            ended(id)
        }
    }

    /**
     * A streamed video: find its playlist among [streams], download the
     * pieces into the cache, then join them into one MP4 in Downloads.
     */
    private fun runStream(
        id: Int,
        job: Job,
        streams: List<String>,
        duration: Double,
        userAgent: String?,
        referer: String?,
        private: Boolean,
    ) {
        var item: Uri? = null
        val dir = File(cacheDir, "stream-$id")
        val log: (String) -> Unit = { if (AdBlock.logging) Log.d(AdBlock.TAG, "dl ${job.name}: $it") }
        val origin = referer?.let { r -> runCatching { Uri.parse(r).let { "${it.scheme}://${it.authority}" } }.getOrNull() }
        val cookies = CookieManager.getInstance()
        val hls = HlsDownload(
            headersFor = { url ->
                buildMap {
                    userAgent?.let { put("User-Agent", it) }
                    referer?.let { put("Referer", it) }
                    origin?.let { put("Origin", it) }
                    cookies.getCookie(url)?.takeIf { it.isNotEmpty() }?.let { put("Cookie", it) }
                }
            },
            playable = CodecSupport::playable,
            log = log,
        )
        val started = SystemClock.elapsedRealtime()
        try {
            val plan = hls.choose(streams, duration)
            dir.mkdirs()
            val video = File(dir, "video")
            val audio = plan.audio?.let { File(dir, "audio") }
            var lastShown = 0L
            val ok = hls.download(
                plan, video, audio,
                progress = { done, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastShown >= 700) {
                        lastShown = now
                        job.note = piecesProgress(id, job.name, done, total)
                        nm.notify(id, job.note!!)
                    }
                },
                cancelled = { job.cancelled },
            )
            if (!ok) return
            job.note = piecesProgress(id, job.name, -1, -1)
            nm.notify(id, job.note!!)

            item = newItem(job.name, "video/mp4")
            val joinStart = SystemClock.elapsedRealtime()
            // The Rust joiner first. Anything it doesn't write (or a failure)
            // goes to Android's own tools, on a fresh (emptied) file.
            var how = "rust"
            val fragmented = plan.video.init != null
            var joined = (plan.audio == null || (plan.audio.init != null) == fragmented) &&
                contentResolver.openFileDescriptor(item, "rwt").use {
                    NativeRemux.join(video.path, audio?.path, fragmented, it!!.fd)
                }.also { log("rust join (fragmented $fragmented): $it") } == NativeRemux.DONE
            if (!joined) {
                how = "android"
                joined = contentResolver.openFileDescriptor(item, "rwt").use { Remux.toMp4(video, audio, it!!.fileDescriptor, log) }
            }
            val joinSecs = (SystemClock.elapsedRealtime() - joinStart) / 1000.0
            var mime = "video/mp4"
            var name = job.name
            if (!joined) {
                // One track Android couldn't rewrite: keep the pieces as they came
                if (audio != null) throw IOException(getString(R.string.download_stream_join_failed))
                contentResolver.delete(item, null, null)
                val ts = plan.video.init == null
                mime = if (ts) "video/mp2t" else "video/mp4"
                name = job.name.removeSuffix(".mp4") + if (ts) ".ts" else ".mp4"
                item = newItem(name, mime)
                contentResolver.openOutputStream(item!!).use { out -> video.inputStream().use { it.copyTo(out!!) } }
            }
            contentResolver.update(
                item!!,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            val secs = (SystemClock.elapsedRealtime() - started) / 1000.0
            Log.i(AdBlock.TAG, "downloaded stream $name: ${plan.pieces} pieces, joined=$joined ($how) in $joinSecs s, $secs s")
            finished(id, name, item!!, mime)
        } catch (e: HlsDownload.Refused) {
            if (e.reason == HlsDownload.Reason.NOT_FOUND && referer != null) {
                offerYtDlp(id, job.name, referer, private)
            } else {
                failed(id, job.name, getString(refusalText(e.reason)))
            }
        } catch (e: Exception) {
            Log.w(AdBlock.TAG, "stream download failed: ${job.name}", e)
            item?.let { runCatching { contentResolver.delete(it, null, null) } }
            failed(id, job.name, getString(R.string.download_failed, e.message ?: e.javaClass.simpleName))
        } finally {
            dir.deleteRecursively()
            ended(id)
        }
    }

    /**
     * A video yt-dlp found: download the picture and sound the user picked
     * with the app's own downloaders (or yt-dlp's, for kinds they don't
     * handle), then join them into one file in Downloads.
     */
    private fun runYtDlp(id: Int, job: Job, y: YtDlpJob) {
        var item: Uri? = null
        val dir = File(cacheDir, "ytdlp-$id").apply { mkdirs() }
        val log: (String) -> Unit = { if (AdBlock.logging) Log.d(AdBlock.TAG, "dl ${job.name}: $it") }
        val cookieHeader = if (y.private) null else CookieManager.getInstance().getCookie(y.page)?.takeIf { it.isNotBlank() }
        val cookiesFile = File(dir, "cookies.txt").takeIf { writeCookies(it, y.page, cookieHeader, y.private) }
        val started = SystemClock.elapsedRealtime()
        val o = y.option
        try {
            val video = o.video?.let { File(dir, "video") }
            val audio = o.audio?.let { File(dir, "audio") }
            var before = 0L
            for ((format, file) in listOfNotNull(o.video?.let { it to video!! }, o.audio?.let { it to audio!! })) {
                if (!fetchFormat(id, job, y, format, file, cookieHeader, cookiesFile, before, o.size ?: -1, started, log)) return
                before += file.length()
            }
            job.note = piecesProgress(id, job.name, -1, -1)
            nm.notify(id, job.note!!)

            val joinStart = SystemClock.elapsedRealtime()
            var how = "copy"
            val mime: String
            when {
                o.output == Output.AUDIO -> {
                    mime = if (job.name.endsWith(".webm")) "audio/webm" else "audio/mp4"
                    item = newItem(job.name, mime)
                    copyInto(audio!!, item)
                }

                o.output == Output.WEBM -> {
                    mime = "video/webm"
                    item = newItem(job.name, mime)
                    how = "android"
                    val ok = contentResolver.openFileDescriptor(item, "rwt").use {
                        Remux.toMp4(video!!, audio, it!!.fileDescriptor, log, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
                    }
                    if (!ok) throw IOException(getString(R.string.download_stream_join_failed))
                }

                o.output == Output.CONVERT -> {
                    mime = "video/mp4"
                    item = newItem(job.name, mime)
                    how = "converted"
                    job.note = convertProgress(id, job.name, 0)
                    nm.notify(id, job.note!!)
                    var shown = 0L
                    val ok = contentResolver.openFileDescriptor(item, "rwt").use { pfd ->
                        Transcode.toH264(
                            video!!, audio, pfd!!.fileDescriptor, dir,
                            progress = { pct ->
                                val now = SystemClock.elapsedRealtime()
                                if (now - shown >= 700) {
                                    shown = now
                                    job.note = convertProgress(id, job.name, pct)
                                    nm.notify(id, job.note!!)
                                }
                            },
                            cancelled = { job.cancelled },
                            log = log,
                        )
                    }
                    if (!ok) {
                        contentResolver.delete(item, null, null)
                        item = null
                        return
                    }
                }

                audio == null && !looksFragmented(video!!) && !looksTs(video) -> {
                    // One ordinary file with everything in it
                    mime = "video/mp4"
                    item = newItem(job.name, mime)
                    copyInto(video, item)
                }

                else -> {
                    mime = "video/mp4"
                    item = newItem(job.name, mime)
                    how = join(video!!, audio, item, log)
                }
            }
            contentResolver.update(item, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            val joinSecs = (SystemClock.elapsedRealtime() - joinStart) / 1000.0
            val secs = (SystemClock.elapsedRealtime() - started) / 1000.0
            Log.i(AdBlock.TAG, "downloaded with yt-dlp ${job.name} (${o.label}, ${o.output}): joined ($how) in $joinSecs s, $secs s")
            finished(id, job.name, item, mime)
        } catch (e: Exception) {
            Log.w(AdBlock.TAG, "yt-dlp download failed: ${job.name}", e)
            item?.let { runCatching { contentResolver.delete(it, null, null) } }
            failed(id, job.name, getString(R.string.download_failed, e.message ?: e.javaClass.simpleName))
        } finally {
            dir.deleteRecursively()
            ended(id)
        }
    }

    /** Joins picture and sound into [item]: the Rust joiner when it reads both, else Android's. */
    private fun join(video: File, audio: File?, item: Uri, log: (String) -> Unit): String {
        val fragmented = looksFragmented(video) && (audio == null || looksFragmented(audio))
        val ts = looksTs(video) && (audio == null || looksTs(audio))
        if (fragmented || ts) {
            val r = contentResolver.openFileDescriptor(item, "rwt").use {
                NativeRemux.join(video.path, audio?.path, fragmented, it!!.fd)
            }
            log("rust join (fragmented $fragmented): $r")
            if (r == NativeRemux.DONE) return "rust"
        }
        val ok = contentResolver.openFileDescriptor(item, "rwt").use { Remux.toMp4(video, audio, it!!.fileDescriptor, log) }
        if (!ok) throw IOException(getString(R.string.download_stream_join_failed))
        return "android"
    }

    private fun copyInto(file: File, item: Uri) {
        contentResolver.openOutputStream(item, "wt").use { out -> file.inputStream().use { it.copyTo(out!!) } }
    }

    /**
     * Downloads one format into [file]. Progress covers the whole job:
     * [before] bytes came earlier, out of about [total]. False if cancelled.
     */
    private fun fetchFormat(
        id: Int,
        job: Job,
        y: YtDlpJob,
        f: Format,
        file: File,
        cookieHeader: String?,
        cookiesFile: File?,
        before: Long,
        total: Long,
        started: Long,
        log: (String) -> Unit,
    ): Boolean {
        var lastShown = 0L
        fun show(done: Long) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastShown < 700) return
            lastShown = now
            val speed = done * 1000 / (now - started).coerceAtLeast(1)
            job.note = progress(id, job.name, done, total, speed)
            nm.notify(id, job.note!!)
        }
        val headers = f.headers + listOfNotNull(cookieHeader?.let { "Cookie" to it })
        when {
            f.protocol == "https" || f.protocol == "http" -> {
                val d = ParallelDownload(f.url, headers, maxRequestBytes = MAX_REQUEST, log = log)
                val info = d.probe()
                return RandomAccessFile(file, "rw").use { raf ->
                    raf.setLength(0)
                    d.download(info, raf.channel, progress = { show(before + it) }, cancelled = { job.cancelled })
                }
            }

            f.protocol.startsWith("m3u8") -> {
                val cookies = CookieManager.getInstance()
                val hls = HlsDownload(
                    headersFor = { url ->
                        f.headers + listOfNotNull(
                            if (y.private) null else cookies.getCookie(url)?.takeIf { it.isNotBlank() }?.let { "Cookie" to it }
                        )
                    },
                    playable = CodecSupport::playable,
                    log = log,
                )
                val plan = hls.choose(listOf(f.url), y.duration ?: -1.0)
                val extra = plan.audio?.let { File(file.parentFile, file.name + "-sound") }
                var shown = 0L
                return hls.download(
                    plan, file, extra,
                    progress = { done, all ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - shown >= 700) {
                            shown = now
                            job.note = piecesProgress(id, job.name, done, all)
                            nm.notify(id, job.note!!)
                        }
                    },
                    cancelled = { job.cancelled },
                )
            }

            else -> {
                // A kind of download only yt-dlp knows: let it fetch this one format
                val runtime = YtDlpRuntime(this)
                if (!runtime.prepare()) throw IOException(getString(R.string.ytdlp_failed))
                val args = listOf("-f", f.id, "-o", file.path, "--no-part", "--no-playlist", "--no-warnings") +
                    (cookiesFile?.let { listOf("--cookies", it.path) } ?: emptyList()) + y.page
                val r = runWithLimit(
                    runtime.command(args), Long.MAX_VALUE, { job.cancelled },
                    stallMs = 60_000, growing = { file.length().also { show(before + it) } },
                )
                if (r.cancelled) return false
                if (r.code != 0) {
                    log("yt-dlp download failed (${r.code}, timed out ${r.timedOut}): ${r.err.takeLast(500)}")
                    throw IOException(getString(problemOf(r.err).message))
                }
                return true
            }
        }
    }

    private fun refusalText(reason: HlsDownload.Reason) = when (reason) {
        HlsDownload.Reason.NOT_FOUND -> R.string.download_stream_not_found
        HlsDownload.Reason.LIVE -> R.string.download_stream_live
        HlsDownload.Reason.PROTECTED -> R.string.download_stream_protected
    }

    private fun newItem(name: String, mime: String): Uri = contentResolver.insert(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        },
    ) ?: throw IOException("couldn't create the file")

    /** Progress of a conversion, in percent. */
    private fun convertProgress(id: Int, name: String, percent: Int): Notification {
        val cancel = PendingIntent.getService(
            this, id,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return base(name)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentText(getString(R.string.download_converting, percent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, false)
            .addAction(0, getString(R.string.picker_cancel), cancel)
            .build()
    }

    /** Progress in pieces; done < 0 means the pieces are being joined. */
    private fun piecesProgress(id: Int, name: String, done: Int, total: Int): Notification {
        val cancel = PendingIntent.getService(
            this, id,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val joining = done < 0
        return base(name)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentText(if (joining) getString(R.string.download_joining) else getString(R.string.download_pieces, done, total))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, if (joining || total <= 0) 0 else done * 100 / total, joining)
            .apply { if (!joining) addAction(0, getString(R.string.picker_cancel), cancel) }
            .build()
    }

    /**
     * A job ended: its progress note goes. The service's foreground note
     * moves to another running job first, since Android won't clear the
     * note a foreground service is tied to.
     */
    @Synchronized
    private fun ended(id: Int) {
        running.remove(id)
        val next = running.entries.firstOrNull { it.value.note != null }
        if (next == null) {
            stopIfIdle()
        } else {
            startForeground(next.key, next.value.note!!, foregroundType())
        }
        nm.cancel(id)
    }

    @Synchronized
    private fun stopIfIdle() {
        if (running.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun base(name: String) = NotificationCompat.Builder(this, CHANNEL)
        .setContentTitle(name)
        .setColor(ContextCompat.getColor(this, R.color.holo_blue))

    private fun progress(id: Int, name: String, done: Long, total: Long, speed: Long): Notification {
        val cancel = PendingIntent.getService(
            this, id,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (total > 0) {
            getString(
                R.string.download_progress,
                Formatter.formatShortFileSize(this, done),
                Formatter.formatShortFileSize(this, total),
                Formatter.formatShortFileSize(this, speed),
            )
        } else {
            getString(R.string.download_starting)
        }
        return base(name)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0)
            .addAction(0, getString(R.string.picker_cancel), cancel)
            .build()
    }

    private fun finished(id: Int, name: String, item: Uri, mime: String) {
        val open = PendingIntent.getActivity(
            this, id,
            Intent(Intent.ACTION_VIEW).setDataAndType(item, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // A new id, so the finished note outlives the service's own one
        nm.notify(
            id + DONE_OFFSET,
            base(name)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentText(getString(R.string.download_done))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun failed(id: Int, name: String, e: Exception) =
        failed(id, name, getString(R.string.download_failed, e.message ?: e.javaClass.simpleName))

    private fun failed(id: Int, name: String, text: String) {
        nm.notify(
            id + DONE_OFFSET,
            base(name)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentText(text)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * The page's own stream couldn't be found: tapping the note opens the
     * page in a new tab (as private as the one it came from) and asks
     * yt-dlp. The list of sizes needs the browser, so it can't start here.
     */
    private fun offerYtDlp(id: Int, name: String, page: String, private: Boolean) {
        val open = Intent(this, org.lineageos.jelly.MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(page))
            .putExtra(EXTRA_YTDLP, true)
            .putExtra(org.lineageos.jelly.utils.IntentUtils.EXTRA_INCOGNITO, private)
        val tap = PendingIntent.getActivity(this, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            id + DONE_OFFSET,
            base(name)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentText(getString(R.string.ytdlp_try_instead))
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.ytdlp_try_instead)))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun createChannel() {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_START_STREAM = "start_stream"
        private const val ACTION_START_YTDLP = "start_ytdlp"
        private const val EXTRA_JOB = "job"
        private const val EXTRA_PRIVATE = "private"

        /** On a browser intent: ask yt-dlp about the page as soon as it opens. */
        const val EXTRA_YTDLP = "dev.jellyholo.ytdlp"

        /** YouTube refuses or slows down bigger single requests. */
        private const val MAX_REQUEST = 10L shl 20
        private const val EXTRA_STREAMS = "streams"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_ID = "id"
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_USER_AGENT = "user_agent"
        private const val EXTRA_COOKIE = "cookie"
        private const val EXTRA_REFERER = "referer"
        private const val DONE_OFFSET = 100_000
        private val nextId = AtomicInteger(4_000)

        fun start(
            context: Context,
            url: String,
            name: String,
            mime: String?,
            userAgent: String?,
            cookie: String?,
            referer: String?,
        ) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_MIME, mime)
                .putExtra(EXTRA_USER_AGENT, userAgent)
                .putExtra(EXTRA_COOKIE, cookie)
                .putExtra(EXTRA_REFERER, referer)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Saves the video and sound the user picked from yt-dlp's list. */
        fun startYtDlp(context: Context, job: YtDlpJob) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START_YTDLP)
                .putExtra(EXTRA_JOB, job.toJson())
            ContextCompat.startForegroundService(context, intent)
        }

        /** True for an MP4 that comes in fragments (moof boxes), as streams and YouTube send. */
        fun looksFragmented(file: File): Boolean = runCatching {
            val head = ByteArray(minOf(file.length(), 2L shl 20).toInt())
            RandomAccessFile(file, "r").use { it.readFully(head) }
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" &&
                String(head, Charsets.ISO_8859_1).contains("moof")
        }.getOrDefault(false)

        /** True for MPEG-TS: a sync byte every 188 bytes. */
        fun looksTs(file: File): Boolean = runCatching {
            val head = ByteArray(minOf(file.length(), 188L * 3).toInt())
            RandomAccessFile(file, "r").use { it.readFully(head) }
            head.size >= 188 * 2 && head[0] == 0x47.toByte() && head[188] == 0x47.toByte()
        }.getOrDefault(false)

        /** Saves a streamed video; [streams] are the playlists its page loaded, oldest first. */
        fun startStream(
            context: Context,
            streams: List<String>,
            duration: Double,
            name: String,
            userAgent: String?,
            referer: String?,
            private: Boolean,
        ) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START_STREAM)
                .putExtra(EXTRA_PRIVATE, private)
                .putExtra(EXTRA_STREAMS, streams.toTypedArray())
                .putExtra(EXTRA_DURATION, duration)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_USER_AGENT, userAgent)
                .putExtra(EXTRA_REFERER, referer)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
