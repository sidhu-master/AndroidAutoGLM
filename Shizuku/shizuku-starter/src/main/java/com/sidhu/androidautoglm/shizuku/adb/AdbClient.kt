package com.sidhu.androidautoglm.shizuku.adb

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

class AdbClient(
    private val host: String,
    private val port: Int,
    private val key: AdbKey,
    private val connectTimeoutMs: Int = 2000,
    private val readTimeoutMs: Int = 2000
) : Closeable {

    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var useTls = false
    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    fun connect() {
        socket = Socket()
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = readTimeoutMs
        socket.tcpNoDelay = true
        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        write(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "host::")

        var message = read()
        if (message.command == AdbProtocol.A_STLS) {
            write(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0)

            val sslContext = key.sslContext
            tlsSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tlsSocket.startHandshake()

            tlsInputStream = DataInputStream(tlsSocket.inputStream)
            tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
            useTls = true

            message = read()
        } else if (message.command == AdbProtocol.A_AUTH) {
            if (message.arg0 != AdbProtocol.ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")
            write(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_SIGNATURE, 0, key.sign(message.data))

            message = read()
            if (message.command != AdbProtocol.A_CNXN) {
                write(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }

        if (message.command != AdbProtocol.A_CNXN) error("not A_CNXN")
    }

    fun shellCommand(command: String, listener: ((ByteArray) -> Unit)?) {
        val localId = 1
        write(AdbProtocol.A_OPEN, localId, 0, "shell:$command")

        var message = read()
        when (message.command) {
            AdbProtocol.A_OKAY -> {
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    if (message.command == AdbProtocol.A_WRTE) {
                        if (message.data_length > 0) listener?.invoke(message.data!!)
                        write(AdbProtocol.A_OKAY, localId, remoteId)
                    } else if (message.command == AdbProtocol.A_CLSE) {
                        write(AdbProtocol.A_CLSE, localId, remoteId)
                        break
                    } else error("not A_WRTE or A_CLSE")
                }
            }
            AdbProtocol.A_CLSE -> write(AdbProtocol.A_CLSE, localId, message.arg0)
            else -> error("not A_OKAY or A_CLSE")
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) =
        write(AdbMessage(command, arg0, arg1, data))

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) =
        write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
    }

    private fun read(): AdbMessage {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        inputStream.readFully(buffer.array(), 0, 24)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        val data = if (dataLength >= 0) {
            ByteArray(dataLength).also { inputStream.readFully(it, 0, dataLength) }
        } else null

        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        return message
    }

    override fun close() {
        try { plainInputStream.close() } catch (_: Throwable) {}
        try { plainOutputStream.close() } catch (_: Throwable) {}
        try { socket.close() } catch (_: Exception) {}
        if (useTls) {
            try { tlsInputStream.close() } catch (_: Throwable) {}
            try { tlsOutputStream.close() } catch (_: Throwable) {}
            try { tlsSocket.close() } catch (_: Exception) {}
        }
    }
}
