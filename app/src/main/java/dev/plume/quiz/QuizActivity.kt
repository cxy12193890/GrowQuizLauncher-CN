package dev.plume.quiz

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.plume.Qr

/** Full-screen, remote-first quiz. A wrong answer advances to a different question. */
class QuizActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var questionText: TextView
    private lateinit var progressText: TextView
    private lateinit var feedbackText: TextView
    private lateinit var answerButtons: List<Button>
    private var bank: QuestionBank? = null
    private var current: Question? = null
    private val completedIds = mutableSetOf<String>()
    private val sessionTarget by lazy { QuizPrefs(this).questionsPerSession }
    private var solved = false
    private var changing = false
    private var lockActive = false
    private lateinit var answerGrid: GridLayout
    private var restPanel: LinearLayout? = null // admin address + QR, visible only while resting

    // GrowQuiz: every size below is drawn for a 1280x720 dp canvas. Smaller screens scale the whole
    // page down by the same factor (paddings, text, button heights), so the question, the four answers
    // and the feedback line all fit without scrolling even on a low-resolution box.
    private var uiScale = 1f

    // GrowQuiz: the growth badge (rank, stage, points) sits above the question and updates after every answer.
    private var ledger: QuizLedger? = null
    private lateinit var rootLayout: FrameLayout
    private lateinit var levelBadge: TextView
    private lateinit var levelScore: TextView

    // GrowQuiz: answers cannot be blurted. The buttons respond only after MIN_THINK_MS of reading time.
    private var thinkReadyAt = 0L
    private var thinkLoop: Runnable? = null

    // GrowQuiz: too many wrong answers in a row mean a forced rest, so blind guessing never pays off.
    private var restLoop: Runnable? = null

    // GrowQuiz: while a question or a rest is up, a watchdog brings the quiz back to the front every
    // second if anything else stole it (Netflix-style remote keys, notifications, other apps).
    private var inForeground = false

    // GrowQuiz: parents can switch the watchdog off from the web console, and they can see proof that it
    // works. [snapPendingAt] is set when a comeback is on its way; if the page is in front again a few
    // seconds later, the comeback worked and the time is kept for the console.
    private var snapPendingAt = 0L
    private var snapPrefs: QuizPrefs? = null

    // GrowQuiz: consecutive comeback attempts that did not bring the page back. A few in a row mean
    // this firmware keeps the quiz behind something else — the fact parents call "答题不在最前端".
    private var snapMisses = 0
    private var snapLossNoted = false

    private val guardLoop: Runnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || solved) return
            if (!inForeground) {
                comeBack()
                if (snapPrefs?.snapBack != false && ++snapMisses == 10 && !snapLossNoted) {
                    // Ten seconds without regaining the front: report once per episode, not per tick.
                    snapLossNoted = true
                    dev.plume.DiagLog.event("foreground_lost")
                }
            }
            ui.postDelayed(this, GUARD_POLL_MS)
        }
    }

    /**
     * Brings this page back over whatever took the screen. Android 10 and newer drop an activity start
     * from the background, so this works in two cases only: while the app still has a window on screen
     * (the moment it gets covered, see [onPause]) and when the app holds "display over other apps" (see
     * the manifest). Nothing here needs the accessibility service.
     */
    private fun comeBack() {
        if (snapPrefs?.snapBack == false) return // the parents turned it off in the web console
        val interactive = getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true
        if (!interactive) return // the screen is off: the child is not looking at anything
        try {
            startActivity(
                Intent(this@QuizActivity, QuizActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
            snapPendingAt = System.currentTimeMillis()
        } catch (e: Exception) {
            android.util.Log.w("GrowQuiz", "guard relaunch failed", e)
            dev.plume.DiagLog.event("snapback_fail", e.message ?: e.javaClass.simpleName)
        }
    }

    /** The page is in front again: a comeback that just started has worked. Recorded for the console. */
    private fun noteSnapBack() {
        val started = snapPendingAt
        if (started == 0L) return
        snapPendingAt = 0L
        if (System.currentTimeMillis() - started > SNAP_OK_MS) return // too late to be our doing
        snapMisses = 0
        snapLossNoted = false
        dev.plume.DiagLog.event("snapback_ok")
        snapPrefs?.apply {
            snapOkAt = System.currentTimeMillis()
            snapOkCount = snapOkCount + 1
        }
    }

    // GrowQuiz: the question is read aloud for children who cannot read yet, then repeated every 20 seconds.
    // The engine itself lives in QuizVoice and is warmed when the desktop comes up, so a question is
    // spoken at once instead of waiting for a model to load.
    private var ttsNoted = false // the "no TTS engine" toast shows once
    private var ttsRetries = 0 // short retries while the engine is still loading, capped by TTS_RETRY_LIMIT
    private val speakLoop: Runnable = object : Runnable {
        override fun run() {
            if (QuizVoice.isUnusable()) {
                ttsMissing() // no engine on this TV: say so once and stop trying
                return
            }
            if (!QuizVoice.isReady()) {
                // Still loading: try again shortly. Waiting for the 20 s repeat would leave the first
                // question silent, which is what "读题有延迟" looked like.
                if (ttsRetries++ < TTS_RETRY_LIMIT) ui.postDelayed(this, TTS_RETRY_MS)
                return
            }
            speakQuestion()
            ui.postDelayed(this, READ_REPEAT_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuizVoice.warm(this) // already warm from the desktop; a no-op when it is
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(createContent())
        immersive()
        holdSound() // stop the video that is playing before the first question is read out
        snapPrefs = QuizPrefs(applicationContext) // the console may switch the watchdog off mid-question
        // A question or a rest is open from now on: a power cut leaves this flag set, and the
        // launcher brings the page back after the next power-on (see MainActivity.onResume).
        snapPrefs?.let { prefs ->
            if (prefs.quizActive) completedIds.addAll(prefs.completedSessionQuestions)
            else prefs.completedSessionQuestions = emptySet()
            prefs.quizActive = true
        }
        // GrowQuiz: pairs with the alarm's alarm_fired; a fired alarm without this event soon after
        // means the firmware dropped the pop-up (see QuizAlarmReceiver).
        dev.plume.DiagLog.event("quiz_open")
        // Proof the pop-up made it onto the screen; the alarm audit compares against this stamp.
        snapPrefs?.lastQuizOpenAt = System.currentTimeMillis()
        ledger = QuizLedger(applicationContext)
        refreshLevel()
        if (QuizDeviceOwner.configure(this)) {
            startLockTask()
            lockActive = true
        }
        Thread {
            val loaded = QuestionBank(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                bank = loaded
                showNextQuestion()
            }
        }.start()
    }

    /** No TTS engine (or no usable language) on this device: say so once, instead of reading nothing in silence. */
    private fun ttsMissing() {
        if (isFinishing || ttsNoted) return
        if (!QuizPrefs(this).voice) return // narration is off anyway: no engine toast would only confuse
        ttsNoted = true
        dev.plume.DiagLog.event("tts_missing", QuizVoice.failureReason() ?: "")
        Toast.makeText(this, "本机没有可用的语音（TTS）引擎，无法朗读题目", Toast.LENGTH_LONG).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!solved && current == null && !changing) bank?.let { showNextQuestion() }
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        noteSnapBack() // a comeback that was on its way has arrived: that is the proof the console shows
        ui.removeCallbacks(guardLoop) // one watchdog, started here, re-posted by itself
        ui.postDelayed(guardLoop, GUARD_POLL_MS)
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
        // Back at once, without waiting for the next tick of the loop: at this instant the page still has
        // a window on screen, which is the one case Android still allows a start from the background. It
        // beats the loop by up to a second on TVs without "display over other apps".
        if (!isFinishing && !solved) comeBack()
    }

    private fun createContent(): View {
        val dm = resources.displayMetrics
        uiScale = minOf(dm.widthPixels / dm.density / 1280f, dm.heightPixels / dm.density / 720f, 1f)
            .coerceAtLeast(0.5f)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(16, 42, 67)) }
        rootLayout = root
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(52), dp(28), dp(52), dp(24))
        }
        val levelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        levelBadge = TextView(this).apply {
            textSize = 16f * uiScale
            setTextColor(Color.rgb(16, 42, 67))
            setPadding(dp(16), dp(5), dp(16), dp(5))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat() }
        }
        levelScore = text("积分 --", 15f, Color.rgb(184, 199, 217))
        levelRow.addView(levelBadge)
        levelRow.addView(levelScore, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(12)
        })
        progressText = text("成长问答 · 正在准备题目", 18f, Color.rgb(255, 209, 102)).apply {
            gravity = Gravity.CENTER
        }
        questionText = text("请稍候…", 32f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            minHeight = dp(118)
            maxLines = 4
        }
        feedbackText = text("用遥控器方向键选择，按确认键作答", 18f, Color.rgb(184, 199, 217)).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
        }
        answerGrid = GridLayout(this).apply {
            columnCount = 2
            rowCount = 2
        }
        answerButtons = List(4) { index ->
            Button(this).apply {
                textSize = 21f * uiScale
                setTextColor(Color.rgb(16, 42, 67))
                isAllCaps = false
                gravity = Gravity.CENTER
                minHeight = dp(88)
                background = answerBackground()
                setOnClickListener { answer(index) }
                setOnFocusChangeListener { view, focused ->
                    view.animate().scaleX(if (focused) 1.025f else 1f).scaleY(if (focused) 1.025f else 1f).setDuration(100).start()
                }
                answerGrid.addView(this, GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(10), dp(10), dp(10), dp(10))
                })
            }
        }
        // GrowQuiz: while the child rests, the parents see where to go to lift the rest early.
        restPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(text("家长打开手机管理后台，可提前解除休息：", 16f, Color.rgb(184, 199, 217)))
            val adminUrl = dev.plume.web.WebAdminServer.url()
            if (dev.plume.web.WebAdminServer.hasLanUrl()) {
                addView(ImageView(this@QuizActivity).apply {
                    try { setImageBitmap(Qr.bitmap(adminUrl, dp(140))) } catch (e: Exception) {}
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                })
            }
            addView(text(adminUrl, 18f, Color.rgb(255, 209, 102)).apply {
                setPadding(0, dp(10), 0, 0)
            })
        }
        content.addView(levelRow)
        content.addView(progressText)
        content.addView(questionText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.85f))
        content.addView(restPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(answerGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.35f))
        content.addView(feedbackText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun showNextQuestion() {
        if (restRemaining() > 0L) {
            showRestScreen()
            return
        }
        val quiz = bank ?: return
        changing = false
        answerGrid.visibility = View.VISIBLE
        restPanel?.visibility = View.GONE
        val prefs = QuizPrefs(this)
        val next = quiz.nextFiltered(prefs.age, prefs.categories, prefs.grade, prefs.difficulty,
            current?.id, completedIds, sessionTarget > 1, prefs.term)
        if (next == null) {
            current = null
            answerGrid.visibility = View.GONE
            restPanel?.visibility = View.VISIBLE
            progressText.text = "当前设置下题目不足"
            questionText.text = "请家长在手机控制台补充题库或调整筛选条件"
            feedbackText.text = "调整后会自动重新检查"
            ui.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    quiz.reload()
                    showNextQuestion()
                }
            }, 5_000L)
            return
        }
        current = next
        val tag = if (prefs.verifyPending) "验证题 · " else ""
        progressText.text = "$tag${next.category} · 已答对 ${completedIds.size}/$sessionTarget 道"
        questionText.text = next.prompt
        feedbackText.setTextColor(Color.rgb(184, 199, 217))
        answerButtons.forEachIndexed { index, button ->
            button.text = "${'A' + index}  ${next.options[index]}"
            button.isEnabled = true
            button.backgroundTintList = null
        }
        answerButtons.first().requestFocus()
        startThinkWindow()
        startReading()
    }

    /**
     * The buttons stay focusable for the remote, but [answer] ignores presses until the configured
     * think time has passed. A countdown in the feedback line tells the child why.
     */
    private fun startThinkWindow() {
        thinkLoop?.let { ui.removeCallbacks(it) }
        val seconds = QuizPrefs(this).thinkSeconds
        thinkReadyAt = System.currentTimeMillis() + seconds * 1_000L
        if (seconds <= 0) {
            feedbackText.text = "请选择正确答案"
            return
        }
        val loop = object : Runnable {
            override fun run() {
                if (isFinishing || solved) return
                val left = thinkReadyAt - System.currentTimeMillis()
                if (left <= 0L) {
                    if (!changing) feedbackText.text = "请选择正确答案"
                    return
                }
                feedbackText.text = "请认真${if (voiceAppliesTo(current)) "听题" else "读题"}和思考，${(left + 999) / 1000} 秒后可以作答"
                ui.postDelayed(this, 500L)
            }
        }
        thinkLoop = loop
        loop.run()
    }

    /** Milliseconds of enforced rest still owed; 0 when none. Side effect: clears an expired deadline. */
    private fun restRemaining(): Long {
        val prefs = QuizPrefs(this)
        if (!prefs.restGuard) return 0L // the parents switched the enforced rest off: never gate on it
        val until = prefs.restUntil
        if (until <= 0L) return 0L
        val left = until - System.currentTimeMillis()
        if (left <= 0L) {
            prefs.restUntil = 0L
            return 0L
        }
        return left
    }

    /** Full-screen rest: no question, no buttons, a countdown, and no way out except the parents' console. */
    private fun showRestScreen() {
        changing = true
        stopReading()
        thinkLoop?.let { ui.removeCallbacks(it) }
        answerGrid.visibility = View.GONE
        restPanel?.visibility = View.VISIBLE
        progressText.text = "休息一下"
        if (QuizPrefs(this).voice) QuizVoice.speak("连续答错啦，休息一会再来答题")
        val loop = object : Runnable {
            override fun run() {
                val left = restRemaining()
                if (left <= 0L) {
                    if (!isFinishing) showNextQuestion()
                    return
                }
                val totalSec = (left + 999) / 1000
                questionText.text = "连续答错啦，休息 ${totalSec / 60} 分 ${totalSec % 60} 秒后再答题"
                feedbackText.text = "休息期间不能作答，休息结束自动回到答题"
                feedbackText.setTextColor(Color.rgb(184, 199, 217))
                ui.postDelayed(this, 1_000L)
            }
        }
        restLoop = loop
        loop.run()
    }

    /** Reads the current question and its options aloud, then keeps repeating every 20 seconds. */
    private fun startReading() {
        // No stop() first: the engine would then have to restart its synthesis thread, which is what made
        // the second question wait after a wrong answer. The stem is sent with QUEUE_FLUSH and cuts the old
        // sentence by itself.
        ui.removeCallbacks(speakLoop)
        ttsRetries = 0
        if (!QuizPrefs(this).voice) return // the parents turned narration off in the web console
        if (!voiceAppliesTo(current)) return // the older questions are read from the screen, not the speaker
        speakLoop.run()
    }

    private fun stopReading() {
        ui.removeCallbacks(speakLoop)
        QuizVoice.stop()
    }

    private fun speakQuestion() {
        val q = current ?: return
        if (!voiceAppliesTo(q)) return
        if (!QuizVoice.isReady()) {
            if (QuizVoice.isUnusable()) ttsMissing()
            return
        }
        // The stem goes out first, the options are queued behind it: the voice starts while the rest is
        // still being synthesised, so the question is heard at once. An option of one character, one
        // number or one letter is left out - spoken aloud it hands the answer over.
        QuizVoice.speak(SpeechText.forStem(q.prompt), flush = true)
        QuizVoice.speak(SpeechText.forOptions(q.options), flush = false)
    }

    /**
     * Narration is for the children who cannot read yet. From 11 up a question is read from the screen:
     * hearing it spoken makes the work easier than the age group is meant to be.
     */
    private fun voiceAppliesTo(question: Question?): Boolean = question != null && question.age <= VOICE_MAX_AGE

    private fun answer(index: Int) {
        val question = current ?: return
        if (changing || solved) return
        val now = System.currentTimeMillis()
        if (now < thinkReadyAt) {
            // The think window is still closed: no penalty, just a nudge to slow down.
            feedbackText.text = "再想一想，${(thinkReadyAt - now + 999) / 1000} 秒后可以作答"
            feedbackText.setTextColor(Color.rgb(255, 209, 102))
            return
        }
        changing = true
        stopReading() // the child answered; the next question (or the exit) restarts narration as needed
        thinkLoop?.let { ui.removeCallbacks(it) }
        answerButtons.forEach { it.isEnabled = false }
        val prefs = QuizPrefs(this)
        val snap = QuizRules.isSnapAnswer(now, thinkReadyAt)
        val correct = QuizRules.isCorrect(question, index)
        // Every graded answer goes into the growth ledger: score, totals, and the per-question wrong memory.
        val recorded = ledger?.record(question, correct, snap, nowMs = now)
        if (recorded != null) refreshLevel(recorded.score)
        if (correct) {
            bank?.registerCorrect(question.id) // mastered questions retire themselves after enough correct answers
            val stageUp = recorded != null && recorded.tierAfter > recorded.tierBefore
            if (stageUp) celebrateLevelUp(recorded)
            if (!prefs.verifyPending && QuizRules.needsVerification(prefs.snapWrongs, snap)) {
                // Two snap wrongs then a snap correct smells like a lucky guess: prove it with one more question.
                prefs.verifyPending = true
                answerButtons[index].backgroundTintList = ColorStateList.valueOf(Color.rgb(46, 158, 107))
                feedbackText.text = "答得真快！再来一道题验证一下"
                feedbackText.setTextColor(Color.rgb(255, 209, 102))
                ui.postDelayed({ if (!isFinishing) { changing = false; showNextQuestion() } }, 1_300L)
                return
            }
            completedIds.add(question.id)
            prefs.completedSessionQuestions = completedIds
            prefs.wrongStreak = 0
            prefs.snapWrongs = 0
            prefs.verifyPending = false
            solved = completedIds.size >= sessionTarget
            answerButtons[index].backgroundTintList = ColorStateList.valueOf(Color.rgb(46, 158, 107))
            feedbackText.text = "回答正确！${question.explanation}"
            feedbackText.setTextColor(Color.rgb(160, 235, 190))
            // A stage-up celebration needs the screen: hold it a little longer than the usual farewell.
            ui.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    if (solved) finishAfterCorrect() else showNextQuestion()
                }
            }, if (stageUp) 3_200L else 1_300L)
        } else {
            if (prefs.verifyPending) {
                // Failed the verification: the snap correct answer was a guess after all. Count it as 3 in a row.
                prefs.verifyPending = false
                prefs.wrongStreak = QuizRules.REST_STREAK_START
                prefs.snapWrongs = 0
            } else {
                prefs.wrongStreak = prefs.wrongStreak + 1
                prefs.snapWrongs = if (snap) prefs.snapWrongs + 1 else 0
            }
            answerButtons[index].backgroundTintList = ColorStateList.valueOf(Color.rgb(217, 83, 79))
            val restMinutes = if (prefs.restGuard) QuizRules.restMinutes(prefs.wrongStreak) else 0
            if (restMinutes > 0) {
                prefs.restUntil = now + restMinutes * 60_000L
                feedbackText.text = "连续答错 ${prefs.wrongStreak} 题，休息 $restMinutes 分钟后再来"
            } else {
                feedbackText.text = "这题不对，换一题再试试"
            }
            feedbackText.setTextColor(Color.rgb(255, 180, 170))
            ui.postDelayed({ if (!isFinishing) showNextQuestion() }, if (restMinutes > 0) 2_000L else 750L)
        }
    }

    private fun finishAfterCorrect() {
        justFinished = true // MainActivity skips the admin prompt right after a quiz
        releaseSound() // hand the sound back before the video is shown again
        dev.plume.DiagLog.event("quiz_done") // the happy end; anything else is quiz_abandoned below
        QuizPrefs(this).apply {
            wrongStreak = 0 // a finished session proves real work: every anti-guess counter resets
            snapWrongs = 0
            verifyPending = false
            completedSessionQuestions = emptySet()
            quizActive = false // the session ends here; nothing must come back after a power-on
        }
        if (lockActive) {
            stopLockTask()
            lockActive = false
        }
        finish()
    }

    /** Puts the current rank, stage and points on the badge; the badge color follows the rank. */
    private fun refreshLevel(score: Int = ledger?.score() ?: 0) {
        val tier = QuizRules.tierIndex(score)
        val rank = tier / QuizRules.STAGES_PER_RANK
        (levelBadge.background as? GradientDrawable)?.setColor(QuizRules.RANK_COLORS[rank])
        levelBadge.text = "${QuizRules.RANK_ICONS[rank]} ${QuizRules.tierName(tier)}"
        levelScore.text = if (tier >= QuizRules.TIER_COUNT - 1) {
            "积分 $score · 已是最高等级"
        } else {
            val next = tier + 1
            "积分 $score · 距${QuizRules.tierName(next)}还差 ${QuizRules.tierFloor(next) - score} 分"
        }
    }

    /**
     * A stage-up earns a full-screen party: the rank icon bounces in, stars burst outward, the new
     * stage name shines in the rank color, and the voice engine congratulates the child.
     */
    private fun celebrateLevelUp(result: QuizLedger.Result) {
        val tier = result.tierAfter
        val rank = tier / QuizRules.STAGES_PER_RANK
        val color = QuizRules.RANK_COLORS[rank]
        val overlay = FrameLayout(this).apply { setBackgroundColor(0xCC0A1526.toInt()) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val icon = TextView(this).apply {
            text = QuizRules.RANK_ICONS[rank]
            textSize = 64f * uiScale
            gravity = Gravity.CENTER
        }
        val title = TextView(this).apply {
            text = "升级啦！"
            textSize = 40f * uiScale
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val stage = TextView(this).apply {
            text = QuizRules.tierName(tier)
            textSize = 34f * uiScale
            setTextColor(color)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        box.addView(icon)
        box.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        box.addView(stage)
        box.addView(
            text("积分 +${result.delta} · 继续加油！", 18f, Color.rgb(184, 199, 217)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) },
        )
        overlay.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The star burst: ten sparks fly outward from the center on fixed angles, so it looks the
        // same every time and stays jitter-free on slow TV hardware.
        for (i in 0 until 10) {
            val star = TextView(this).apply {
                text = "✦"
                textSize = (if (i % 2 == 0) 26f else 20f) * uiScale
                setTextColor(if (i % 2 == 0) color else Color.rgb(255, 209, 102))
            }
            overlay.addView(star, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            val angle = Math.toRadians((i * 36).toDouble())
            val distance = dp(150) - (i % 3) * dp(25)
            star.animate()
                .translationX((Math.cos(angle) * distance).toFloat())
                .translationY((Math.sin(angle) * distance).toFloat())
                .alpha(0f)
                .setDuration(1_400L)
                .setStartDelay(250L)
                .start()
        }
        icon.scaleX = 0.2f
        icon.scaleY = 0.2f
        icon.animate().scaleX(1f).scaleY(1f).setDuration(600L).start()
        rootLayout.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ui.postDelayed({
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }, 2_600L)
        if (QuizPrefs(this).voice) QuizVoice.speak("恭喜你，升级到${QuizRules.tierName(tier)}，继续加油！")
    }

    // TV remotes still dispatch the back key; gesture-back migration would require AndroidX.
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        feedbackText.text = if (restRemaining() > 0L) "休息结束后，答对一题才能退出" else "答对一题后才能退出"
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Best-effort recovery for ordinary installations; Device Owner uses real lock-task enforcement.
        if (!solved && !lockActive) {
            ui.postDelayed({
                if (!solved && !isFinishing) {
                    startActivity(Intent(this, QuizActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }, 250L)
        }
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopReading()
        releaseSound()
        // GrowQuiz: a page that dies with a question unanswered — a system kill, a power cut, an
        // update install — leaves quizActive set, so the page comes back; the report says which.
        // A crash has its own event, and a screen rotation is rare on a TV and counts as neither.
        if (!solved && !justFinished) dev.plume.DiagLog.event("quiz_abandoned",
            "", mapOf("finishing" to isFinishing.toString()))
        // The engine itself stays up: it belongs to the app, and shutting it down here would make the next
        // quiz pay the model load again.
        super.onDestroy()
    }

    // GrowQuiz: the quiz takes the sound away from whatever is playing, so a video stops instead of talking
    // over the question reading. Asking for audio focus is what makes a well-behaved player pause; the media
    // key is the fallback for the players that ignore focus (TCL's own player does). Both are undone when the
    // question is answered, so the video picks up where it stopped.
    private var focusRequest: AudioFocusRequest? = null
    private var pausedMedia = false

    private fun holdSound() {
        val am = getSystemService(AudioManager::class.java) ?: return
        if (focusRequest == null) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
        }
        am.requestAudioFocus(focusRequest!!)
        if (am.isMusicActive) {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            pausedMedia = true
        }
    }

    private fun releaseSound() {
        val am = getSystemService(AudioManager::class.java) ?: return
        if (pausedMedia) {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            pausedMedia = false
        }
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size * uiScale
        setTextColor(color)
    }

    private fun answerBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(Color.rgb(255, 248, 231), Color.rgb(255, 209, 102), 4))
        addState(intArrayOf(), rounded(Color.WHITE, Color.rgb(184, 199, 217), 2))
    }

    private fun rounded(fill: Int, stroke: Int, width: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(18).toFloat()
        setStroke(dp(width), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density * uiScale).toInt()

    companion object {
        private const val READ_REPEAT_MS = 20_000L // after the first reading, repeat question + options every 20 s
        private const val TTS_RETRY_MS = 250L // the engine is still loading: ask again this soon
        private const val TTS_RETRY_LIMIT = 40 // 10 s of short retries, then give up and keep quiet
        /** Above this age a question is not read aloud: the child reads it from the screen. */
        private const val VOICE_MAX_AGE = 10
        private const val GUARD_POLL_MS = 1_000L // while a quiz or rest is up, check the foreground every second

        /** A comeback that lands within this window counts as ours; anything slower was the child coming back. */
        private const val SNAP_OK_MS = 4_000L

        /** Set when a quiz is completed; MainActivity reads (and clears) it to skip the admin prompt then. */
        @Volatile var justFinished = false
    }
}
