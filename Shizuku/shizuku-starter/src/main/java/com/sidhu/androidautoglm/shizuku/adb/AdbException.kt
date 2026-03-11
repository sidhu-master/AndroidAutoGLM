package com.sidhu.androidautoglm.shizuku.adb

open class AdbException : Exception {
    constructor(message: String, cause: Throwable?) : super(message, cause)
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
    constructor() : super()
}

class AdbKeyException(cause: Throwable) : AdbException(cause)

class AdbInvalidPairingCodeException : AdbException()
