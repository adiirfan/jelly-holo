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
                thread(name = "Download$id") { runStream(id, job, streams, duration, userAgent, referer) }
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
            failed(id, job.name, getString(refusalText(e.reason)))
        } catch (e: Exception) {
            Log.w(AdBlock.TAG, "stream download failed: ${job.name}", e)
            item?.let { runCatching { contentResolver.delete(it, null, null) } }
            failed(id, job.name, getString(R.string.download_failed, e.message ?: e.javaClass.simpleName))
        } finally {
            dir.deleteRecursively()
            ended(id)
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

        /** Saves a streamed video; [streams] are the playlists its page loaded, oldest first. */
        fun startStream(
            context: Context,
            streams: List<String>,
            duration: Double,
            name: String,
            userAgent: String?,
            referer: String?,
        ) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START_STREAM)
                .putExtra(EXTRA_STREAMS, streams.toTypedArray())
                .putExtra(EXTRA_DURATION, duration)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_USER_AGENT, userAgent)
                .putExtra(EXTRA_REFERER, referer)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
