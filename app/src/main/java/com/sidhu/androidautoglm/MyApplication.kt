package com.sidhu.androidautoglm

import android.app.Application
import com.sidhu.androidautoglm.action.AppMapper
import com.sidhu.androidautoglm.action.AppMatcher
import com.sidhu.androidautoglm.memory.MemoryManager
import com.sidhu.androidautoglm.utils.SherpaModelManager
import com.sidhu.androidautoglm.utils.ShizukuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // 尽早初始化 Shizuku，确保 Service 启动前 binder 监听已注册
        ShizukuHelper.init(this)
        appScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(2500)
            ShizukuHelper.checkBinderOnStartup(this@MyApplication)
        }
        AppMapper.init(this)
        AppMatcher.init(AppMapper)
        MemoryManager(this).init()
        // 预加载语音模型（若已启用唤醒词），减少唤醒后等待时间
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        if (prefs.getBoolean("wake_up_enabled", false)) {
            appScope.launch(Dispatchers.IO) {
                SherpaModelManager.initModel(this@MyApplication)
            }
        }
    }
}
