package com.sidhu.androidautoglm

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.sidhu.androidautoglm.shizuku.EmbeddedShizukuStarter
import com.sidhu.androidautoglm.shizuku.adb.AdbClient
import com.sidhu.androidautoglm.shizuku.adb.AdbInvalidPairingCodeException
import com.sidhu.androidautoglm.shizuku.adb.AdbKey
import com.sidhu.androidautoglm.shizuku.adb.AdbMdns
import com.sidhu.androidautoglm.shizuku.adb.PreferenceAdbKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 前台服务：后台搜索配对服务，发现后通过通知的「输入配对码」让用户输入。
 * 用户可在系统无线调试界面显示配对码时，下拉通知栏点击「输入配对码」直接输入，无需切回应用。
 */
class ShizukuPairingService : Service() {

    companion object {
        private const val TAG = "ShizukuPairingService"
        const val NOTIFICATION_CHANNEL = "shizuku_pairing"
        private const val NOTIFICATION_ID = 9001
        private const val REMOTE_INPUT_KEY = "pairing_code"
        private const val PORT_EXTRA = "port"
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_REPLY = "reply"
        private const val EXTRA_CONNECT_ONLY = "connect_only"

        fun start(context: Context, connectOnly: Boolean = false) {
            context.startForegroundService(
                Intent(context, ShizukuPairingService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_CONNECT_ONLY, connectOnly)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ShizukuPairingService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var adbMdnsPairing: AdbMdns? = null
    private var adbMdnsConnect: AdbMdns? = null
    private var started = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var scanJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 在 onCreate 中立即调用 startForeground，避免快速点击「重启配对」时因 intent 投递顺序导致超时崩溃
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, createSearchingNotification(R.string.shizuku_pairing_searching), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, createSearchingNotification(R.string.shizuku_pairing_searching))
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed in onCreate", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val connectOnly = intent.getBooleanExtra(EXTRA_CONNECT_ONLY, false)
                // 必须先立即调用 startForeground()，否则会触发 ForegroundServiceDidNotStartInTimeException
                val titleResId = if (connectOnly) R.string.shizuku_searching else R.string.shizuku_pairing_searching
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIFICATION_ID, createSearchingNotification(titleResId), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } else {
                        startForeground(NOTIFICATION_ID, createSearchingNotification(titleResId))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "startForeground failed", e)
                }
                if (connectOnly) {
                    val saved = getSharedPreferences("shizuku_starter", Context.MODE_PRIVATE).getInt("connect_port", -1)
                    if (saved in 1..65535) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
                // 重启配对：先停止已有搜索，延迟后启动，给 NsdManager 释放时间，避免 FAILURE_ALREADY_ACTIVE
                runCatching { stopSearch() }
                scope.launch {
                    kotlinx.coroutines.delay(800)
                    runCatching { startSearch(connectOnly) }
                }
            }
            ACTION_REPLY -> {
                // 必须先立即调用 startForeground()
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIFICATION_ID, createWorkingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } else {
                        startForeground(NOTIFICATION_ID, createWorkingNotification())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "startForeground failed", e)
                }
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REMOTE_INPUT_KEY)?.toString() ?: ""
                val port = intent.getIntExtra(PORT_EXTRA, -1)
                if (port in 1..65535 && code.isNotEmpty()) {
                    doPair(code, port)
                }
            }
            ACTION_STOP -> {
                stopSearch()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // intent 为 null 或未知 action 时，若由 startForegroundService 启动则仍需调用 startForeground
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIFICATION_ID, createSearchingNotification(R.string.shizuku_pairing_searching), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } else {
                        startForeground(NOTIFICATION_ID, createSearchingNotification(R.string.shizuku_pairing_searching))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "startForeground failed", e)
                }
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.notification_channel_shizuku_pairing),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            }
        )
    }

    private fun startSearch(connectOnly: Boolean) {
        if (started) {
            return
        }
        if (connectOnly) {
            val saved = getSharedPreferences("shizuku_starter", Context.MODE_PRIVATE).getInt("connect_port", -1)
            if (saved in 1..65535) {
                return
            }
        }
        started = true
        if (multicastLock == null) {
            runCatching {
                val wifi = getSystemService(WifiManager::class.java)
                multicastLock = wifi.createMulticastLock("shizuku_mdns").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure { Log.w(TAG, "MulticastLock 获取失败: ${it.message}") }
        }
        val onPairingFound: (Int) -> Unit = { port ->
            if (port in 1..65535) {
                getSharedPreferences("shizuku_starter", Context.MODE_PRIVATE).edit().putInt("pairing_port", port).apply()
                // 必须用 startForeground 更新通知，否则 RemoteInput 输入框可能不显示（尤其重启服务后）
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIFICATION_ID, createInputNotification(port), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } else {
                        startForeground(NOTIFICATION_ID, createInputNotification(port))
                    }
                }.onFailure { Log.e(TAG, "startForeground(input) failed", it) }
            }
        }
        val onConnectFound: (Int) -> Unit = { port ->
            if (port in 1..65535) {
                getSharedPreferences("shizuku_starter", Context.MODE_PRIVATE).edit().putInt("connect_port", port).apply()
            }
        }
        adbMdnsConnect = AdbMdns(this, AdbMdns.TLS_CONNECT, onConnectFound)
        adbMdnsConnect?.start()
        if (connectOnly) {
            scanJob?.cancel()
            scanJob = scope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(8000)
                val saved = getSharedPreferences("shizuku_starter", Context.MODE_PRIVATE).getInt("connect_port", -1)
                if (saved in 1..65535) return@launch
                val port = scanAdbPort() ?: return@launch
                if (port in 1..65535) {
                    getSharedPreferences("shizuku_starter", Context.MODE_PRIVATE).edit().putInt("connect_port", port).apply()
                } else {
                    Log.w(TAG, "扫描端口未找到")
                }
            }
        }
        if (!connectOnly) {
            adbMdnsPairing = AdbMdns(this, AdbMdns.TLS_PAIRING, onPairingFound)
            adbMdnsPairing?.start()
        }
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        adbMdnsPairing?.stop()
        adbMdnsPairing = null
        adbMdnsConnect?.stop()
        adbMdnsConnect = null
        scanJob?.cancel()
        scanJob = null
        multicastLock?.let {
            runCatching { it.release() }.onFailure { e -> Log.w(TAG, "MulticastLock 释放失败: ${e.message}") }
        }
        multicastLock = null
    }

    private suspend fun scanAdbPort(): Int? = withContext(Dispatchers.IO) {
        val prefs = getSharedPreferences("shizuku_starter", Context.MODE_PRIVATE)
        val key = runCatching { AdbKey(PreferenceAdbKeyStore(prefs), "shizuku") }.getOrNull()
        if (key == null) return@withContext null
        val candidates = buildList {
            add(5555)
            addAll(30000..60000)
        }
        var found: Int? = null
        for (port in candidates) {
            ensureActive()
            if (!isTcpOpen(port)) continue
            val ok = runCatching {
                AdbClient("127.0.0.1", port, key, 600, 600).use { client ->
                    client.connect()
                }
                true
            }.getOrDefault(false)
            if (ok) {
                found = port
                break
            }
        }
        found
    }

    private fun isTcpOpen(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 150)
                true
            }
        }.getOrDefault(false)
    }

    private fun doPair(code: String, port: Int) {
        scope.launch(Dispatchers.IO) {
            val result = EmbeddedShizukuStarter.pairWithDevice(this@ShizukuPairingService, code, port)
            if (result.isSuccess) {
                getSharedPreferences("shizuku_starter", Context.MODE_PRIVATE).edit().putBoolean("device_paired", true).apply()
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                stopSearch()
                stopForeground(STOP_FOREGROUND_REMOVE)
                val (title, text) = when {
                    result.isSuccess -> getString(R.string.shizuku_pair_success) to getString(R.string.shizuku_pair_success)
                    result.exceptionOrNull() is AdbInvalidPairingCodeException ->
                        getString(R.string.shizuku_pairing_code_wrong) to null
                    result.exceptionOrNull() is ConnectException ->
                        getString(R.string.shizuku_pair_failed) to getString(R.string.shizuku_cannot_connect)
                    else ->
                        getString(R.string.shizuku_pair_failed) to (result.exceptionOrNull()?.message)
                }
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(this@ShizukuPairingService, NOTIFICATION_CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setAutoCancel(true)
                        .build()
                )
                stopSelf()
            }
        }
    }

    private fun createSearchingNotification(titleResId: Int): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ShizukuPairingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(titleResId))
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, getString(R.string.shizuku_pairing_stop), stopIntent)
            .build()
    }

    private fun createInputNotification(port: Int): Notification {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel(getString(R.string.shizuku_pairing_code_label))
            .build()
        val replyIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ShizukuPairingService::class.java)
                .setAction(ACTION_REPLY)
                .putExtra(PORT_EXTRA, port),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_input_add,
            getString(R.string.shizuku_pairing_input_action),
            replyIntent
        ).addRemoteInput(remoteInput).build()

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.shizuku_pairing_found_title))
            .setContentText(getString(R.string.shizuku_pairing_found_desc))
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    private fun createWorkingNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.shizuku_pairing_working))
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearch()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
