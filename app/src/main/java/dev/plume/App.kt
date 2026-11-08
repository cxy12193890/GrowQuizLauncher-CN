package dev.plume

import android.app.Application

/** Process-wide setup that must run before any activity or receiver: crash and diagnostics recording. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        CrashLog.install(this)
        DiagLog.event("app_start", "", mapOf("vendor" to Vendor.current))
    }
}
