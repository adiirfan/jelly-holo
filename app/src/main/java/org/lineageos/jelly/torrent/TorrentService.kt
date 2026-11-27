/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.torrent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.lineageos.jelly.R
import org.lineageos.jelly.dao.DownloadDao
import org.lineageos.jelly.database.DownloadDatabase
import org.lineageos.jelly.downloads.DownloadsActivity
import org.lineageos.jelly.downloads.upsertTorrent
import org.lineageos.jelly.model.DownloadItem
import org.lineageos.jelly.model.DownloadKind
import org.lineageos.jelly.model.DownloadState
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs the torrent engine in its own process (`:torrent`), so the whole
 * process can be tied to the VPN without touching the browser's own traffic.
 *
 * Every change of switch or network goes through [decide] and [transition]:
 * the engine is stopped before the network it is tied to changes, and only
 * started again once the process is tied to the right one. Stopping the
 * engine stops everything, including finding people; each torrent comes back
 * as it was (the engine remembers pauses).
 */
class TorrentService : Service() {
    private val cm by lazy { getSystemService(ConnectivityManager::class.java)!! }
    private val nm by lazy { getSystemService(NotificationManager::class.java)!! }
    private val dao: DownloadDao by lazy { DownloadDatabase.get(this).downloadDao() }
    private val prefs by lazy { TorrentPrefs(this) }

    /** Engine control, network changes and the progress loop, one at a time. */
    private val worker = Executors.newSingleThreadScheduledExecutor()

    /** Adding can wait a long time for a magnet's details, so it runs apart. */
    private val adders = Executors.newCachedThreadPool()
    private val adding = ConcurrentHashMap.newKeySet<Long>()

    private val vpns = ConcurrentHashMap<Network, Long>()
    @Volatile private var unmetered = true

