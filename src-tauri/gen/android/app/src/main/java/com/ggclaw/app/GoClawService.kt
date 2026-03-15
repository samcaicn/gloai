package com.ggclaw.app

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

class GoClawService : Service() {

    companion object {
        const val TAG = "GoClawService"
        
        private var libraryLoaded = false
        
        init {
            try {
                System.loadLibrary("goclaw")
                libraryLoaded = true
                Log.d(TAG, "GoClaw library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load GoClaw library: ${e.message}")
                libraryLoaded = false
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error loading GoClaw library: ${e.message}", e)
                libraryLoaded = false
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
        Log.d(TAG, "GoClawService created, library loaded: $libraryLoaded")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "GoClawService started")
        
        if (!libraryLoaded) {
            Log.e(TAG, "Cannot initialize GoClaw: library not loaded")
            return START_NOT_STICKY
        }
        
        try {
            val config = intent?.getStringExtra("config") ?: "{}"
            val result = initGoClaw(config)
            Log.d(TAG, "GoClaw init result: $result")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GoClaw: ${e.message}", e)
        }
        
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "GoClawService destroyed")
        if (libraryLoaded) {
            try {
                stopGoClaw()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping GoClaw: ${e.message}", e)
            }
        }
    }

    external fun initGoClaw(config: String): String
    external fun startGoClaw(): String
    external fun stopGoClaw()
    external fun sendMessage(message: String): String
    external fun getStatus(): String
    external fun freeString(str: String)

    fun initialize(config: String): String {
        if (!libraryLoaded) {
            return "{\"error\": \"library not loaded\"}"
        }
        return try {
            initGoClaw(config)
        } catch (e: Exception) {
            Log.e(TAG, "initialize error: ${e.message}", e)
            "{\"error\": \"${e.message}\"}"
        }
    }

    fun start(): String {
        if (!libraryLoaded) {
            return "{\"error\": \"library not loaded\"}"
        }
        return try {
            startGoClaw()
        } catch (e: Exception) {
            Log.e(TAG, "start error: ${e.message}", e)
            "{\"error\": \"${e.message}\"}"
        }
    }

    fun stop() {
        if (!libraryLoaded) {
            return
        }
        try {
            stopGoClaw()
        } catch (e: Exception) {
            Log.e(TAG, "stop error: ${e.message}", e)
        }
    }

    fun send(msg: String): String {
        if (!libraryLoaded) {
            return "{\"error\": \"library not loaded\"}"
        }
        return try {
            sendMessage(msg)
        } catch (e: Exception) {
            Log.e(TAG, "send error: ${e.message}", e)
            "{\"error\": \"${e.message}\"}"
        }
    }

    fun status(): String {
        if (!libraryLoaded) {
            return "{\"running\": false, \"error\": \"library not loaded\"}"
        }
        return try {
            getStatus()
        } catch (e: Exception) {
            Log.e(TAG, "status error: ${e.message}", e)
            "{\"running\": false, \"error\": \"${e.message}\"}"
        }
    }
}
