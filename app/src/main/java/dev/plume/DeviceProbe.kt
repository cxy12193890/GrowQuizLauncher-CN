package dev.plume

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Small on-device permission checks used by the setup prompt and the password-protected LAN console.
 * This helper does not build or transmit a device fingerprint or compatibility report.
 */
object DeviceProbe {

    /** Whether our accessibility service is enabled in Android settings (bound or not). */
    fun a11yEnabled(context: Context): Boolean {
        val on = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val me = ComponentName(context, HomeOverride::class.java)
        return on.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Whether Android allows exact alarms on this TV. */
    fun exactAlarm(context: Context): Boolean {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
    }
}
