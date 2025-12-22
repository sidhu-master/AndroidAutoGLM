package com.sidhu.androidautoglm

import android.app.Application
import com.sidhu.androidautoglm.action.AppMapper

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppMapper.init(this)
    }
}
