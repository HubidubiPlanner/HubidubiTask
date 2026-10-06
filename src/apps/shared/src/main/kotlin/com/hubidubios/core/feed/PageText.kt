package com.hubidubios.core.feed

/**
 * The readable text of a web page, one block per line: what a person would
 * notice changing. Scripts, styles and markup go; block elements become line
 * breaks so a changed paragraph shows up as a changed line.
 */
object PageText {
    private val DROP = Regex("(?is)<(script|style|noscript|template|svg|head)\\b.*?</\\1\\s*>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val BLOCK = Regex("(?i)<\\s*(br|/p|/div|/li|/h[1-6]|/tr|/section|/article|/header|/footer|/blockquote|/pre|/dd|/dt|/table|/ul|/ol|/nav|/main|hr)\\b[^>]*>")
    private val TAG = Regex("(?s)<[^>]+>")
    private val SPACE = Regex("[ \\t\\u00A0]+")
    private val ENTITY = Regex("&(#\\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ndash" to "–", "mdash" to "—", "hellip" to "…", "rsquo" to "’",
        "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "copy" to "©", "euro" to "€")

    fun extract(html: String): String {
        var s = COMMENT.replace(html, " ")
        s = DROP.replace(s, " ")
        s = BLOCK.replace(s, "\n")
        s = TAG.replace(s, " ")
        s = ENTITY.replace(s) { m -> decode(m.groupValues[1]) ?: m.value }
        return s.lines().map { SPACE.replace(it, " ").trim() }.filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** The page's own name, from its <title>, if it has one. */
    fun title(html: String): String? =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
            ?.let { t -> ENTITY.replace(t) { m -> decode(m.groupValues[1]) ?: m.value } }
            ?.let { SPACE.replace(it.replace('\n', ' '), " ").trim() }?.takeIf { it.isNotEmpty() }

    private fun decode(e: String): String? = when {
        e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
        e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
        else -> NAMED[e.lowercase()]
    }
}

/** Lines that left and lines that arrived between two snapshots, in page order. */
data class LineChanges(val removed: List<String>, val added: List<String>) {
    val isEmpty: Boolean get() = removed.isEmpty() && added.isEmpty()

    companion object {
        fun between(old: String, new: String): LineChanges {
            val before = old.lines().filter { it.isNotBlank() }
            val after = new.lines().filter { it.isNotBlank() }
            val had = before.toHashSet(); val has = after.toHashSet()
            return LineChanges(
                removed = before.filter { it !in has }.distinct(),
                added = after.filter { it !in had }.distinct(),
            )
        }
    }
}
