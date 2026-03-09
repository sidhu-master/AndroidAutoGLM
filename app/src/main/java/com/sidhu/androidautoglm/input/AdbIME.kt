package com.sidhu.androidautoglm.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.inputmethodservice.InputMethodService

/**
 * 内置 ADBKeyboard 功能：通过广播接收文本/按键并输入到当前焦点输入框。
 * 与 senzhk/ADBKeyBoard 协议兼容，用户无需单独安装 ADBKeyboard 应用。
 *
 * 使用前需在系统设置中启用本输入法，并在需要输入时切换到「AutoGLM 输入」。
 *
 * 支持的广播：
 * - ADB_INPUT_TEXT --es msg "文本"
 * - ADB_INPUT_B64 --es msg [base64]  (Unicode 支持)
 * - ADB_INPUT_CODE --ei code [keycode] --ei metaState [meta]
 * - ADB_CLEAR_TEXT 清空当前输入框
 */
class AdbIME : InputMethodService() {

    private var receiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        registerReceiver()
    }

    override fun onDestroy() {
        unregisterReceiver()
        super.onDestroy()
    }

    private fun registerReceiver() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                handleBroadcast(intent)
            }
        }
        val filter = IntentFilter().apply {
            addAction(ADB_INPUT_TEXT)
            addAction(ADB_INPUT_B64)
            addAction(ADB_INPUT_CODE)
            addAction(ADB_CLEAR_TEXT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        Log.d(TAG, "AdbIME: BroadcastReceiver registered")
    }

    private fun unregisterReceiver() {
        receiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterReceiver: $e")
            }
            receiver = null
        }
    }

    private fun handleBroadcast(intent: Intent) {
        val ic = currentInputConnection ?: run {
            Log.w(TAG, "AdbIME: no InputConnection (IME 可能尚未连接输入框)，ignore action=${intent.action}")
            return
        }
        when (intent.action) {
            ADB_INPUT_TEXT -> {
                val msg = intent.getStringExtra("msg")
                if (!msg.isNullOrEmpty()) {
                    ic.commitText(msg, 1)
                    Log.d(TAG, "AdbIME: committed text, len=${msg.length}")
                }
            }
            ADB_INPUT_B64 -> {
                val b64 = intent.getStringExtra("msg")
                if (!b64.isNullOrEmpty()) {
                    try {
                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                        val text = String(bytes, Charsets.UTF_8)
                        ic.commitText(text, 1)
                        Log.d(TAG, "AdbIME: committed b64 text, len=${text.length}")
                    } catch (e: Exception) {
                        Log.e(TAG, "AdbIME: b64 decode failed", e)
                    }
                }
            }
            ADB_INPUT_CODE -> {
                val code = intent.getIntExtra("code", -1)
                val metaState = intent.getIntExtra("metaState", 0)
                if (code >= 0) {
                    if (metaState != 0) {
                        ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, metaState, 0, 0, 0, InputDevice.SOURCE_KEYBOARD))
                        ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, metaState, 0, 0, 0, InputDevice.SOURCE_KEYBOARD))
                    } else {
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                    }
                    Log.d(TAG, "AdbIME: sent keycode=$code metaState=$metaState")
                }
            }
            ADB_CLEAR_TEXT -> {
                clearInput(ic)
            }
        }
    }

    /** 清空输入框：删除光标前后的全部文字 */
    private fun clearInput(ic: InputConnection) {
        val beforeLen = ic.getTextBeforeCursor(10000, 0)?.length ?: 0
        val afterLen = ic.getTextAfterCursor(10000, 0)?.length ?: 0
        if (beforeLen > 0 || afterLen > 0) {
            ic.deleteSurroundingText(beforeLen, afterLen)
            Log.d(TAG, "AdbIME: cleared input, before=$beforeLen after=$afterLen")
        }
    }

    override fun onCreateInputView(): android.view.View {
        // 无 UI 键盘，仅通过广播输入；返回最小透明视图以满足系统要求
        return android.view.View(this)
    }

    companion object {
        private const val TAG = "AdbIME"
        const val ADB_INPUT_TEXT = "ADB_INPUT_TEXT"
        const val ADB_INPUT_B64 = "ADB_INPUT_B64"
        const val ADB_INPUT_CODE = "ADB_INPUT_CODE"
        const val ADB_CLEAR_TEXT = "ADB_CLEAR_TEXT"
    }
}
