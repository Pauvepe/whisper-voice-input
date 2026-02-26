package com.pauvepe.whispervoice

import android.app.Application

class App : Application() {
    lateinit var whisperEngine: WhisperEngine

    override fun onCreate() {
        super.onCreate()
        whisperEngine = WhisperEngine(this)
    }
}
