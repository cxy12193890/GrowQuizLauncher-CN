package dev.plume

import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import org.json.JSONObject

/**
 * A snapshot of everything a compatibility report needs to reproduce a problem on a TV we do not
 * own: the build fingerprint, the Android level, which of our guards the system actually granted
 * (accessibility bind, overlay, exact alarms, secure-settings), the speech engines on board and
 * the screen it draws on. Collected fresh for every report — a vendor update can change any of it.
 */
object DeviceProbe {

    fun collect(context: Context): JSONObject {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        val dm = context.resources.displayMetrics
        return JSONObject()
            .put("vendor", Vendor.current)
            .put("brand", Build.BRAND)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("product", Build.PRODUCT)
            .put("hardware", Build.HARDWARE)
            .put("fingerprint", Build.FINGERPRINT)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("abis", Build.SUPPORTED_ABIS.joinToString(","))
            .put("appVersion", info.versionName)
            .put("appVersionCode", info.longVersionCode)
            .put("screen", "${dm.widthPixels}x${dm.heightPixels}")
            .put("densityDpi", dm.densityDpi)
            .put("ttsEngines", ttsEngines(context))
            .put("ttsDefault", Settings.Secure.getString(context.contentResolver, "tts_default_synth") ?: "")
            .put("a11yEnabled", a11yEnabled(context))
            .put("a11yBound", HomeOverride.running != null)
            .put("overlay", Settings.canDrawOverlays(context))
            .put("exactAlarm", exactAlarm(context))
            .put("secureSettings", secureSettings(context))
            .put("deviceOwner", deviceOwner(context))
            .put("defaultHome", defaultHome(context))
    }

    /** Speech engines that answer the standard TTS intent, comma separated ("" = none on board). */
    private fun ttsEngines(context: Context): String {
        val intent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
        return context.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }.distinct().sorted().joinToString(",")
    }

    /** Whether our accessibility service sits in the system's enabled list (bound or not). */
    private fun a11yEnabled(context: Context): Boolean {
        val on = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val me = ComponentName(context, HomeOverride::class.java)
        return on.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun exactAlarm(context: Context): Boolean {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
    }

    private fun secureSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun deviceOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true

    private fun defaultHome(context: Context): String {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName ?: ""
    }
}
