package com.sidhu.androidautoglm

import android.app.Application
import com.sidhu.androidautoglm.action.AppMapper
import com.sidhu.androidautoglm.action.AppMatcher
import com.sidhu.androidautoglm.utils.SherpaModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        AppMapper.init(this)
        AppMatcher.init(AppMapper)
        // 预加载语音模型（若已启用唤醒词），减少唤醒后等待时间
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        if (prefs.getBoolean("wake_up_enabled", false)) {
            appScope.launch(Dispatchers.IO) {
                SherpaModelManager.initModel(this@MyApplication)
            }
        }
    }
}
