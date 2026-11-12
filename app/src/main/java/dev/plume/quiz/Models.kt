package dev.plume.quiz

/** One immutable multiple-choice question. The answer index is zero based. */
data class Question(
    val id: String,
    val age: Int,
    val category: String,
    val prompt: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String,
    /** True for questions written by parents through the admin console, false for the bundled bank. */
    val custom: Boolean = false,
    val term: Int = 0, // 0 = unclassified, 1 = first volume, 2 = second volume
    val grade: Int = 0, // 0 = unclassified, 10 = kindergarten senior class
    val difficulty: Int = 0, // 0 = unclassified, 1–5 = increasing difficulty
)
