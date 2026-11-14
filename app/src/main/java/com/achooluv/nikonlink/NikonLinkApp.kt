package com.achooluv.nikonlink

import android.app.Application

class NikonLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.attach(this)

        // Keep a trace of crashes in the same file the diagnostics screen reads.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                Diagnostics.log("崩溃：${error.javaClass.simpleName}: ${error.message}")
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
