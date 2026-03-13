package moe.shizuku.manager.adb

/**
 * JNI 桥接类。libadb.so 的 JNI_OnLoad 会向此类注册 native 方法。
 * 必须使用此包名和类名，因为 libadb.so 从 Shizuku manager 构建，硬编码了 "moe/shizuku/manager/adb/PairingContext"。
 */
class PairingContext private constructor(private val nativePtr: Long) {

    val msg: ByteArray
        get() = nativeMsg(nativePtr)

    fun initCipher(theirMsg: ByteArray) = nativeInitCipher(nativePtr, theirMsg)
    fun encrypt(`in`: ByteArray) = nativeEncrypt(nativePtr, `in`)
    fun decrypt(`in`: ByteArray) = nativeDecrypt(nativePtr, `in`)
    fun destroy() = nativeDestroy(nativePtr)

    private external fun nativeMsg(nativePtr: Long): ByteArray
    private external fun nativeInitCipher(nativePtr: Long, theirMsg: ByteArray): Boolean
    private external fun nativeEncrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?
    private external fun nativeDecrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?
    private external fun nativeDestroy(nativePtr: Long)

    companion object {
        fun create(password: ByteArray): PairingContext? {
            val nativePtr = nativeConstructor(true, password)
            return if (nativePtr != 0L) PairingContext(nativePtr) else null
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}