    // Only touched on [worker]
    private var lock: Lock = Lock.Off
    private var engineUp = false
    /** Goes up every time the engine starts, so a slow add knows it was cut off. */
    @Volatile private var generation = 0
    private var runningPort = -1
    private var startError: String? = null
    private var stats: List<TorrentStat> = emptyList()
    private var inited = false
    private var idleTicks = 0

    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && vpns.put(network, network.networkHandle) == null) {
                worker.execute(::reevaluate)
            }
        }

        override fun onLost(network: Network) {
            if (vpns.remove(network) != null) worker.execute(::reevaluate)
        }
    }

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val free = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            if (free != unmetered) {
                unmetered = free
                worker.execute(::reevaluate)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground(note())
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            vpnCallback,
        )
        cm.registerDefaultNetworkCallback(defaultCallback)
        worker.scheduleWithFixedDelay(::tick, 1, 1, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android may restart a killed service with no intent: just carry on
        goForeground(note())
        val action = intent?.action
        val rowId = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        worker.execute {
            // The engine may not be running yet (the service had ended): start it before acting
            reevaluate()
            when (action) {
                ACTION_ADD -> addPending()
                ACTION_PAUSE -> engineAction(rowId) { NativeTorrent.pause(it) }
                ACTION_RESUME -> engineAction(rowId) { NativeTorrent.resume(it) }
                ACTION_SELECT -> intent.getIntArrayExtra(EXTRA_FILES)?.let { files ->
                    engineAction(rowId) { NativeTorrent.selectFiles(it, files) }
                }
                ACTION_REMOVE -> remove(rowId, intent.getBooleanExtra(EXTRA_DELETE, false))
                ACTION_PAUSE_ALL -> prefs.torrentsOn = false
            }
            reevaluate()
        }
        return START_STICKY
    }

    /**
     * Android 15 lets this kind of service run about 6 hours a day: stop
     * everything and say why, instead of being killed.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        worker.execute {
            stopEngine()
            lock = Lock.Off
            record {
                torrents().filter { it.state in MOVING }.forEach {
                    update(
                        it.copy(
                            state = DownloadState.PAUSED, downBps = 0, upBps = 0, peers = 0,
                            error = getString(R.string.torrent_time_limit), updated = now(),
                        )
                    )
                }
            }
            quit()
        }
    }

    override fun onDestroy() {
        runCatching { cm.unregisterNetworkCallback(vpnCallback) }
        runCatching { cm.unregisterNetworkCallback(defaultCallback) }
        worker.execute { stopEngine() }
        worker.shutdown()
        runCatching { worker.awaitTermination(10, TimeUnit.SECONDS) }
        adders.shutdownNow()
        super.onDestroy()
    }

    private fun now() = System.currentTimeMillis()

    private fun <T> record(f: suspend DownloadDao.() -> T): T = runBlocking { dao.f() }

    /** Brings the engine in line with the switches and the networks. */
    private fun reevaluate() {
        val vpn = vpns.entries.firstOrNull()
        val next = decide(NetState(prefs.torrentsOn, prefs.vpnOnly, prefs.wifiOnly, vpn?.value, unmetered))
        for (step in transition(lock, next)) {
            when (step) {
                Step.PAUSE_ALL -> stopEngine()
                Step.BIND -> {
                    val handle = (next as Lock.Run).bindTo
                    val network = handle?.let { h -> vpns.entries.firstOrNull { it.value == h }?.key }
                    if (handle != null && network == null) {
                        // The VPN went away meanwhile: the next change will pick it up
                        lock = Lock.WaitVpn
                        return
                    }
                    cm.bindProcessToNetwork(network)
                }
                Step.RESUME_ALL -> startEngine()
            }
        }
        lock = next
        if (engineUp) {
            if (prefs.port != runningPort) {
                // A new port needs a new listener
                stopEngine()
                startEngine()
            } else {
                NativeTorrent.setLimits(prefs.downLimit, prefs.upLimit)
            }
        }
    }

    private fun startEngine() {
        if (engineUp) return
        if (!canSave(this)) {
            startError = getString(R.string.torrent_needs_storage)
            return
        }
        if (!inited) inited = NativeTorrent.init(this)
        val folder = saveFolder()
        val state = File(filesDir, "torrent-state")
        var port = prefs.port
        var ok = NativeTorrent.start(folder.path, state.path, port, prefs.downLimit, prefs.upLimit)
        if (!ok && port != 0) {
            // The chosen port is taken: any port still downloads, only fewer people can reach us
            port = 0
            ok = NativeTorrent.start(folder.path, state.path, 0, prefs.downLimit, prefs.upLimit)
        }
        engineUp = ok
        startError = if (ok) null else NativeTorrent.lastError
        if (!ok) return
        runningPort = prefs.port
        generation++
        applyRemovals()
        addPending()
    }

    private fun stopEngine() {
        if (!engineUp) return
        NativeTorrent.stop()
        engineUp = false
        stats = emptyList()
    }

    /** Hands every torrent asked for to the engine. */
    private fun addPending() {
        if (!engineUp) return
        val gen = generation
        for (row in record { pendingTorrents() }) {
            if (!adding.add(row.id)) continue
            adders.execute {
                try {
                    add(row, gen)
                } finally {
                    adding.remove(row.id)
                }
            }
        }
    }

    private fun add(row: DownloadItem, gen: Int) {
        val source = row.source ?: return
        val answer = JSONObject(
            when {
                source.startsWith("magnet:", ignoreCase = true) -> NativeTorrent.addMagnet(source)
                else -> {
                    val file = File(source)
                    if (!file.exists()) """{"error":"the .torrent file is gone"}"""
                    else NativeTorrent.addBytes(file.readBytes())
                }
            }
        )
        if (answer.has("error")) {
            // Cut off by the engine stopping (VPN gone, switched off): try again next start
            if (gen != generation || !engineUp) return
            record { update(row.copy(state = DownloadState.FAILED, error = answer.getString("error"), updated = now())) }
            return
        }
        val hash = answer.getString("info_hash")
        val name = answer.getString("name")
        record {
            val existing = byInfoHash(hash)
            if (existing != null && existing.id != row.id) {
                // The same torrent again: one row
                delete(row.id)
                update(
                    upsertTorrent(existing, hash, name, now()).copy(
                        state = if (existing.state in SETTLED) DownloadState.QUEUED else existing.state,
                        error = null,
                    )
                )
            } else {
                update(upsertTorrent(row, hash, name, now()).copy(infoHash = hash, engineId = answer.getInt("id")))
            }
        }
        if (!source.startsWith("magnet:", ignoreCase = true)) File(source).delete()
    }

    private fun engineAction(rowId: Long, f: (Int) -> Boolean) {
        if (!engineUp) return
        val hash = record { byId(rowId) }?.infoHash ?: return
        engineIdOf(hash)?.let { f(it) }
    }

    /** Asks the engine itself: the last second's list may be empty right after it starts. */
    private fun engineIdOf(hash: String): Int? = parseStats(NativeTorrent.stats()).firstOrNull { it.infoHash == hash }?.id

    private fun remove(rowId: Long, deleteFiles: Boolean) {
        val row = record { byId(rowId) } ?: return
        val hash = row.infoHash
        when {
            hash == null -> row.source?.takeUnless { it.startsWith("magnet:", true) }?.let { File(it).delete() }
            engineUp -> engineIdOf(hash)?.let { NativeTorrent.remove(it, deleteFiles) }
            // Can't reach the engine now (waiting for the VPN): do it when it starts
            else -> prefs.pendingRemovals = prefs.pendingRemovals + "$hash|${if (deleteFiles) 1 else 0}"
        }
        record { delete(row.id) }
    }

    private fun applyRemovals() {
        val pending = prefs.pendingRemovals
        if (pending.isEmpty()) return
        val now = parseStats(NativeTorrent.stats())
        for (entry in pending) {
            val (hash, delete) = entry.split('|').let { it[0] to (it.getOrNull(1) == "1") }
            now.firstOrNull { it.infoHash == hash }?.let { NativeTorrent.remove(it.id, delete) }
        }
        prefs.pendingRemovals = emptySet()
    }

    /** Every second: engine progress into the page's rows, and the notification. */
    private fun tick() {
        runCatching { tickOnce() }
    }

    private fun tickOnce() {
        val t = now()
        if (engineUp) {
            stats = parseStats(NativeTorrent.stats())
            // The engine treats a full disk as a bad peer and retries forever: stop and say so instead
            val full = lowOnSpace(runCatching { StatFs(saveFolder().path).availableBytes }.getOrDefault(Long.MAX_VALUE))
            for (s in stats) {
                val row = record { byInfoHash(s.infoHash) }
                    ?: record { // The engine has it but the list doesn't (e.g. app data cleared)
                        val r = upsertTorrent(null, s.infoHash, s.name, t)
                        r.copy(id = insert(r))
                    }
                var next = applyStat(row, s, t)
                if (full && s.state == "live" && !s.finished) {
                    NativeTorrent.pause(s.id)
                    next = next.copy(
                        state = DownloadState.PAUSED, downBps = 0, upBps = 0, peers = 0,
                        error = getString(R.string.torrent_storage_full),
                    )
                }
                if (next.state == DownloadState.SHARING &&
                    shouldStopSharing(s.uploaded, s.total, prefs.ratio, prefs.keepSharing)
                ) {
                    NativeTorrent.pause(s.id)
                    next = next.copy(state = DownloadState.DONE, downBps = 0, upBps = 0, peers = 0)
                }
                if (s.finished && row.uri == null && scanStarted.add(s.infoHash)) scan(s)
                scanned[s.infoHash]?.let { next = next.copy(uri = it) }
                if (next.copy(updated = row.updated) != row) record { update(next) }
            }
        } else {
            for (row in record { torrents() }) {
                if (row.infoHash == null) continue
                val next = waitingRow(row, lock, t)
                if (next != row) record { update(next) }
            }
        }
        val rows = record { torrents() }
        val active = rows.count { it.state in MOVING || it.state == DownloadState.QUEUED } + adding.size
        idleTicks = if (active == 0) idleTicks + 1 else 0
        if (idleTicks >= IDLE_TICKS) quit() else nm.notify(NOTE_ID, note(rows))
    }

    /** Where finished single-file torrents can be opened from. */
    private val scanned = ConcurrentHashMap<String, String>()
    private val scanStarted = ConcurrentHashMap.newKeySet<String>()

    /** Lets other apps see the finished files (Android 10 and older don't do it by themselves). */
    private fun scan(s: TorrentStat) {
        val folder = saveFolder()
        val paths = s.files.filter { it.selected }.map { f ->
            if (s.files.size == 1) File(folder, f.name) else File(File(folder, s.name), f.name)
        }.filter { it.exists() }.map { it.path }
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(this, paths.toTypedArray(), null) { _, uri ->
            if (uri != null && s.files.size == 1) scanned[s.infoHash] = uri.toString()
        }
    }

    private fun quit() {
        stopEngine()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        startForeground(NOTE_ID, n, type)
    }

    private fun note(rows: List<DownloadItem> = emptyList()): Notification {
        val moving = rows.filter { it.state in MOVING }
        val text = when {
            startError != null && lock is Lock.Run -> startError!!
            lock == Lock.WaitVpn -> getString(R.string.torrent_waiting_vpn)
            lock == Lock.WaitWifi -> getString(R.string.torrent_waiting_wifi)
            else -> getString(
                R.string.torrent_note,
                moving.size,
                Formatter.formatShortFileSize(this, moving.sumOf { it.downBps }),
                Formatter.formatShortFileSize(this, moving.sumOf { it.upBps }),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pauseAll = PendingIntent.getService(
            this, 1,
            Intent(this, TorrentService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.torrent_note_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setColor(ContextCompat.getColor(this, R.color.holo_blue))
            .setContentIntent(open)
            .addAction(0, getString(R.string.torrent_pause_all), pauseAll)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        // The browser's download channel, so one setting covers both
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.download_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    companion object {
        private const val CHANNEL = "downloads_visible"
        private const val NOTE_ID = 0x70727265
        /** Seconds with nothing to do before the service ends itself. */
        private const val IDLE_TICKS = 5

        const val ACTION_ADD = "torrent_add"
        const val ACTION_PAUSE = "torrent_pause"
        const val ACTION_RESUME = "torrent_resume"
        const val ACTION_REMOVE = "torrent_remove"
        const val ACTION_SELECT = "torrent_select"
        const val ACTION_PAUSE_ALL = "torrent_pause_all"
        const val ACTION_SETTINGS_CHANGED = "torrent_settings"
        const val EXTRA_ID = "id"
        const val EXTRA_DELETE = "delete"
        const val EXTRA_FILES = "files"

        private val MOVING = setOf(
            DownloadState.RUNNING, DownloadState.SHARING, DownloadState.WAITING_VPN, DownloadState.WAITING_WIFI,
        )
        private val SETTLED = setOf(DownloadState.DONE, DownloadState.FAILED, DownloadState.CANCELLED, DownloadState.PAUSED)

        fun saveFolder(): File =
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Torrents")

        /** Android 10 and older need the storage permission to write into Downloads. */
        fun canSave(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED

        private fun send(context: Context, intent: Intent) =
            ContextCompat.startForegroundService(context, intent.setClass(context, TorrentService::class.java))

        /** Starts a magnet link. Returns the row's id; the name comes later from the engine. */
        suspend fun addMagnet(context: Context, uri: String, name: String): Long {
            val id = addRow(context, name, uri)
            send(context, Intent(ACTION_ADD))
            return id
        }

        /** Starts a .torrent file's contents. */
        suspend fun addBytes(context: Context, bytes: ByteArray, name: String): Long {
            val dir = File(context.filesDir, "torrent-pending").apply { mkdirs() }
            val file = File(dir, UUID.randomUUID().toString() + ".torrent").apply { writeBytes(bytes) }
            val id = addRow(context, name, file.path)
            send(context, Intent(ACTION_ADD))
            return id
        }

        private suspend fun addRow(context: Context, name: String, source: String): Long {
            val t = System.currentTimeMillis()
            return DownloadDatabase.get(context).downloadDao().insert(
                DownloadItem(
                    kind = DownloadKind.TORRENT, name = name, state = DownloadState.QUEUED,
                    source = source, created = t, updated = t,
                )
            )
        }

        fun pause(context: Context, rowId: Long) = send(context, Intent(ACTION_PAUSE).putExtra(EXTRA_ID, rowId))
        fun resume(context: Context, rowId: Long) = send(context, Intent(ACTION_RESUME).putExtra(EXTRA_ID, rowId))
        fun remove(context: Context, rowId: Long, deleteFiles: Boolean) =
            send(context, Intent(ACTION_REMOVE).putExtra(EXTRA_ID, rowId).putExtra(EXTRA_DELETE, deleteFiles))
        fun selectFiles(context: Context, rowId: Long, files: IntArray) =
            send(context, Intent(ACTION_SELECT).putExtra(EXTRA_ID, rowId).putExtra(EXTRA_FILES, files))
        fun settingsChanged(context: Context) = send(context, Intent(ACTION_SETTINGS_CHANGED))

        /** After a restart of the phone or the browser: carry on with unfinished torrents. */
        suspend fun resumeIfNeeded(context: Context) {
            if (!TorrentPrefs(context).torrentsOn) return
            val rows = DownloadDatabase.get(context).downloadDao().torrents()
            val unfinished = rows.any {
                it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING ||
                    it.state == DownloadState.SHARING || it.state == DownloadState.WAITING_VPN ||
                    it.state == DownloadState.WAITING_WIFI
            }
            if (unfinished) send(context, Intent(ACTION_SETTINGS_CHANGED))
        }
    }
}
