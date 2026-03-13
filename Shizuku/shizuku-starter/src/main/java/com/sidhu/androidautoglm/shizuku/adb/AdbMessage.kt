package com.sidhu.androidautoglm.shizuku.adb

import com.sidhu.androidautoglm.shizuku.adb.AdbProtocol.A_AUTH
import com.sidhu.androidautoglm.shizuku.adb.AdbProtocol.A_CLSE
import com.sidhu.androidautoglm.shizuku.adb.AdbProtocol.A_CNXN
import com.sidhu.androidautoglm.shizuku.adb.AdbProtocol.A_OKAY
import com.sidhu.androidautoglm.shizuku.adb.AdbProtocol.A_OPEN
import com.sidhu.androidautoglm.shizuku.adb.AdbProtocol.A_STLS
import com.sidhu.androidautoglm.shizuku.adb.AdbProtocol.A_SYNC
import com.sidhu.androidautoglm.shizuku.adb.AdbProtocol.A_WRTE
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val data_length: Int,
    val data_crc32: Int,
    val magic: Int,
    val data: ByteArray?
) {
    constructor(command: Int, arg0: Int, arg1: Int, data: String) : this(
        command, arg0, arg1, "$data\u0000".toByteArray()
    )

    constructor(command: Int, arg0: Int, arg1: Int, data: ByteArray?) : this(
        command, arg0, arg1, data?.size ?: 0, crc32(data), (command.toLong() xor 0xFFFFFFFF).toInt(), data
    )

    fun validate(): Boolean {
        if (command != magic xor -0x1) return false
        if (data_length != 0 && crc32(data) != data_crc32) return false
        return true
    }

    fun validateOrThrow() {
        if (!validate()) throw IllegalArgumentException("bad message ${toStringShort()}")
    }

    fun toByteArray(): ByteArray {
        val length = HEADER_LENGTH + (data?.size ?: 0)
        return ByteBuffer.allocate(length).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(data_length)
            putInt(data_crc32)
            putInt(magic)
            data?.let { put(it) }
        }.array()
    }

    fun toStringShort(): String {
        val commandString = when (command) {
            A_SYNC -> "A_SYNC"
            A_CNXN -> "A_CNXN"
            A_AUTH -> "A_AUTH"
            A_OPEN -> "A_OPEN"
            A_OKAY -> "A_OKAY"
            A_CLSE -> "A_CLSE"
            A_WRTE -> "A_WRTE"
            A_STLS -> "A_STLS"
            else -> command.toString()
        }
        return "command=$commandString, arg0=$arg0, arg1=$arg1, data_length=$data_length"
    }

    companion object {
        const val HEADER_LENGTH = 24
        private fun crc32(data: ByteArray?): Int {
            if (data == null) return 0
            var res = 0
            for (b in data) res += if (b >= 0) b.toInt() else b.toInt() + 256
            return res
        }
    }
}
