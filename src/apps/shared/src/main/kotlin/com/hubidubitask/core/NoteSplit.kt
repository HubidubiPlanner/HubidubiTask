package com.hubidubitask.core

/**
 * Words that came from somewhere else, a photo read by the agent or a note
 * spoken aloud, made into a note's title and body. Nothing said is lost: a
 * title cut short keeps the whole text below it.
 */
object NoteSplit {
    data class Note(val title: String, val body: String)

    /** The longest title kept whole. */
    const val MAX_TITLE = 80

    private val HEADING = Regex("^(#+\\s*|title\\s*:\\s*)", RegexOption.IGNORE_CASE)
    private val SENTENCE = Regex("^(.{1,$MAX_TITLE}?[.!?…])\\s+(.+)$", RegexOption.DOT_MATCHES_ALL)

    /**
     * The first line as the title, cleaned of the markup a model may put on
     * it, and the rest as the body. One long run of words (dictation has no
     * lines) gives its first sentence instead, or its first words when there
     * is no short sentence. Null when there are no words at all.
     */
    fun titleAndBody(text: String): Note? {
        val lines = text.trim().removePrefix("```").removeSuffix("```").trim().lines()
        val at = lines.indexOfFirst { it.isNotBlank() }
        if (at < 0) return null
        val first = clean(lines[at])
        val rest = lines.drop(at + 1).joinToString("\n").trim()
        // Markup alone ("**", "---") is no title.
        if (first.none { it.isLetterOrDigit() }) return titleAndBody(rest)
        if (first.length <= MAX_TITLE) return Note(first, rest)
        val whole = (listOf(first) + rest).filter { it.isNotEmpty() }.joinToString("\n")
        SENTENCE.find(first)?.let { m ->
            val body = (listOf(m.groupValues[2].trim()) + rest).filter { it.isNotEmpty() }.joinToString("\n")
            return Note(m.groupValues[1].trimEnd('.'), body)
        }
        val cut = first.take(MAX_TITLE).substringBeforeLast(' ').ifBlank { first.take(MAX_TITLE) }
        return Note("$cut…", whole)
    }

    private fun clean(line: String): String =
        line.trim().replace(HEADING, "").removeSurrounding("**").removeSurrounding("\"").trim()
}
