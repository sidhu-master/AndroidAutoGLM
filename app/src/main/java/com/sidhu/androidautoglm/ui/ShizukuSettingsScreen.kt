package com.sidhu.androidautoglm.ui

import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.delay
import com.sidhu.androidautoglm.R
import com.sidhu.androidautoglm.ShizukuPairingService
import com.sidhu.androidautoglm.utils.ShizukuHelper
import com.sidhu.androidautoglm.shizuku.EmbeddedShizukuStarter
import com.sidhu.androidautoglm.shizuku.adb.AdbClient
import com.sidhu.androidautoglm.shizuku.adb.AdbKey
import com.sidhu.androidautoglm.shizuku.adb.AdbMdns
import com.sidhu.androidautoglm.shizuku.adb.PreferenceAdbKeyStore
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private const val SHIZUKU_PREFS = "shizuku_starter"
private const val KEY_DEVICE_PAIRED = "device_paired"
private const val KEY_CONNECT_PORT = "connect_port"

private fun isDevicePaired(context: Context): Boolean =
    context.getSharedPreferences(SHIZUKU_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DEVICE_PAIRED, false)

private fun clearDevicePaired(context: Context) {
    context.getSharedPreferences(SHIZUKU_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DEVICE_PAIRED, false).apply()
}

private suspend fun validateAdbKey(context: Context, port: Int): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val prefs = context.getSharedPreferences(SHIZUKU_PREFS, Context.MODE_PRIVATE)
        val key = AdbKey(PreferenceAdbKeyStore(prefs), "shizuku")
        AdbClient("127.0.0.1", port, key).use { client ->
            client.connect()
        }
        true
    }.getOrDefault(false)
}

private fun getSavedConnectPort(context: Context): Int {
    val p = context.getSharedPreferences(SHIZUKU_PREFS, Context.MODE_PRIVATE)
    return p.getInt(KEY_CONNECT_PORT, -1).takeIf { it in 1..65535 } ?: -1
}

private fun getAdbTcpPortFromSystem(context: Context): Int {
    val propPort = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val getInt = clazz.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
        val servicePort = getInt.invoke(null, "service.adb.tcp.port", -1) as Int
        val persistPort = getInt.invoke(null, "persist.adb.tcp.port", -1) as Int
        listOf(servicePort, persistPort).firstOrNull { it in 1..65535 } ?: -1
    }.getOrElse {
        -1
    }
    if (propPort in 1..65535) return propPort
    val cr = context.contentResolver
    val globalPort = runCatching {
        listOf("adb_wifi_port", "adb_port", "adb_tcp_port").firstNotNullOfOrNull { key ->
            runCatching { Settings.Global.getInt(cr, key, -1) }.getOrNull()?.takeIf { it in 1..65535 }
        } ?: -1
    }.getOrElse {
        -1
    }
    if (globalPort in 1..65535) return globalPort
    val securePort = runCatching {
        listOf("adb_wifi_port", "adb_port", "adb_tcp_port").firstNotNullOfOrNull { key ->
            runCatching { Settings.Secure.getInt(cr, key, -1) }.getOrNull()?.takeIf { it in 1..65535 }
        } ?: -1
    }.getOrElse {
        -1
    }
    return securePort.takeIf { it in 1..65535 } ?: -1
}

private fun saveConnectPort(context: Context, port: Int) {
    if (port in 1..65535) {
        context.getSharedPreferences(SHIZUKU_PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CONNECT_PORT, port).apply()
    }
}

private suspend fun scanAdbPort(context: Context): Int? = withContext(Dispatchers.IO) {
    val prefs = context.getSharedPreferences("shizuku_starter", Context.MODE_PRIVATE)
    val key = runCatching { AdbKey(PreferenceAdbKeyStore(prefs), "shizuku") }.getOrNull() ?: return@withContext null
    for (port in listOf(5555) + (30000..60000)) {
        val isOpen = runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 150)
                true
            }
        }.getOrDefault(false)
        if (!isOpen) continue
        val ok = runCatching {
            AdbClient("127.0.0.1", port, key, 600, 600).use { client -> client.connect() }
            true
        }.getOrDefault(false)
        if (ok) return@withContext port
    }
    null
}

