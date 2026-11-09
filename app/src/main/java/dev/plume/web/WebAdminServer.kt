package dev.plume.web

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.plume.quiz.QuestionBank
import dev.plume.quiz.QuizActivity
import dev.plume.quiz.QuizDeviceOwner
import dev.plume.quiz.QuizPrefs
import dev.plume.quiz.QuizScheduler
import dev.plume.quiz.QuizStats
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections

/**
 * A small built-in admin console for the LAN: one page, password-protected, no external libraries.
 * Serves the control page and a few JSON endpoints. Runs while the launcher is alive.
 */
object WebAdminServer {
    private const val PREFS = "web_admin"
    private const val KEY_HASH = "password_hash"
    private const val KEY_SALT = "password_salt"
    private const val COOKIE = "session"
    private const val SESSION_MS = 24L * 60 * 60 * 1000
    private const val MIN_PASSWORD = 6
    private const val MAX_BODY = 64L * 1024
    private const val MAX_IMPORT_BODY = 8L * 1024 * 1024
    private const val MAX_IMPORT_QUESTIONS = 5000
    private const val MAX_IMPORT_ERRORS = 20

    private val sessions = HashMap<String, Long>()
    private val random = SecureRandom()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: 8081

    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        server = try {
            ServerSocket(8081)
        } catch (_: IOException) {
            try {
                ServerSocket(0) // 8081 busy: any free port, the settings row shows it
            } catch (second: IOException) {
                // The parents' console is the local way to reach this TV's settings; record a local
                // diagnostic event if the port cannot be opened.
                dev.plume.DiagLog.event("web_admin_fail", second.message ?: second.javaClass.simpleName)
                null
            }
        }
        if (server == null) return
        running = true
        Thread({ serve(app) }, "web-admin").start()
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
        sessions.clear()
    }

    fun url(): String {
        val address = localIpAddress() ?: return "http://localhost:$port"
        return "http://$address:$port"
    }

    // ---- one-off QR unlock: the TV's password dialog can be answered from a phone instead ----

    private const val UNLOCK_MS = 3L * 60 * 1000
    private const val UNLOCK_TRIES = 5

    @Volatile private var unlockToken: String? = null
    @Volatile private var unlockDone: (() -> Unit)? = null
    private var unlockAt = 0L
    private var unlockFails = 0

    /**
     * Starts a one-off unlock session while the TV's password dialog is up and returns its token.
     * The token goes into the QR's URL; anyone on the LAN who can see the screen can reach the page,
     * but the password is still the only way through, and [endUnlock] kills it the moment the dialog goes away.
     */
    @Synchronized fun beginUnlock(onUnlock: () -> Unit): String {
        val token = randomHex(16)
        unlockToken = token
        unlockDone = onUnlock
        unlockAt = System.currentTimeMillis()
        unlockFails = 0
        return token
    }

    /** The dialog went away (accepted, cancelled, dismissed): the QR on screen must stop working at once. */
    @Synchronized fun endUnlock() {
        unlockToken = null
        unlockDone = null
    }

    /** The URL the QR on the TV's password dialog encodes: this LAN page takes the password from a phone. */
    fun unlockUrl(token: String) = url() + "/unlock?t=" + token

    /** Whether the TV has a LAN address the phone can reach; the QR is pointless on localhost. */
    fun hasLanUrl(): Boolean = !url().startsWith("http://localhost")

    /** True once the family has set the admin password; the TV-side settings entries gate on this too. */
    fun hasPassword(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HASH, null) != null

    /** Checks a password against the stored salted hash (used by the on-TV unlock dialog). */
    fun verifyPassword(context: Context, password: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        val stored = prefs.getString(KEY_HASH, null) ?: return false
        return sha256(salt + password) == stored
    }

    @Volatile private var bank: QuestionBank? = null

    /** One bank per server run; mutations reload it, and disabled state lives in shared preferences. */
    private fun bank(context: Context): QuestionBank =
        bank ?: QuestionBank(context.applicationContext).also { bank = it }

    private fun serve(context: Context) {
        val socket = server ?: return
        while (running) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            try {
                client.soTimeout = 15_000
                handle(client, context)
            } catch (_: Exception) {
            } finally {
                try {
                    client.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun handle(socket: Socket, context: Context) {
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val request = readLine(input) ?: return
        val method = request.substringBefore(' ').uppercase()
        val target = request.substringAfter(' ').substringBefore(' ')
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val key = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':').trim()
            headers[key] = value
        }
        // Question bank imports carry whole files, so they get a much larger body budget.
        val bodyLimit = if (path == "/api/questions/import") MAX_IMPORT_BODY else MAX_BODY
        val body = if (method == "POST") readBody(input, headers["content-length"]?.toLongOrNull() ?: 0L, bodyLimit) else ""

        when {
            path == "/" && method == "GET" -> {
                val page = context.assets.open("web_admin.html").use { it.readBytes() }.toString(Charsets.UTF_8)
                reply(out, 200, "text/html; charset=utf-8", page)
            }

            // The standalone bulk-import page; like /stats it checks the session itself and sends
            // the visitor to the console to log in when the cookie is missing.
            path == "/import" && method == "GET" -> {
                val page = context.assets.open("web_import.html").use { it.readBytes() }.toString(Charsets.UTF_8)
                reply(out, 200, "text/html; charset=utf-8", page)
            }

            // The standalone learning report; the page itself asks /api/stats and sends the visitor
            // to the console to log in when the session cookie is missing.
            path == "/stats" && method == "GET" -> {
                val page = context.assets.open("web_stats.html").use { it.readBytes() }.toString(Charsets.UTF_8)
                reply(out, 200, "text/html; charset=utf-8", page)
            }

            // The learning report data: one trailing time window, everything the page shows in one reply.
            path == "/api/stats" && method == "GET" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val range = query["range"]?.takeIf { it in QuizStats.RANGE_DAYS.keys } ?: "month"
                val ledger = dev.plume.quiz.QuizLedger(context)
                val report = QuizStats.build(
                    range,
                    ledger.attempts(),
                    dev.plume.TvWatch.minutesByDay(context),
                    System.currentTimeMillis(),
                )
                val score = ledger.score()
                val tier = dev.plume.quiz.QuizRules.tierIndex(score)
                val rank = tier / dev.plume.quiz.QuizRules.STAGES_PER_RANK
                val nextTier = (tier + 1).coerceAtMost(dev.plume.quiz.QuizRules.TIER_COUNT - 1)
                val level = JSONObject()
                    .put("score", score)
                    .put("tier", tier)
                    .put("name", dev.plume.quiz.QuizRules.tierName(tier))
                    .put("rank", dev.plume.quiz.QuizRules.RANKS[rank])
                    .put("stage", tier % dev.plume.quiz.QuizRules.STAGES_PER_RANK + 1)
                    .put("icon", dev.plume.quiz.QuizRules.RANK_ICONS[rank])
                    .put("color", "#%06x".format(0xFFFFFF and dev.plume.quiz.QuizRules.RANK_COLORS[rank]))
                    .put("nextAt", dev.plume.quiz.QuizRules.tierFloor(nextTier))
                    .put("prevAt", dev.plume.quiz.QuizRules.tierFloor(tier))
                    .put("nextName", if (tier >= dev.plume.quiz.QuizRules.TIER_COUNT - 1) "" else dev.plume.quiz.QuizRules.tierName(tier + 1))
                val totals = JSONObject()
                    .put("attempts", report.attempts)
                    .put("correct", report.correct)
                    .put("wrong", report.wrong)
                    .put("repeatWrong", report.repeatWrong)
                    .put("accuracy", if (report.attempts > 0) report.correct.toDouble() / report.attempts else 0.0)
                    .put("scoreDelta", report.scoreDelta)
                    .put("activeDays", report.activeDays)
                    .put("tvMinutes", report.tvMs / 60_000L)
                val trend = JSONArray()
                for (bucket in report.buckets) {
                    val point = JSONObject()
                        .put("label", bucket.label)
                        .put("attempts", bucket.attempts)
                        .put("correct", bucket.correct)
                        .put("wrong", bucket.wrong)
                        .put("tvMinutes", bucket.tvMs / 60_000L)
                    if (bucket.attempts > 0) point.put("accuracy", bucket.correct.toDouble() / bucket.attempts)
                    trend.put(point)
                }
                val cats = JSONArray()
                for (cat in report.categories) {
                    cats.put(
                        JSONObject()
                            .put("name", cat.name)
                            .put("attempts", cat.attempts)
                            .put("correct", cat.correct)
                            .put("wrong", cat.wrong)
                            .put("accuracy", if (cat.attempts > 0) cat.correct.toDouble() / cat.attempts else 0.0),
                    )
                }
                // The wrong list reads better with the question text; the bank knows the ids.
                val wrongs = JSONArray()
                if (report.wrongs.isNotEmpty()) {
                    val known = bank(context).list().associateBy { it.id }
                    for (wrong in report.wrongs) {
                        wrongs.put(
                            JSONObject()
                                .put("id", wrong.id)
                                .put("category", wrong.category)
                                .put("count", wrong.count)
                                .put("question", known[wrong.id]?.prompt ?: wrong.id),
                        )
                    }
                }
                val json = JSONObject()
                    .put("ok", true)
                    .put("range", report.range)
                    .put("since", report.sinceMs)
                    .put("level", level)
                    .put("totals", totals)
                    .put("trend", trend)
                    .put("categories", cats)
                    .put("wrongs", wrongs)
                reply(out, 200, "application/json; charset=utf-8", json.toString())
            }

            // The page the phone lands on after scanning the TV password dialog's QR. Open to anyone
            // on the LAN who can see the TV: the one-off token in the URL is the ticket, and it dies
            // with the dialog. No session, no cookies, nothing persists after the unlock.
            path == "/unlock" && method == "GET" -> {
                reply(out, 200, "text/html; charset=utf-8", UNLOCK_PAGE)
            }

            path == "/api/unlock" && method == "POST" -> {
                val json = JSONObject(body.ifEmpty { "{}" })
                val token = json.optString("t")
                val password = json.optString("p")
                val pending = unlockToken
                val done = unlockDone
                when {
                    pending == null || token != pending || done == null || System.currentTimeMillis() - unlockAt > UNLOCK_MS -> {
                        endUnlock()
                        reply(out, 403, "application/json; charset=utf-8", """{"ok":false,"error":"expired"}""")
                    }
                    verifyPassword(context, password) -> {
                        endUnlock() // one shot: the token never works twice
                        reply(out, 200, "application/json; charset=utf-8", ok())
                        main.post(done) // the dialog dismisses itself and the settings open, as if typed on the TV
                    }
                    ++unlockFails >= UNLOCK_TRIES -> {
                        endUnlock()
                        reply(out, 429, "application/json; charset=utf-8", """{"ok":false,"error":"too-many"}""")
                    }
                    else -> reply(out, 401, "application/json; charset=utf-8", """{"ok":false,"error":"wrong","tries":${UNLOCK_TRIES - unlockFails}}""")
                }
            }

            path == "/api/status" && method == "GET" -> {
                val token = cookie(headers)
                val authed = valid(token)
                val prefs = QuizPrefs(context)
                val json = if (authed) {
                    JSONObject()
                        .put("initialized", initialized(context))
                        .put("authenticated", true)
                        .put("age", prefs.age)
                        .put("interval", prefs.intervalMinutes)
                        .put("enabled", prefs.enabled)
                        .put("voice", prefs.voice)
                        .put("bootQuiz", prefs.bootQuiz)
                        .put("restGuard", prefs.restGuard)
                        // "A skipped question comes back": the switch, and the evidence it works.
                        .put("snapBack", prefs.snapBack)
                        .put("snapOkAt", prefs.snapOkAt)
                        .put("snapOkCount", prefs.snapOkCount)
                        .put("overlay", android.provider.Settings.canDrawOverlays(context))
                        .put("a11y", dev.plume.HomeOverride.running != null)
                        // GrowQuiz: day-one reports showed nearly every Android 12+ TV without the
                        // exact-alarm grant, and most TVs with the home service never switched on.
                        // The console needs both facts apart from "bound right now".
                        .put("a11yEnabled", dev.plume.DeviceProbe.a11yEnabled(context))
                        .put("exactAlarm", dev.plume.DeviceProbe.exactAlarm(context))
                        .put("thinkSeconds", prefs.thinkSeconds)
                        .put("wrongStreak", prefs.wrongStreak)
                        .put("restUntil", prefs.restUntil)
                        .put("restRemainingMs", (prefs.restUntil - System.currentTimeMillis()).coerceAtLeast(0L))
                        .put("categories", JSONArray(prefs.categories.toList()))
                        .put("next", prefs.nextTriggerAt)
                        .put("owner", QuizDeviceOwner.isOwner(context))
                        .put("crash", dev.plume.CrashLog.peek(context) ?: "")
                        .put("tts", dev.plume.quiz.TtsProbe.describe())
                } else {
                    JSONObject()
                        .put("initialized", initialized(context))
                        .put("authenticated", false)
                }
                reply(out, 200, "application/json; charset=utf-8", json.toString())
            }

            path == "/api/setup" && method == "POST" -> {
                if (initialized(context)) return reply(out, 400, "application/json; charset=utf-8", error("already initialized"))
                val password = JSONObject(body.ifEmpty { "{}" }).optString("password")
                if (password.length < MIN_PASSWORD) return reply(out, 400, "application/json; charset=utf-8", error("password too short"))
                val salt = randomHex(16)
                val hash = sha256(salt + password)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_SALT, salt)
                    .putString(KEY_HASH, hash)
                    .commit()
                val token = newSession()
                reply(out, 200, "application/json; charset=utf-8", ok(), cookie = sessionCookie(token))
            }

            path == "/api/login" && method == "POST" -> {
                if (!initialized(context)) return reply(out, 400, "application/json; charset=utf-8", error("not initialized"))
                val password = JSONObject(body.ifEmpty { "{}" }).optString("password")
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val salt = prefs.getString(KEY_SALT, null) ?: return reply(out, 500, "application/json; charset=utf-8", error("server error"))
                val stored = prefs.getString(KEY_HASH, null) ?: return reply(out, 500, "application/json; charset=utf-8", error("server error"))
                if (sha256(salt + password) != stored) return reply(out, 401, "application/json; charset=utf-8", error("wrong password"))
                val token = newSession()
                reply(out, 200, "application/json; charset=utf-8", ok(), cookie = sessionCookie(token))
            }

            path == "/api/logout" && method == "POST" -> {
                val token = cookie(headers)
                if (token != null) sessions.remove(token)
                reply(out, 200, "application/json; charset=utf-8", ok(), cookie = clearCookie())
            }

            path == "/api/password_change" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val json = JSONObject(body.ifEmpty { "{}" })
                val old = json.optString("old")
                val fresh = json.optString("new")
                if (!verifyPassword(context, old)) return reply(out, 403, "application/json; charset=utf-8", error("wrong password"))
                if (fresh.length < MIN_PASSWORD) return reply(out, 400, "application/json; charset=utf-8", error("password too short"))
                val salt = randomHex(16)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_SALT, salt)
                    .putString(KEY_HASH, sha256(salt + fresh))
                    .commit()
                reply(out, 200, "application/json; charset=utf-8", ok())
            }

            path == "/api/settings" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val json = JSONObject(body.ifEmpty { "{}" })
                val age = json.optInt("age", -1)
                val interval = json.optInt("interval", -1)
                val enabled = json.optBoolean("enabled", true)
                if (age !in 5..15) return reply(out, 400, "application/json; charset=utf-8", error("invalid age"))
                if (interval !in 1..1440) return reply(out, 400, "application/json; charset=utf-8", error("invalid interval"))
                val prefs = QuizPrefs(context)
                val voice = if (json.has("voice")) json.optBoolean("voice") else prefs.voice
                val bootQuiz = if (json.has("bootQuiz")) json.optBoolean("bootQuiz") else prefs.bootQuiz
                val categories = json.optJSONArray("categories")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }.toSet()
                } ?: prefs.categories
                prefs.save(age, interval, enabled, categories, voice)
                prefs.bootQuiz = bootQuiz
                if (json.has("restGuard")) prefs.restGuard = json.optBoolean("restGuard")
                if (json.has("snapBack")) prefs.snapBack = json.optBoolean("snapBack")
                if (json.has("thinkSeconds")) prefs.thinkSeconds = json.optInt("thinkSeconds", prefs.thinkSeconds)
                QuizScheduler.reschedule(context)
                QuizDeviceOwner.configure(context)
                reply(out, 200, "application/json; charset=utf-8", ok())
            }

            path == "/api/rest/clear" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                QuizPrefs(context).clearRest() // ends the enforced rest and wipes the anti-guess counters
                reply(out, 200, "application/json; charset=utf-8", ok())
            }

            path == "/api/categories" && method == "GET" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val b = bank(context)
                val age = query["age"]?.toIntOrNull()
                val questions = b.list(age = age)
                val disabled = b.disabledIds()
                val arr = JSONArray()
                for (name in b.categories(age)) {
                    val inCategory = if (name == QuestionBank.CUSTOM_CATEGORY) {
                        questions.filter { it.custom }
                    } else {
                        questions.filter { it.category == name }
                    }
                    arr.put(
                        JSONObject()
                            .put("name", name)
                            .put("count", inCategory.size)
                            .put("disabled", inCategory.count { it.id in disabled }),
                    )
                }
                val json = JSONObject()
                    .put("ok", true)
                    .put("categories", arr)
                    .put("selected", JSONArray(QuizPrefs(context).categories.toList()))
                reply(out, 200, "application/json; charset=utf-8", json.toString())
            }

            path == "/api/questions" && method == "GET" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val b = bank(context)
                val age = query["age"]?.toIntOrNull()
                val category = query["category"]?.takeIf { it.isNotEmpty() }
                val onlyDisabled = when (query["state"]) {
                    "enabled" -> false
                    "disabled" -> true
                    else -> null
                }
                val items = b.list(age, category, onlyDisabled)
                val pageSize = query["pageSize"]?.toIntOrNull()?.coerceIn(1, 100)
                val requestedPage = query["page"]?.toIntOrNull()?.coerceAtLeast(1)
                val page: Int
                val paged: List<dev.plume.quiz.Question>
                if (pageSize != null && requestedPage != null) {
                    val lastPage = maxOf(1, (items.size + pageSize - 1) / pageSize)
                    page = requestedPage.coerceAtMost(lastPage)
                    paged = items.drop((page - 1) * pageSize).take(pageSize)
                } else {
                    page = 1
                    paged = items
                }
                val disabled = b.disabledIds()
                val arr = JSONArray()
                for (q in paged) {
                    arr.put(
                        JSONObject()
                            .put("id", q.id)
                            .put("age", q.age)
                            .put("category", q.category)
                            .put("question", q.prompt)
                            .put("options", JSONArray(q.options))
                            .put("answerIndex", q.answerIndex)
                            .put("explanation", q.explanation)
                            .put("custom", q.custom)
                            .put("overridden", b.isOverridden(q.id))
                            .put("correct", b.correctCount(q.id))
                            .put("enabled", q.id !in disabled),
                    )
                }
                val json = JSONObject()
                    .put("ok", true)
                    .put("total", items.size)
                    .put("page", page)
                    .put("pageSize", pageSize ?: items.size)
                    .put("questions", arr)
                reply(out, 200, "application/json; charset=utf-8", json.toString())
            }

            // Bulk import of an AI-generated question file. Every row is validated first; a single
            // bad row fails the whole file with a row-by-row report, so a half-imported bank never happens.
            path == "/api/questions/import" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                if (body.isEmpty()) return reply(out, 400, "application/json; charset=utf-8", error("请求体为空，或文件超过 8 MB 上限"))
                val root = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    return reply(out, 400, "application/json; charset=utf-8", error("文件内容不是有效的 JSON"))
                }
                val array = root.optJSONArray("questions")
                    ?: return reply(out, 400, "application/json; charset=utf-8", error("文件缺少 questions 数组"))
                when {
                    array.length() == 0 ->
                        return reply(out, 400, "application/json; charset=utf-8", error("文件里没有任何题目"))
                    array.length() > MAX_IMPORT_QUESTIONS ->
                        return reply(out, 400, "application/json; charset=utf-8", error("一次最多导入 $MAX_IMPORT_QUESTIONS 题，本文件有 ${array.length()} 题，请拆分后再传"))
                }
                val replace = root.optString("mode") == "replace"
                val drafts = ArrayList<QuestionBank.CustomDraft>(array.length())
                val errors = JSONArray()
                var failures = 0
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index)
                    val failure = when {
                        item == null -> "不是 JSON 对象"
                        else -> validateImport(item)
                    }
                    if (failure != null) {
                        failures++
                        if (errors.length() < MAX_IMPORT_ERRORS) {
                            errors.put(JSONObject().put("row", index + 1).put("reason", failure))
                        }
                    } else {
                        val optionsJson = item!!.getJSONArray("options")
                        drafts += QuestionBank.CustomDraft(
                            age = item.getInt("age"),
                            category = item.getString("category"),
                            prompt = item.getString("question"),
                            options = List(4) { optionsJson.getString(it) },
                            answerIndex = item.getInt("answerIndex"),
                            explanation = item.optString("explanation"),
                        )
                    }
                }
                if (failures > 0) {
                    val json = JSONObject()
                        .put("ok", false)
                        .put("error", "$failures 道题格式不正确，整批未导入")
                        .put("total", array.length())
                        .put("failed", failures)
                        .put("errors", errors)
                    return reply(out, 400, "application/json; charset=utf-8", json.toString())
                }
                val result = bank(context).importCustom(drafts, replace)
                val json = JSONObject()
                    .put("ok", true)
                    .put("imported", result.imported)
                    .put("skipped", result.skipped)
                    .put("replace", replace)
                reply(out, 200, "application/json; charset=utf-8", json.toString())
            }

            path == "/api/question/update" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val id = JSONObject(body.ifEmpty { "{}" }).optString("id").trim()
                if (id.isEmpty()) return reply(out, 400, "application/json; charset=utf-8", error("id required"))
                val (draft, failure) = parseDraft(body)
                if (draft == null) return reply(out, 400, "application/json; charset=utf-8", error(failure ?: "invalid question"))
                val b = bank(context)
                val target = b.list().firstOrNull { it.id == id }
                    ?: return reply(out, 404, "application/json; charset=utf-8", error("question not found"))
                // Custom questions are rewritten in place; bundled ones get an override so the original stays intact.
                val saved = if (target.custom) {
                    b.updateCustom(id, draft.age, draft.category, draft.prompt, draft.options, draft.answerIndex, draft.explanation)
                } else {
                    b.saveOverride(id, draft.age, draft.category, draft.prompt, draft.options, draft.answerIndex, draft.explanation)
                }
                if (!saved) return reply(out, 400, "application/json; charset=utf-8", error("update failed"))
                reply(out, 200, "application/json; charset=utf-8", ok())
            }

            path == "/api/question/revert" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val id = JSONObject(body.ifEmpty { "{}" }).optString("id")
                if (!bank(context).removeOverride(id)) {
                    return reply(out, 400, "application/json; charset=utf-8", error("only overridden bundled questions can be reverted"))
                }
                reply(out, 200, "application/json; charset=utf-8", ok())
            }

            path == "/api/question/delete" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val id = JSONObject(body.ifEmpty { "{}" }).optString("id")
                if (!bank(context).deleteCustom(id)) {
                    return reply(out, 400, "application/json; charset=utf-8", error("only custom questions can be deleted"))
                }
                reply(out, 200, "application/json; charset=utf-8", ok())
            }

            path == "/api/questions/state" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val json = JSONObject(body.ifEmpty { "{}" })
                val idsJson = json.optJSONArray("ids")
                    ?: return reply(out, 400, "application/json; charset=utf-8", error("ids required"))
                val enabled = json.optBoolean("enabled", true)
                val ids = (0 until idsJson.length()).map { idsJson.getString(it) }
                bank(context).setDisabled(ids, !enabled)
                reply(out, 200, "application/json; charset=utf-8", JSONObject().put("ok", true).put("count", ids.size).toString())
            }

            path == "/api/questions/state_all" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val json = JSONObject(body.ifEmpty { "{}" })
                val enabled = json.optBoolean("enabled", true)
                val age = if (json.has("age")) json.optInt("age") else null
                val category = json.optString("category").ifEmpty { null }
                bank(context).setAllDisabled(!enabled, age, category)
                reply(out, 200, "application/json; charset=utf-8", ok())
            }

            path == "/api/start" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val intent = Intent(context, QuizActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                main.post { context.startActivity(intent) }
                reply(out, 200, "application/json; charset=utf-8", ok())
            }

            path == "/api/tts_test" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val spoke = dev.plume.quiz.TtsProbe.speak("语音测试：宝贝你好，我是你的答题小助手")
                val json = JSONObject().put("ok", true).put("spoke", spoke)
                reply(out, 200, "application/json; charset=utf-8", json.toString())
            }

            else -> reply(out, if (method == "GET" || method == "POST") 404 else 405, "application/json; charset=utf-8", error("not found"))
        }
    }

    private fun initialized(context: Context): Boolean = hasPassword(context)

    /** Validated question fields used by the update endpoint. */
    private data class QuestionDraft(
        val age: Int,
        val category: String,
        val prompt: String,
        val options: List<String>,
        val answerIndex: Int,
        val explanation: String,
    )

    /** Parses a question body; on failure returns null draft plus an error message. */
    private fun parseDraft(body: String): Pair<QuestionDraft?, String?> {
        val json = JSONObject(body.ifEmpty { "{}" })
        val age = json.optInt("age", -1)
        val category = json.optString("category").trim()
        val prompt = json.optString("question").trim()
        val optionsJson = json.optJSONArray("options")
        val answerIndex = json.optInt("answerIndex", -1)
        val explanation = json.optString("explanation").trim()
        val options = optionsJson?.let { arr -> List(arr.length()) { arr.optString(it).trim() } }.orEmpty()
        val failure = when {
            age !in 5..15 -> "invalid age"
            category.isEmpty() -> "category required"
            prompt.isEmpty() -> "question required"
            options.size != 4 || options.any { it.isEmpty() } || options.toSet().size != 4 -> "four distinct options required"
            answerIndex !in 0..3 -> "answer index out of range"
            else -> null
        }
        return if (failure != null) {
            null to failure
        } else {
            QuestionDraft(age, category, prompt, options, answerIndex, explanation) to null
        }
    }

    /**
     * Field-level check of one imported question; returns a parent-readable Chinese reason on
     * failure, null when the row is clean. Mirrors [QuestionBank]'s own validation.
     */
    private fun validateImport(item: JSONObject): String? {
        if (!item.has("age") || item.optInt("age", -1) !in 5..15) return "age 必须是 5–15 的整数"
        if (item.optString("category").trim().isEmpty()) return "category 不能为空"
        if (item.optString("question").trim().isEmpty()) return "question 不能为空"
        val options = item.optJSONArray("options")
            ?: return "options 必须是 4 个互不相同的选项"
        val texts = List(options.length()) { options.optString(it).trim() }
        if (texts.size != 4 || texts.any { it.isEmpty() } || texts.toSet().size != 4) {
            return "options 必须是 4 个互不相同的选项"
        }
        if (item.optInt("answerIndex", -1) !in 0..3) return "answerIndex 必须是 0–3（0 代表第一个选项）"
        return null
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&')
            .filter { it.contains('=') }
            .associate {
                URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8.name()) to
                    URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8.name())
            }
    }

    private fun newSession(): String {
        val token = randomHex(32)
        sessions[token] = System.currentTimeMillis() + SESSION_MS
        return token
    }

    private fun valid(token: String?): Boolean {
        if (token == null) return false
        val expiry = sessions[token] ?: return false
        if (expiry < System.currentTimeMillis()) {
            sessions.remove(token)
            return false
        }
        return true
    }

    private fun cookie(headers: Map<String, String>): String? {
        val raw = headers["cookie"] ?: return null
        return raw.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$COOKIE=") }
            ?.substringAfter('=')
    }

    private fun sessionCookie(token: String) =
        "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=${SESSION_MS / 1000}"

    private fun clearCookie() = "$COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"

    private fun ok() = JSONObject().put("ok", true).toString()

    private fun error(message: String) = JSONObject().put("ok", false).put("error", message).toString()

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun randomHex(bytes: Int): String {
        val data = ByteArray(bytes)
        random.nextBytes(data)
        return data.joinToString("") { "%02x".format(it) }
    }

    private fun localIpAddress(): String? {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        var fallback: String? = null
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (address in Collections.list(nif.inetAddresses)) {
                if (address.isLoopbackAddress || address !is java.net.Inet4Address) continue
                if (address.isSiteLocalAddress) return address.hostAddress
                // Not a classic LAN range (some VMs and hotel networks use one): still the best guess.
                if (fallback == null) fallback = address.hostAddress
            }
        }
        return fallback
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        throw IOException("header line too long")
    }

    private fun readBody(input: InputStream, length: Long, limit: Long): String {
        if (length <= 0L || length > limit) return ""
        val buffer = ByteArray(length.toInt())
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    private fun reply(out: OutputStream, status: Int, type: String, body: String, cookie: String? = null) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
            .append("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n")
            .append("Content-Type: $type\r\n")
            .append("Content-Length: ${bytes.size}\r\n")
            .append("Connection: close\r\n")
        cookie?.let { head.append("Set-Cookie: $it\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    /** The phone side of the TV's password dialog: one input, one button, nothing else. */
    private val UNLOCK_PAGE = """
<!doctype html>
<html lang="zh"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>输入管理密码</title>
<style>
  body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
       background:#0e1933;color:#cbdaf3;font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
  .card{width:min(92vw,360px);background:#14233f;border:1px solid #27446c;border-radius:18px;padding:28px 24px;box-sizing:border-box}
  h1{font-size:20px;margin:0 0 6px;color:#fff}
  p{font-size:13px;margin:0 0 18px;color:#7d94b8}
  input{width:100%;box-sizing:border-box;font-size:18px;padding:14px;border-radius:12px;border:1px solid #27446c;
        background:#0c182e;color:#fff;outline:none}
  input:focus{border-color:#ffd166}
  button{width:100%;margin-top:14px;font-size:17px;padding:14px 0;border:0;border-radius:26px;
         background:#ffd166;color:#1a2332;font-weight:600}
  button:disabled{background:#27446c;color:#7d94b8}
  .msg{margin-top:14px;font-size:14px;min-height:20px;text-align:center}
  .err{color:#ff8a80}.ok{color:#a0ebbe}
</style></head>
<body><div class="card">
  <h1>输入管理密码</h1>
  <p>这是电视上密码框的扫码入口，输入正确后电视会自动解锁。</p>
  <input id="p" type="password" autocomplete="off" placeholder="管理密码">
  <button id="go">确认解锁</button>
  <div id="m" class="msg"></div>
</div>
<script>
const t = new URLSearchParams(location.search).get('t') || '';
const el = document.getElementById('p'), go = document.getElementById('go'), m = document.getElementById('m');
async function submit() {
  go.disabled = true; m.className = 'msg'; m.textContent = '';
  try {
    const r = await fetch('/api/unlock', {method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({t: t, p: el.value})});
    const j = await r.json();
    if (j.ok) { m.className = 'msg ok'; m.textContent = '已解锁，回到电视即可'; el.value = ''; return; }
    if (j.error === 'expired') { m.className = 'msg err'; m.textContent = '二维码已失效，请回电视重新扫码'; el.value = ''; return; }
    if (j.error === 'too-many') { m.className = 'msg err'; m.textContent = '尝试次数过多，请回电视重新扫码'; el.value = ''; return; }
    m.className = 'msg err'; m.textContent = '密码错误，还可尝试 ' + j.tries + ' 次'; el.value = ''; el.focus();
  } catch (e) { m.className = 'msg err'; m.textContent = '连接失败，确认手机和电视在同一 Wi-Fi'; }
  go.disabled = false;
}
go.onclick = submit;
el.addEventListener('keydown', e => { if (e.key === 'Enter') submit(); });
</script>
</body></html>
"""
}
