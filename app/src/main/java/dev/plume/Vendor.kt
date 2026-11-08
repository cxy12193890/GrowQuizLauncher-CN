package dev.plume

import android.os.Build
import java.util.Locale

/**
 * Which maker built this TV, decided at run time from Build.BRAND / Build.MANUFACTURER.
 *
 * A workaround that only one firmware needs (e.g. the accessibility revive verified on TCL) must
 * check [isTcl] / [is] before it runs, so a fix for one brand never changes another brand's
 * behaviour. Detection and problem logging stay brand-blind: the daily report tells us what a
 * firmware we have never seen does wrong, and only then does a gated workaround join the code.
 */
object Vendor {
    const val TCL = "tcl"
    const val XIAOMI = "xiaomi"
    const val HISENSE = "hisense"
    const val SKYWORTH = "skyworth"
    const val HUAWEI = "huawei"
    const val AMAZON = "amazon"
    const val SONY = "sony"
    const val PHILIPS = "philips"
    const val KONKA = "konka"
    const val CHANGHONG = "changhong"
    const val HAIER = "haier"
    const val FFALCON = "ffalcon"
    const val OTHER = "other"

    /** Pure matching, so unit tests need no Android. Brand and manufacturer together, lowercased. */
    fun identify(brand: String?, manufacturer: String?): String {
        val s = "${brand.orEmpty()} ${manufacturer.orEmpty()}".lowercase(Locale.US)
        return when {
            s.contains("ffalcon") -> FFALCON // TCL's own sub-brand, but its installer differs: keep it apart
            s.contains("tcl") -> TCL
            s.contains("xiaomi") || s.contains("redmi") -> XIAOMI
            s.contains("hisense") || s.contains("vidaa") -> HISENSE
            s.contains("skyworth") || s.contains("coocaa") -> SKYWORTH
            s.contains("huawei") || s.contains("honor") -> HUAWEI
            s.contains("amazon") -> AMAZON
            s.contains("sony") -> SONY
            s.contains("philips") -> PHILIPS
            s.contains("konka") -> KONKA
            s.contains("changhong") -> CHANGHONG
            s.contains("haier") -> HAIER
            else -> OTHER
        }
    }

    /** The maker of the device this process runs on. */
    val current: String get() = identify(Build.BRAND, Build.MANUFACTURER)

    fun matches(vendor: String): Boolean = current == vendor
    fun isTcl(): Boolean = matches(TCL)
}