/** 与 Shizuku 一致：若有 WRITE_SECURE_SETTINGS 权限则启用无线调试，促系统广播 mDNS */
private fun enableAdbWifiIfHasPermission(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
    runCatching {
        val cr = context.contentResolver
        Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
        Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShizukuSettingsScreen(
    onBack: () -> Unit,
    onShizukuStarted: () -> Unit = {},
    onOpenDocumentation: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var discoveredPort by remember { mutableStateOf(getSavedConnectPort(context).coerceAtLeast(-1)) }
    var isConnecting by remember { mutableStateOf(false) }
    var isPaired by remember { mutableStateOf(isDevicePaired(context)) }
    var pendingStart by remember { mutableStateOf(false) }
    var nearbyPermissionGranted by remember { mutableStateOf(true) }
    var isRunning by remember { mutableStateOf(ShizukuHelper.isShizukuAvailable()) }
    var connectFailed by remember { mutableStateOf(ShizukuHelper.isConnectFailed(context)) }
    var lastPairCheckAt by remember { mutableStateOf(0L) }
    var isRestartingPairing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val effectiveRunning = isRunning && isPaired && !isConnecting && !connectFailed

    fun setConnectFailed(value: Boolean) {
        connectFailed = value
        ShizukuHelper.setConnectFailed(context, value)
    }

    fun syncPairState() {
        val prefPaired = isDevicePaired(context)
        if (prefPaired) {
            val systemPort = getAdbTcpPortFromSystem(context)
            if (discoveredPort < 0) {
                val saved = getSavedConnectPort(context)
                discoveredPort = if (saved > 0) saved else systemPort
            }
        } else if (discoveredPort >= 0) {
            discoveredPort = -1
        }
        if (!prefPaired) {
            setConnectFailed(false)
        }
        if (isPaired != prefPaired) {
            isPaired = prefPaired
        }
        connectFailed = ShizukuHelper.isConnectFailed(context)
    }

    val nearbyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        nearbyPermissionGranted = granted
        if (!granted) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.shizuku_nearby_permission_required), duration = SnackbarDuration.Long) }
        }
    }

    val startWithPort: (Int) -> Unit = { port ->
        scope.launch {
            isConnecting = true
            setConnectFailed(false)
            val result = EmbeddedShizukuStarter.startViaWirelessAdb(
                context, "127.0.0.1", port
            )
            isConnecting = false
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { output ->
                        setConnectFailed(false)
                        ShizukuHelper.requestPermissionOnce()
                        val intent = Intent(context, ShizukuBlackWindowActivity::class.java)
                            .putExtra(ShizukuBlackWindowActivity.EXTRA_OUTPUT, output)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.shizuku_start_success), duration = SnackbarDuration.Short) }
                        onShizukuStarted()
                    },
                    onFailure = { e ->
                        setConnectFailed(true)
                        scope.launch { snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.shizuku_pair_failed), duration = SnackbarDuration.Long) }
                    }
                )
            }
        }
    }

    val onConnectPortFound: (Int) -> Unit = { port ->
        if (port in 1..65535) {
            discoveredPort = port
            saveConnectPort(context, port)
            if (pendingStart && isPaired && !isConnecting) {
                pendingStart = false
                startWithPort(port)
            }
        }
    }
    // 与官方 Shizuku 一致：仅 _adb-tls-connect._tcp。TLS_PAIRING 由 ShizukuPairingService 统一发现
    val adbMdnsConnect = remember { AdbMdns(context, AdbMdns.TLS_CONNECT, onConnectPortFound) }

    DisposableEffect(Unit) {
        if (!Shizuku.isPreV11()) {
            val binderListener = Shizuku.OnBinderReceivedListener {
                isRunning = true
                setConnectFailed(false)
            }
            val deadListener = Shizuku.OnBinderDeadListener { isRunning = false }
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addBinderDeadListener(deadListener)
            onDispose {
                Shizuku.removeBinderReceivedListener(binderListener)
                Shizuku.removeBinderDeadListener(deadListener)
            }
        } else {
            onDispose { }
        }
    }

    LaunchedEffect(Unit) {
        isPaired = isDevicePaired(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            nearbyPermissionGranted = context.checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            if (!nearbyPermissionGranted) {
                nearbyPermissionLauncher.launch(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        // 与 Shizuku 一致：若有 WRITE_SECURE_SETTINGS 则启用无线调试，促系统广播 mDNS
        enableAdbWifiIfHasPermission(context)
        var port = getSavedConnectPort(context)
        if (port < 0) {
            port = getAdbTcpPortFromSystem(context)
        }
        if (port > 0 && discoveredPort < 0) discoveredPort = port
        if (isPaired && port < 0) {
            if (nearbyPermissionGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                ShizukuPairingService.start(context, connectOnly = true)
            }
        }
        // 启用 adb_wifi 后系统可能延迟设置端口，后台轮询（与 mDNS 并行）
        if (port < 0) {
            scope.launch {
                repeat(15) {
                    delay(2000)
                    val p = getAdbTcpPortFromSystem(context)
                    if (p > 0) {
                        discoveredPort = p
                        saveConnectPort(context, p)
                        return@launch
                    }
                }
            }
        }
        if (!isPaired) {
            ShizukuPairingService.start(context)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            syncPairState()
            val prefPaired = isDevicePaired(context)
            val systemPort = getAdbTcpPortFromSystem(context)
            val now = System.currentTimeMillis()
            if (prefPaired && systemPort > 0 && now - lastPairCheckAt > 5000L) {
                lastPairCheckAt = now
                val valid = validateAdbKey(context, systemPort)
                if (!valid) {
                    clearDevicePaired(context)
                    if (isPaired) isPaired = false
                    if (discoveredPort >= 0) discoveredPort = -1
                }
            }
            delay(2000)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                syncPairState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(discoveredPort) {
        if (discoveredPort >= 0) {
            isPaired = isDevicePaired(context)
            return@LaunchedEffect
        }
        while (true) { delay(30_000) }
    }

    LaunchedEffect(Unit) {
        delay(5 * 60 * 1000L)
        adbMdnsConnect.stop()
        ShizukuPairingService.stop(context)
    }

    DisposableEffect(Unit) {
        onDispose {
            adbMdnsConnect.stop()
            ShizukuPairingService.stop(context)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shizuku_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 状态卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.shizuku_status_label),
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (isConnecting) {
                            AssistChip(
                                onClick = { },
                                label = { Text(stringResource(R.string.shizuku_status_connecting)) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            )
                        } else if (connectFailed) {
                            AssistChip(
                                onClick = { },
                                label = { Text(stringResource(R.string.shizuku_status_failed)) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            )
                        } else if (effectiveRunning) {
                            AssistChip(
                                onClick = { },
                                label = { Text(stringResource(R.string.shizuku_status_running)) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            )
                        } else {
                            AssistChip(
                                onClick = { },
                                label = { Text(stringResource(R.string.shizuku_status_stopped)) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            )
                        }
                    }
                }
            }

            // 配对状态
            Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (isPaired) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.shizuku_pair_btn),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                AssistChip(
                                    onClick = { },
                                    label = { Text(stringResource(R.string.shizuku_already_paired)) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    ),
                                    enabled = false
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.shizuku_pair_btn),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                TextButton(
                                    onClick = {
                                        if (isRestartingPairing) return@TextButton
                                        isRestartingPairing = true
                                        // 先停止再延迟启动，给 NsdManager 足够时间释放旧发现，避免 FAILURE_ALREADY_ACTIVE
                                        ShizukuPairingService.stop(context)
                                        scope.launch {
                                            delay(1200)
                                            ShizukuPairingService.start(context)
                                            snackbarHostState.showSnackbar(context.getString(R.string.shizuku_pairing_started_toast), duration = SnackbarDuration.Short)
                                            delay(2000)
                                            isRestartingPairing = false
                                        }
                                    },
                                    enabled = !isRestartingPairing
                                ) {
                                    Text(stringResource(R.string.shizuku_restart_pairing_service))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.shizuku_pairing_message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = true
                            ) {
                                Text(stringResource(R.string.shizuku_open_developer_options))
                            }
                        }
                    }
                }

            // 启动 Shizuku 按钮
            Button(
                    onClick = {
                        if (!isPaired) {
                            ShizukuPairingService.start(context)
                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.shizuku_pairing_started_toast), duration = SnackbarDuration.Short) }
                            return@Button
                        }
                        val systemPort = getAdbTcpPortFromSystem(context)
                        Log.d("ShizukuPort", "systemPort=$systemPort")
                        if (systemPort > 0) {
                            startWithPort(systemPort)
                        } else {
                            scope.launch {
                                isConnecting = true
                                val scannedPort = scanAdbPort(context)
                                isConnecting = false
                                if (scannedPort != null) {
                                    startWithPort(scannedPort)
                                } else {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nearbyPermissionGranted) {
                                        nearbyPermissionLauncher.launch(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                                        return@launch
                                    }
                                    pendingStart = true
                                    ShizukuPairingService.start(context, connectOnly = true)
                                    snackbarHostState.showSnackbar(context.getString(R.string.shizuku_searching), duration = SnackbarDuration.Long)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isPaired && !isConnecting && !effectiveRunning
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Icon(
                        if (effectiveRunning) Icons.Filled.Check else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(if (effectiveRunning) R.string.shizuku_started else R.string.shizuku_start_btn))
                }

            Spacer(modifier = Modifier.height(24.dp))
            TextButton(
                onClick = onOpenDocumentation,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("shizuku配对指南", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
