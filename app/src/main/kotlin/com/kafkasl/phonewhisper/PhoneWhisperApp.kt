package com.kafkasl.phonewhisper

import android.app.Application

class PhoneWhisperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
    }
}
