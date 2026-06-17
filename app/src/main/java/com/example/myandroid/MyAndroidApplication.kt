package com.example.myandroid

import android.app.Application

class MyAndroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NativeWebSocketManager.initialize(this)
    }
}
