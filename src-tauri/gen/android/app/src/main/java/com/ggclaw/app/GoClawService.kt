package com.ggclaw.app

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

class GoClawService : Service() {

    companion object {
        const val TAG = "GoClawService"
        
        // 加载 native 库
        init {
            try {
                System.loadLibrary("goclaw")
                Log.d(TAG, "GoClaw library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load GoClaw library: ${e.message}")
            }
        }
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): GoClawService = this@GoClawService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "GoClawService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "GoClawService started")
        
        // 初始化 goclaw
        val config = intent?.getStringExtra("config") ?: "{}"
        val result = initGoClaw(config)
        Log.d(TAG, "GoClaw init result: $result")
        
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "GoClawService destroyed")
        stopGoClaw()
    }

    // Native 方法声明
    external fun initGoClaw(config: String): String
    external fun startGoClaw(): String
    external fun stopGoClaw()
    external fun sendMessage(message: String): String
    external fun getStatus(): String
    external fun freeString(str: String)

    // 包装方法
    fun initialize(config: String): String {
        return initGoClaw(config)
    }

    fun start(): String {
        return startGoClaw()
    }

    fun stop() {
        stopGoClaw()
    }

    fun send(msg: String): String {
        return sendMessage(msg)
    }

    fun status(): String {
        return getStatus()
    }
}
