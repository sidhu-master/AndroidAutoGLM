package com.sidhu.androidautoglm

import android.app.Application
import com.sidhu.androidautoglm.action.AppMapper
import com.sidhu.androidautoglm.action.AppMatcher

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppMapper.init(this)
        AppMatcher.init(AppMapper)
    }
}
