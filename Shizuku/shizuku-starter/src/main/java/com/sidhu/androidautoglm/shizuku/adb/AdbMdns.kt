package com.sidhu.androidautoglm.shizuku.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

/**
 * mDNS 发现无线调试服务端口。
 * 需 Android 11+ 且已启用无线调试。
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context,
    internal val serviceType: String,
    private val onPortFound: (Int) -> Unit
) {
    private var registered = false
    private var running = false
    private var serviceName: String? = null
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private val maxRetries = 4

    fun start() {
        if (running) {
            return
        }
        running = true
        retryCount = 0
        doStart()
    }

    private fun doStart() {
        if (!running) return
        if (registered) return
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "discoverServices failed for $serviceType", e)
            running = false
        }
    }

    private val retryRunnable = Runnable {
        running = true
        doStart()
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(retryRunnable)
        if (registered) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "stopServiceDiscovery failed for listener", e)
            }
        }
    }

    private fun onDiscoveryStart() {
        registered = true
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        nsdManager.resolveService(info, ResolveListener(this))
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) onPortFound(-1)
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        if (!running) return
        val port = resolvedService.port
        val host = resolvedService.host
        val hostAddr = host?.hostAddress ?: "null"
        val hostOk = runCatching {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .any { nif ->
                    nif.inetAddresses
                        .asSequence()
                        .any { hostAddr == it.hostAddress }
                }
        }.getOrElse { false }
        val portInUse = isPortAvailable(port)
        if (hostOk && portInUse) {
            serviceName = resolvedService.serviceName
            onPortFound(port)
        } else {
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket().use {
                it.bind(InetSocketAddress("127.0.0.1", port), 1)
                false
            }
        } catch (e: IOException) {
            true
        }
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            val msg = when (errorCode) {
                0 -> "FAILURE_INTERNAL(内部错误)"
                3 -> "FAILURE_ALREADY_ACTIVE(已有发现在进行)"
                4 -> "FAILURE_MAX_LIMIT(达到发现数量上限)"
                else -> "未知"
            }
            Log.e(TAG, "[$serviceType] 发现失败: errorCode=$errorCode $msg")
            if (errorCode == 3 && adbMdns.retryCount < adbMdns.maxRetries) {
                adbMdns.running = false
                adbMdns.registered = false
                adbMdns.retryCount++
                val delayMs = 1500L * adbMdns.retryCount
                Log.w(TAG, "[$serviceType] ${delayMs}ms 后重试 (${adbMdns.retryCount}/${adbMdns.maxRetries})")
                adbMdns.handler.postDelayed(adbMdns.retryRunnable, delayMs)
            } else {
                adbMdns.running = false
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            adbMdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(private val adbMdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
            val msg = when (errorCode) {
                0 -> "FAILURE_INTERNAL"
                2 -> "FAILURE_NOT_RUNNING"
                3 -> "FAILURE_ALREADY_ACTIVE"
                4 -> "FAILURE_MAX_LIMIT"
                else -> "未知"
            }
            Log.w(TAG, "[${adbMdns.serviceType}] 解析失败: ${nsdServiceInfo.serviceName}, errorCode=$errorCode $msg")
        }
        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        private const val TAG = "AdbMdns"
    }
}
