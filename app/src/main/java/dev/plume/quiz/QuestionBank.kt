package dev.plume.quiz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * Deals the bundled bank plus the parents' custom questions as shuffled, non-repeating decks.
 * Also owns the maintenance state: manually or automatically disabled questions, and how many
 * times each question was answered correctly (a question retires itself once mastered).
 */
class QuestionBank(private val context: Context) {
    private val prefs get() = context.getSharedPreferences(QuizPrefs.NAME, Context.MODE_PRIVATE)
    private val stats get() = context.getSharedPreferences(STATS_NAME, Context.MODE_PRIVATE)

    /** Bundled bank, one shard per age, parsed lazily so a quiz never pays for ages it does not use. */
    private val builtinShards = HashMap<Int, List<Question>>()
    @Volatile private var overriddenIds: Set<String> = emptySet()
    @Volatile private var overridesCache: Map<String, JSONObject>? = null
    @Volatile private var customCache: List<Question>? = null
    @Volatile private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) reload()
        }
    }

    /** Re-reads custom questions and bundled overrides from disk; called after the admin console changes them. */
    @Synchronized
    fun reload() {
        val overrides = loadOverrides()
        overridesCache = overrides
        overriddenIds = overrides.keys
        customCache = loadCustom()
        synchronized(this) { builtinShards.clear() }
        loaded = true
    }

    private fun overrides(): Map<String, JSONObject> {
        overridesCache?.let { return it }
        synchronized(this) {
            overridesCache?.let { return it }
            val loadedOverrides = loadOverrides()
            overridesCache = loadedOverrides
            return loadedOverrides
        }
    }

    private fun custom(): List<Question> {
        customCache?.let { return it }
        synchronized(this) {
            customCache?.let { return it }
            val loadedCustom = loadCustom()
            customCache = loadedCustom
            return loadedCustom
        }
    }

    /** One age's bundled questions with overrides applied; parsed on first use and cached. */
    private fun shard(age: Int): List<Question> {
        ensureLoaded()
        val key = age.coerceIn(5, 15)
        synchronized(this) {
            builtinShards[key]?.let { return it }
            val loadedShard = loadShard(key, overrides())
            builtinShards[key] = loadedShard
            return loadedShard
        }
    }

    private fun builtin(age: Int?): List<Question> =
        age?.let { shard(it) } ?: (5..15).flatMap { shard(it) }

    private fun custom(age: Int?): List<Question> =
        age?.let { a -> custom().filter { it.age == a } } ?: custom()

    private fun questions(): List<Question> = builtin(null) + custom()

    fun count(age: Int): Int = (builtin(age) + custom(age)).count { it.age == age }

    /**
     * Distinct categories, optionally limited to one age, first-seen order (bundled first).
     * The virtual [CUSTOM_CATEGORY] is appended whenever custom questions exist: it stands for
     * every parent-written question, regardless of the category text typed when it was created.
     */
    fun categories(age: Int? = null): List<String> {
        val matching = (builtin(age) + custom(age)).filter { age == null || it.age == age }
        val result = matching.map { it.category }.distinct().toMutableList()
        if (matching.any { it.custom } && CUSTOM_CATEGORY !in result) result += CUSTOM_CATEGORY
        return result
    }

    /** The virtual [CUSTOM_CATEGORY] matches every custom question; any other name matches literally. */
    private fun matchesCategory(question: Question, category: String?): Boolean = when (category) {
        null -> true
        CUSTOM_CATEGORY -> question.custom
        else -> question.category == category
    }

    fun isDisabled(id: String): Boolean = id in disabledIds()

    fun disabledIds(): Set<String> = prefs.getStringSet(KEY_DISABLED, emptySet())!!.toSet()

    fun correctCount(id: String): Int = stats.getInt(KEY_CORRECT + id, 0)

    /**
     * Records a correct answer. Once a question has been answered right [AUTO_DISABLE_AFTER]
     * times it is retired automatically: the child has clearly mastered it.
     */
    fun registerCorrect(id: String) {
        val count = correctCount(id) + 1
        stats.edit().putInt(KEY_CORRECT + id, count).apply()
        if (count >= AUTO_DISABLE_AFTER && !isDisabled(id)) {
            setDisabled(setOf(id), true)
        }
    }

    /** Enables ([disabled] = false) or disables the given questions, for the maintenance page. */
    fun setDisabled(ids: Collection<String>, disabled: Boolean) {
        val current = disabledIds().toMutableSet()
        if (disabled) current += ids else current -= ids.toSet()
        prefs.edit().putStringSet(KEY_DISABLED, current).apply()
    }

    /** Every question passes [disabled]; the one-click switch on the maintenance page. */
    fun setAllDisabled(disabled: Boolean, age: Int? = null, category: String? = null) {
        if (!disabled && age == null && category == null) {
            prefs.edit().putStringSet(KEY_DISABLED, emptySet<String>()).apply()
            return
        }
        val ids = questions()
            .filter { (age == null || it.age == age) && matchesCategory(it, category) }
            .map { it.id }
        setDisabled(ids, disabled)
    }

    /** The maintenance list: optional filters, disabled state and correct counts included by the caller. */
    fun list(age: Int? = null, category: String? = null, onlyDisabled: Boolean? = null): List<Question> {
        val disabled = disabledIds()
        return (builtin(age) + custom(age)).filter { q ->
            (age == null || q.age == age) &&
                matchesCategory(q, category) &&
                (onlyDisabled == null || (q.id in disabled) == onlyDisabled)
        }
    }

    private fun validateQuestion(age: Int, category: String, prompt: String, options: List<String>, answerIndex: Int) {
        require(age in 5..15) { "age out of range" }
        require(category.isNotBlank()) { "category required" }
        require(prompt.isNotBlank()) { "prompt required" }
        require(options.size == 4 && options.all { it.isNotBlank() } && options.toSet().size == 4) { "four distinct options required" }
        require(answerIndex in options.indices) { "answer index out of range" }
    }

    /** One question from a bulk import file; field-level validation already done by the caller. */
    data class CustomDraft(
        val age: Int,
        val category: String,
        val prompt: String,
        val options: List<String>,
        val answerIndex: Int,
        val explanation: String,
        val grade: Int = 0,
        val difficulty: Int = 0,
    )

    /** Bulk import outcome: [imported] new questions, [skipped] duplicates. */
    data class ImportResult(val imported: Int, val skipped: Int)

    /**
     * Adds a batch of imported questions in one write. With [replace] the whole custom bank is
     * swapped for the batch (retired questions lose their stats and disabled state); otherwise the
     * batch appends, skipping prompts the custom bank already has. Duplicates inside the batch
     * itself are skipped either way. IDs get a "u-" prefix so they never clash with the bundled bank.
     */
    @Synchronized
    fun importCustom(batch: List<CustomDraft>, replace: Boolean): ImportResult {
        val old = loadCustom()
        val custom = if (replace) ArrayList<Question>(batch.size) else old.toMutableList()
        val seen = custom.map { dupKey(it.age, it.category, it.prompt) }.toHashSet()
        var imported = 0
        for (draft in batch) {
            validateQuestion(draft.age, draft.category, draft.prompt, draft.options, draft.answerIndex)
            if (!seen.add(dupKey(draft.age, draft.category.trim(), draft.prompt.trim()))) continue
            val id = "u-" + java.lang.Long.toHexString(System.currentTimeMillis()) + "-" +
                java.lang.Integer.toHexString(Random.nextInt()) + "-" + imported
            custom += Question(id, draft.age, draft.category.trim(), draft.prompt.trim(), draft.options.map { it.trim() }, draft.answerIndex, draft.explanation.trim(), custom = true, grade = draft.grade, difficulty = draft.difficulty)
            imported++
        }
        if (replace) {
            val kept = custom.map { it.id }.toHashSet()
            val removed = old.map { it.id }.filter { it !in kept }
            if (removed.isNotEmpty()) {
                setDisabled(removed, false)
                val editor = stats.edit()
                for (id in removed) editor.remove(KEY_CORRECT + id)
                editor.apply()
            }
        }
        saveCustom(custom)
        reload()
        return ImportResult(imported, batch.size - imported)
    }

    /** Two imported questions are the same when age, category and prompt all match. */
    private fun dupKey(age: Int, category: String, prompt: String): String =
        "$age|" + category.trim() + "|" + prompt.trim()

    /** Edits a parent-written question in place, keeping its id so stats and disabled state survive. */
    @Synchronized
    fun updateCustom(id: String, age: Int, category: String, prompt: String, options: List<String>, answerIndex: Int, explanation: String): Boolean {
        validateQuestion(age, category, prompt, options, answerIndex)
        val custom = loadCustom().toMutableList()
        val index = custom.indexOfFirst { it.id == id }
        if (index < 0) return false
        custom[index] = custom[index].copy(age = age, category = category.trim(), prompt = prompt.trim(),
            options = options.map { it.trim() }, answerIndex = answerIndex, explanation = explanation.trim())
        saveCustom(custom)
        reload()
        return true
    }

    /** Removes a custom question; bundled questions can only be disabled, never deleted. */
    @Synchronized
    fun deleteCustom(id: String): Boolean {
        val custom = loadCustom()
        if (custom.none { it.id == id }) return false
        saveCustom(custom.filterNot { it.id == id })
        setDisabled(setOf(id), false)
        stats.edit().remove(KEY_CORRECT + id).apply()
        reload()
        return true
    }

    /** True while a bundled question is shadowed by a parent-edited override. */
    fun isOverridden(id: String): Boolean {
        ensureLoaded()
        return id in overriddenIds
    }

    /**
     * Overrides a bundled question with a parent-edited version. The bundled bank itself is never
     * touched: the edit lives in builtin_overrides.json under the original id, so the question's
     * stats and disabled state carry over. Returns false for custom or unknown ids.
     */
    @Synchronized
    fun saveOverride(id: String, age: Int, category: String, prompt: String, options: List<String>, answerIndex: Int, explanation: String): Boolean {
        validateQuestion(age, category, prompt, options, answerIndex)
        val home = ageOf(id)?.let { shard(it) } ?: questions()
        if (home.none { it.id == id && !it.custom }) return false
        val overrides = overrides().toMutableMap()
        overrides[id] = JSONObject()
            .put("age", age)
            .put("category", category.trim())
            .put("question", prompt.trim())
            .put("options", JSONArray(options.map { it.trim() }))
            .put("answerIndex", answerIndex)
            .put("explanation", explanation.trim())
        saveOverrides(overrides)
        reload()
        return true
    }

    /** Drops a bundled question's override, restoring the original text. */
    @Synchronized
    fun removeOverride(id: String): Boolean {
        val overrides = overrides().toMutableMap()
        if (overrides.remove(id) == null) return false
        saveOverrides(overrides)
        reload()
        return true
    }

    fun count(age: Int, categories: Set<String>): Int =
        pick(age, categories, includeDisabled = false).size

    fun eligibleCount(age: Int, categories: Set<String>, grade: Int, difficulty: Int): Int =
        if (grade == 0 && difficulty == 0) count(age, categories)
        else strictPool(age, categories, grade, difficulty).size

    private fun strictPool(age: Int, categories: Set<String>, grade: Int, difficulty: Int): List<Question> {
        val disabled = disabledIds()
        // The existing bundled bank is classified by age only.
        return custom().filter { question ->
            (if (grade == 0) question.age == age else question.grade == grade) &&
                (difficulty == 0 || question.difficulty == difficulty) &&
                (categories.isEmpty() || question.category in categories ||
                    (question.custom && CUSTOM_CATEGORY in categories)) &&
                question.id !in disabled
        }
    }

    @Synchronized
    fun nextFiltered(
        age: Int, categories: Set<String>, grade: Int, difficulty: Int,
        previousId: String?, completedIds: Set<String>, multiple: Boolean,
    ): Question? {
        if (grade == 0 && difficulty == 0 && !multiple) return next(age, categories, previousId)
        val pool = if (grade == 0 && difficulty == 0) pick(age, categories, includeDisabled = false)
            else strictPool(age, categories, grade, difficulty)
        return draw(pool.filterNot { it.id in completedIds }, "asked_${age}_${grade}_${difficulty}", previousId)
    }

    @Synchronized
    fun next(age: Int, categories: Set<String> = emptySet(), previousId: String? = null): Question {
        val pool = pick(age, categories, includeDisabled = false).ifEmpty {
            // The chosen categories have no questions at this age (e.g. 历史 starts at 11): stay within
            // the parents' explicit category choice across all ages before ever ignoring it.
            pickAnyAge(categories, includeDisabled = false).ifEmpty {
                pick(age, emptySet(), includeDisabled = false).ifEmpty { pick(age, emptySet(), includeDisabled = true) }
            }
        }
        return checkNotNull(draw(pool, "asked_$age", previousId)) { "No questions for age $age" }
    }

    private fun draw(pool: List<Question>, key: String, previousId: String?): Question? {
        if (pool.isEmpty()) return null
        val asked = prefs.getStringSet(key, emptySet())!!.toMutableSet()
        val poolIds = pool.map { it.id }.toSet()
        asked.retainAll(poolIds) // asked ids of disabled or deleted questions must not shrink the deck
        var candidates = pool.filter { it.id !in asked }
        if (candidates.isEmpty()) {
            asked.clear()
            candidates = pool
        }
        if (candidates.size > 1 && previousId != null) {
            candidates = candidates.filter { it.id != previousId }
        }
        val selected = candidates[Random.Default.nextInt(candidates.size)]
        asked += selected.id
        prefs.edit().putStringSet(key, asked).apply()
        return selected
    }

    private fun pick(age: Int, categories: Set<String>, includeDisabled: Boolean): List<Question> {
        val disabled = if (includeDisabled) emptySet() else disabledIds()
        return (builtin(age) + custom(age)).filter { q ->
            q.age == age.coerceIn(5, 15) &&
                (categories.isEmpty() || q.category in categories || (q.custom && CUSTOM_CATEGORY in categories)) &&
                q.id !in disabled
        }
    }

    /** Same filter as [pick] but without the age constraint: the last resort that still honours the chosen categories. */
    private fun pickAnyAge(categories: Set<String>, includeDisabled: Boolean): List<Question> {
        if (categories.isEmpty()) return emptyList()
        val disabled = if (includeDisabled) emptySet() else disabledIds()
        return questions().filter { q ->
            (q.category in categories || (q.custom && CUSTOM_CATEGORY in categories)) && q.id !in disabled
        }
    }

    private fun loadShard(age: Int, overrides: Map<String, JSONObject>): List<Question> {
        val text = context.assets.open("questions/age_$age.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONObject(text).getJSONArray("questions")
        val result = ArrayList<Question>(array.length())
        val ids = HashSet<String>(array.length())
        for (index in 0 until array.length()) {
            var question = parse(array.getJSONObject(index), custom = false)
            overrides[question.id]?.let { question = applyOverride(question, it) }
            check(ids.add(question.id)) { "Duplicate question id: ${question.id}" }
            result += question
        }
        check(result.count { it.age == age } >= 800) { "Too few questions for age $age" }
        return result
    }

    /** Bundled ids look like "a7-0123": the shard they live in is derivable from the id itself. */
    private fun ageOf(id: String): Int? =
        Regex("^a(\\d+)-").find(id)?.groupValues?.get(1)?.toIntOrNull()

    /** Applies a parent edit on top of a bundled question; broken fields fall back to the bundled values. */
    private fun applyOverride(question: Question, override: JSONObject): Question {
        val optionsJson = override.optJSONArray("options")
        val options = if (optionsJson != null && optionsJson.length() == 4) {
            List(4) { optionsJson.getString(it) }
        } else {
            question.options
        }
        if (options.toSet().size != 4) return question
        val answerIndex = override.optInt("answerIndex", question.answerIndex)
        return question.copy(
            age = override.optInt("age", question.age).coerceIn(5, 15),
            category = override.optString("category", question.category).trim().ifEmpty { question.category },
            prompt = override.optString("question", question.prompt).trim().ifEmpty { question.prompt },
            options = options,
            answerIndex = if (answerIndex in options.indices) answerIndex else question.answerIndex,
            explanation = override.optString("explanation", question.explanation),
        )
    }

    private fun customFile(): File = File(context.filesDir, "custom_questions.json")

    private fun overridesFile(): File = File(context.filesDir, "builtin_overrides.json")

    private fun loadOverrides(): Map<String, JSONObject> {
        val file = overridesFile()
        if (!file.exists()) return emptyMap()
        return try {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            obj.keys().asSequence().associateWith { obj.getJSONObject(it) }
        } catch (_: Exception) {
            emptyMap() // a corrupted override file must never break the bundled bank
        }
    }

    private fun saveOverrides(overrides: Map<String, JSONObject>) {
        val obj = JSONObject()
        for ((id, override) in overrides) obj.put(id, override)
        val tmp = File(context.filesDir, "builtin_overrides.json.tmp")
        tmp.writeText(obj.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(overridesFile())) {
            overridesFile().writeText(obj.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun loadCustom(): List<Question> {
        val file = customFile()
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            val result = ArrayList<Question>(array.length())
            val ids = HashSet<String>(array.length())
            for (index in 0 until array.length()) {
                val question = parse(array.getJSONObject(index), custom = true)
                if (ids.add(question.id)) result += question
            }
            result
        } catch (_: Exception) {
            emptyList() // a corrupted custom file must never break the bundled bank
        }
    }

    private fun saveCustom(questions: List<Question>) {
        val array = JSONArray()
        for (q in questions) {
            array.put(
                JSONObject()
                    .put("id", q.id)
                    .put("age", q.age)
                    .put("category", q.category)
                    .put("question", q.prompt)
                    .put("options", JSONArray(q.options))
                    .put("answerIndex", q.answerIndex)
                    .put("explanation", q.explanation)
                    .put("grade", q.grade)
                    .put("difficulty", q.difficulty),
            )
        }
        val tmp = File(context.filesDir, "custom_questions.json.tmp")
        tmp.writeText(array.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(customFile())) {
            customFile().writeText(array.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun parse(item: JSONObject, custom: Boolean): Question {
        val optionsJson = item.getJSONArray("options")
        val options = List(optionsJson.length()) { optionsJson.getString(it) }
        check(options.size == 4 && options.toSet().size == 4) { "Invalid options: ${item.getString("id")}" }
        val question = Question(
            id = item.getString("id"),
            age = item.getInt("age"),
            category = item.getString("category"),
            prompt = item.getString("question"),
            options = options,
            answerIndex = item.getInt("answerIndex"),
            explanation = item.optString("explanation"),
            custom = custom,
            grade = item.optInt("grade", 0).takeIf { it in 0..10 } ?: 0,
            difficulty = item.optInt("difficulty", 0).takeIf { it in 0..5 } ?: 0,
        )
        check(question.answerIndex in options.indices)
        return question
    }

    companion object {
        /** A question answered correctly this many times retires itself from the deck. */
        const val AUTO_DISABLE_AFTER = 5
        /** Virtual category standing for every parent-written question, in filters and quiz picking. */
        const val CUSTOM_CATEGORY = "自定义"
        private const val STATS_NAME = "grow_quiz_stats"
        private const val KEY_DISABLED = "disabled_ids"
        private const val KEY_CORRECT = "correct_"
    }
}
