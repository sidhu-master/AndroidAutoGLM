package com.sidhu.androidautoglm.shizuku.adb

import android.net.ssl.SSLSockets
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import javax.net.ssl.SSLException
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket
import moe.shizuku.manager.adb.PairingContext

private const val TAG = "AdbPairClient"

private const val kCurrentKeyHeaderVersion = 1.toByte()
private const val kMinSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxPeerInfoSize = 8192
private const val kMaxPayloadSize = kMaxPeerInfoSize * 2

private const val kExportedKeyLabel = "adb-label\u0000"
private const val kExportedKeySize = 64

private const val kPairingPacketHeaderSize = 6

private class PeerInfo(
    val type: Byte,
    data: ByteArray
) {
    val data = ByteArray(kMaxPeerInfoSize - 1)

    init {
        data.copyInto(this.data, 0, 0, data.size.coerceAtMost(kMaxPeerInfoSize - 1))
    }

    enum class Type(val value: Byte) {
        ADB_RSA_PUB_KEY(0.toByte()),
        ADB_DEVICE_GUID(0.toByte()),
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(type)
            put(data)
        }
        Log.d(TAG, "write PeerInfo ${toStringShort()}")
    }

    fun toStringShort(): String = "type=$type, data=${data.contentToString()}"

    companion object {
        fun readFrom(buffer: ByteBuffer): PeerInfo {
            val type = buffer.get()
            val data = ByteArray(kMaxPeerInfoSize - 1)
            buffer.get(data)
            return PeerInfo(type, data)
        }
    }
}

private class PairingPacketHeader(
    val version: Byte,
    val type: Byte,
    val payload: Int
) {
    enum class Type(val value: Byte) {
        SPAKE2_MSG(0.toByte()),
        PEER_INFO(1.toByte())
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(version)
            put(type)
            putInt(payload)
        }
        Log.d(TAG, "write PairingPacketHeader ${toStringShort()}")
    }

    fun toStringShort(): String = "version=${version.toInt()}, type=${type.toInt()}, payload=$payload"

    companion object {
        fun readFrom(buffer: ByteBuffer): PairingPacketHeader? {
            val version = buffer.get()
            val type = buffer.get()
            val payload = buffer.int
            if (version < kMinSupportedKeyHeaderVersion || version > kMaxSupportedKeyHeaderVersion) {
                Log.e(TAG, "PairingPacketHeader version mismatch")
                return null
            }
            if (type != Type.SPAKE2_MSG.value && type != Type.PEER_INFO.value) {
                Log.e(TAG, "Unknown PairingPacket type=$type")
                return null
            }
            if (payload <= 0 || payload > kMaxPayloadSize) {
                Log.e(TAG, "header payload not within safe size")
                return null
            }
            return PairingPacketHeader(version, type, payload)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairCode: String,
    private val key: AdbKey
) : Closeable {

    private enum class State { Ready, ExchangingMsgs, ExchangingPeerInfo, Stopped }

    private lateinit var socket: Socket
    private lateinit var inputStream: DataInputStream
    private lateinit var outputStream: DataOutputStream
    private val peerInfo = PeerInfo(PeerInfo.Type.ADB_RSA_PUB_KEY.value, key.adbPublicKey)
    private lateinit var pairingContext: PairingContext
    private var state = State.Ready

    fun start(): Boolean {
        setupTlsConnection()
        state = State.ExchangingMsgs
        if (!doExchangeMsgs()) {
            state = State.Stopped
            return false
        }
        state = State.ExchangingPeerInfo
        if (!doExchangePeerInfo()) {
            state = State.Stopped
            return false
        }
        state = State.Stopped
        return true
    }

    private fun setupTlsConnection() {
        socket = Socket(host, port)
        socket.tcpNoDelay = true
        val sslContext = key.sslContext
        val sslSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        sslSocket.startHandshake()
        Log.d(TAG, "Handshake succeeded.")
        inputStream = DataInputStream(sslSocket.inputStream)
        outputStream = DataOutputStream(sslSocket.outputStream)
        val pairCodeBytes = pairCode.toByteArray()
        val keyMaterial = exportKeyingMaterial(sslSocket, kExportedKeyLabel, kExportedKeySize)
        val passwordBytes = ByteArray(pairCode.length + keyMaterial.size)
        pairCodeBytes.copyInto(passwordBytes)
        keyMaterial.copyInto(passwordBytes, pairCodeBytes.size)
        val ctx = PairingContext.create(passwordBytes)
        checkNotNull(ctx) { "Unable to create PairingContext." }
        pairingContext = ctx
    }

    private fun createHeader(type: PairingPacketHeader.Type, payloadSize: Int) =
        PairingPacketHeader(kCurrentKeyHeaderVersion, type.value, payloadSize)

    private fun readHeader(): PairingPacketHeader? {
        val bytes = ByteArray(kPairingPacketHeaderSize)
        inputStream.readFully(bytes)
        return PairingPacketHeader.readFrom(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN))
    }

    private fun writeHeader(header: PairingPacketHeader, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(kPairingPacketHeaderSize).order(ByteOrder.BIG_ENDIAN)
        header.writeTo(buffer)
        outputStream.write(buffer.array())
        outputStream.write(payload)
    }

    private fun doExchangeMsgs(): Boolean {
        val msg = pairingContext.msg
        writeHeader(createHeader(PairingPacketHeader.Type.SPAKE2_MSG, msg.size), msg)
        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.Type.SPAKE2_MSG.value) return false
        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)
        return pairingContext.initCipher(theirMessage)
    }

    private fun doExchangePeerInfo(): Boolean {
        val buf = ByteBuffer.allocate(kMaxPeerInfoSize).order(ByteOrder.BIG_ENDIAN)
        peerInfo.writeTo(buf)
        val outbuf = pairingContext.encrypt(buf.array()) ?: return false
        writeHeader(createHeader(PairingPacketHeader.Type.PEER_INFO, outbuf.size), outbuf)
        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.Type.PEER_INFO.value) return false
        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)
        val decrypted = pairingContext.decrypt(theirMessage) ?: throw AdbInvalidPairingCodeException()
        if (decrypted.size != kMaxPeerInfoSize) return false
        return true
    }

    override fun close() {
        try { inputStream.close() } catch (_: Throwable) {}
        try { outputStream.close() } catch (_: Throwable) {}
        try { socket.close() } catch (_: Exception) {}
        if (state != State.Ready) pairingContext.destroy()
    }

    companion object {
        private var loaded = false

        private fun exportKeyingMaterial(sslSocket: SSLSocket, label: String, size: Int): ByteArray {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return SSLSockets.exportKeyingMaterial(sslSocket, label, null, size)
                    ?: throw SSLException("Failed to export keying material")
            }
            val conscrypt = Class.forName("com.android.org.conscrypt.Conscrypt")
            val method = conscrypt.getMethod("exportKeyingMaterial", SSLSocket::class.java, String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType)
            return method.invoke(null, sslSocket, label, null, size) as ByteArray
        }

        init {
            try {
                System.loadLibrary("adb")
                loaded = true
            } catch (_: UnsatisfiedLinkError) {
                Log.w(TAG, "libadb not loaded, pairing unavailable")
            }
        }

        @JvmStatic
        fun available(): Boolean = loaded
    }
}
