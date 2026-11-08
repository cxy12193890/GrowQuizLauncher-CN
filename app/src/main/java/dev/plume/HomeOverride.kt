package dev.plume

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV's launcher has to stay enabled (disabling it boot-loops the TV), so we take the Home key before
 * the system sees it and open our home directly; Google TV never flashes up. Holding Home still opens the
 * Google TV dashboard. Covering Google TV whenever it shows stays as the fallback for anything else that
 * opens it (e.g. Back out of the last app). On Fire TV that cover is the only way: Fire OS keeps Home to itself.
 * On MIUI TV the cover also restores Plume after a restart, when MIUI hands the home role back to its own launcher.
 */
class HomeOverride : AccessibilityService() {
    override fun onServiceConnected() {
        running = this
        DiagLog.event("a11y_bound")
        applyKeyFilter()
        loadHomes()
        // This TV never delivers BOOT_COMPLETED to third-party receivers (TCL's boot manager holds
        // them), so the quiz alarm armed before a power cut is lost at reboot. Arm it again here:
        // an accessibility service is one of the few components the system starts on its own.
        dev.plume.quiz.QuizScheduler.ensureScheduled(this)
        handler.postDelayed(bootCatchUp, BOOT_SETTLE_MS)
        if (!listening) {
            listening = true
            registerReceiver(packageReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            })
        }
    }

    /**
     * The system binds this service a few seconds into a boot. By then the firmware's own launcher
     * is already on screen, and its one window event went by before we were bound — the cover in
     * [onAccessibilityEvent] never fires (this TCL starts com.tcl.cyberui directly, ignoring the
     * default home). Look at what is in front once, shortly after the bind, and take over. A quiz
     * or a forced rest that a power cut interrupted comes back instead of the home screen. Runs
     * only in the first minutes after power-on: a later re-bind (update, revive) must not pull the
     * viewer out of whatever they watch.
     */
    private val bootCatchUp = Runnable {
        if (SystemClock.uptimeMillis() > BOOT_WINDOW_MS) return@Runnable
        val prefs = dev.plume.quiz.QuizPrefs(this)
        val rest = prefs.restGuard && prefs.restUntil > System.currentTimeMillis()
        if (prefs.enabled && (prefs.quizActive || rest)) {
            DiagLog.event("boot_catchup_quiz")
            startActivity(
                Intent(this, dev.plume.quiz.QuizActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
            return@Runnable
        }
        val front = rootInActiveWindow?.packageName?.toString() ?: return@Runnable
        if (front != packageName && watch.coversPackage(front)) {
            DiagLog.event("boot_catchup_home", "", mapOf("front" to front))
            goHome()
        }
    }

    /**
     * Seeing keys costs volume its hold on TVs: Android acts on Volume before any service sees it and the key never
     * repeats. So Smooth volume turns key filtering off, unless remote keys need it (set, or one being learned).
     * Home is then caught by the Google TV window watcher below, which shows Google TV for about a second.
     * Fire OS never passes remote keys to us, so there filtering is always off.
     */
    fun applyKeyFilter() {
        val filter = !fireTv(this) && (!getSharedPreferences("home", MODE_PRIVATE).getBoolean("smooth_volume", false) ||
            Remap.all(this).isNotEmpty() || Remap.learn != null)
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = serviceInfo.apply { flags = if (filter) flags or flag else flags and flag.inv() }
    }

    /** Turned off, from Plume's switch or Android's Accessibility settings: the app mustn't turn it back on. */
    override fun onUnbind(intent: Intent?): Boolean {
        running = null
        DiagLog.event("a11y_unbound")
        handler.removeCallbacks(jump)
        handler.removeCallbacks(bootCatchUp)
        TvWatch.flush(this) // the watch-time bucket goes to disk before the service goes away
        if (listening) {
            listening = false
            unregisterReceiver(packageReceiver)
        }
        getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("home_off", true).apply()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        TvWatch.flush(this)
        super.onDestroy()
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_HOME) return remap(e)
        // Decided on release, by how long it was held: this TV's remote doesn't reliably send key repeats.
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
            if (e.eventTime - e.downTime < LONG_PRESS_MS) goHome() else performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
        return true
    }

    /** Keys the user changed in Plume settings (see [Remap]); any other key goes on to the system untouched. */
    private fun remap(e: KeyEvent): Boolean {
        if (Remap.kept(e)) return false
        val id = Remap.id(e)
        if (id == Remap.pending) { // the key just learned: its repeats and release never reach anyone
            if (e.action == KeyEvent.ACTION_UP) Remap.pending = null
            return true
        }
        Remap.learn?.let { learn ->
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                Remap.pending = id
                learn(e)
            }
            return true
        }
        val m = Remap.lookup(Remap.all(this), e.keyCode, e.scanCode) ?: return false
        // Decided on release, like Home: this TV's remote doesn't reliably send key repeats.
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
            val action = if (e.eventTime - e.downTime >= LONG_PRESS_MS && m.long.isNotEmpty()) m.long else m.short
            if (action.isNotEmpty()) Remap.run(this, action)
        }
        return true
    }

    private val handler = Handler(Looper.getMainLooper())
    private val watch by lazy { HomeWatch(fireTv(this), { googleHomes }, { stock }) }
    private var googleHomes = emptySet<String>()
    private var stock = emptyMap<String, Set<String>>()
    private var listening = false
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = loadHomes()
    }
    private val jump = Runnable { watch.jumped(); goHome() }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        TvWatch.note(this, pkg) // watch-time tracking sees every foreground change first
        val after = watch.opened(pkg, e.className?.toString(), SystemClock.uptimeMillis())
        if (after == HomeWatch.KEEP) return // the waiting jump stays
        handler.removeCallbacks(jump) // any other window opening means Home wasn't where we were going
        after?.let { handler.postDelayed(jump, it) }
    }

    /**
     * Every launcher's home screens, asked of the system when the service starts and when a package changes.
     * Besides the standard Home intent, the vendor query catches launchers that declare only a private home
     * category (Coocaa's HOME.CC and the like), so the cover works on domestic TVs without a hardcoded list.
     * Read again on every package change: a guard bound while the stock launcher was disabled would keep
     * ignoring it after `pm enable` otherwise.
     */
    private fun loadHomes() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val found = packageManager.queryIntentActivities(home, 0).map {
            val a = it.activityInfo
            HomeEntry(a.packageName, a.name, a.targetActivity)
        }
        googleHomes = googleTvHomes(found)
        val vendor = runCatching {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN), PackageManager.GET_RESOLVED_FILTER)
                .filter { it.filter?.categoriesIterator()?.asSequence()?.any(HomeWatch::isVendorHomeCategory) == true }
                .map {
                    val a = it.activityInfo
                    HomeEntry(a.packageName, a.name, a.targetActivity)
                }
        }.getOrDefault(emptyList())
        val resolved = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        stock = stockHomes(found + vendor, resolved)
    }

    // Marked as Home so the setup check can tell it from Plume being opened from the apps list.
    private fun goHome() = startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    override fun onInterrupt() {}

    companion object {
        /** The bound service, so Plume's switch can turn it off without any permission. */
        var running: HomeOverride? = null
            private set
        private const val LONG_PRESS_MS = 500L
        /** The boot catch-up runs only this long after power-on; later binds must not take the screen. */
        private const val BOOT_WINDOW_MS = 3 * 60_000L
        /** Let the firmware's launcher draw first, so the take-over replaces a settled screen. */
        private const val BOOT_SETTLE_MS = 4_000L

        /** Fire OS keeps Amazon's home and ignores the home role; Plume can only cover it (see [HomeWatch]). */
        fun fireTv(c: Context) = c.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }
}
