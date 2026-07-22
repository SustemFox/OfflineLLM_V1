package com.example.offlinellm.domain.model

/**
 * Split model output into optional thinking + visible answer.
 *
 * Small models (0.5B) often:
 * - emit multiple <think> blocks
 * - leave unclosed tags
 * - put tags mid-sentence / after Chinese / with spaces: </ think>
 * - repeat empty <think></think>
 *
 * We strip ALL think-like markup from the visible answer and collect thinking text.
 */
object ResponseParser {

    data class Parts(
        val thinking: String?,
        val answer: String,
        val thinkingComplete: Boolean
    )

    private val TAG_NAMES = listOf("think", "thinking", "reasoning", "thought", "reflection")

    /** <think>...</think> (allow spaces inside tag: </ think>) */
    private val PAIR_RE = Regex(
        """(?is)<\s*(think|thinking|reasoning|thought|reflection)\s*>(.*?)<\s*/\s*\1\s*>"""
    )
    private val OPEN_RE = Regex(
        """(?is)<\s*(think|thinking|reasoning|thought|reflection)\s*>"""
    )
    private val CLOSE_RE = Regex(
        """(?is)<\s*/\s*(think|thinking|reasoning|thought|reflection)\s*>"""
    )
    /** orphan bare tags without content */
    private val ANY_TAG_RE = Regex(
        """(?is)<\s*/?\s*(think|thinking|reasoning|thought|reflection)\s*>"""
    )

    fun parse(raw: String, showThinking: Boolean): Parts {
        if (raw.isEmpty()) return Parts(null, "", true)

        val thinkChunks = mutableListOf<String>()
        var work = raw
        var thinkingComplete = true

        // 1) Extract all well-formed pairs (repeat until stable)
        var guard = 0
        while (guard++ < 32) {
            val m = PAIR_RE.find(work) ?: break
            val body = m.groupValues[2].trim()
            if (body.isNotEmpty()) thinkChunks += body
            work = work.removeRange(m.range)
        }

        // 2) Unclosed open tag → rest is still "thinking" while streaming
        val open = OPEN_RE.find(work)
        if (open != null) {
            val afterOpen = work.substring(open.range.last + 1)
            val close = CLOSE_RE.find(afterOpen)
            if (close == null) {
                // still streaming thinking
                val thinkPart = afterOpen.trim()
                if (thinkPart.isNotEmpty()) thinkChunks += thinkPart
                val before = work.substring(0, open.range.first)
                work = before
                thinkingComplete = false
            } else {
                // open...close with odd formatting already partially handled; strip leftover open..close
                val body = afterOpen.substring(0, close.range.first).trim()
                if (body.isNotEmpty()) thinkChunks += body
                work = work.substring(0, open.range.first) + afterOpen.substring(close.range.last + 1)
            }
        }

        // 3) Strip any remaining orphan tags from answer
        work = ANY_TAG_RE.replace(work, "")

        // 4) Collapse whitespace noise from tag removal
        val answer = work
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex(" ?\\n ?"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        val thinkingJoined = thinkChunks
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n\n")
            .ifBlank { null }

        // If after stripping there's no answer but we had thinking and generation finished,
        // show a short fallback so the bubble isn't empty-looking forever.
        val finalAnswer = when {
            answer.isNotEmpty() -> answer
            !thinkingComplete -> "" // still streaming
            // Think-only completion (common on Qwen3/3.5 if stop fired early or /no_think ignored)
            thinkingJoined != null -> {
                // Prefer last paragraph of thinking as visible answer so bubble is never blank
                val lines = thinkingJoined.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
                lines.lastOrNull() ?: thinkingJoined.take(400)
            }
            else -> answer
        }

        val cleanedAnswer = collapseRepeatedParagraphs(finalAnswer)
        return Parts(
            thinking = if (showThinking) thinkingJoined else null,
            answer = cleanedAnswer,
            thinkingComplete = thinkingComplete
        )
    }

    
    /** Remove consecutive / alternating duplicate paragraphs from visible answer. */
    fun collapseRepeatedParagraphs(text: String): String {
        if (text.length < 40) return text
        val paras = text
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paras.size <= 1) return text.trim()

        val out = ArrayList<String>()
        for (p in paras) {
            if (out.isNotEmpty() && out.last() == p) continue
            if (out.size >= 2 && out[out.size - 2] == p) continue
            out.add(p)
        }
        // A B A B → A B
        if (out.size >= 4 && out[0] == out[2] && out[1] == out[3]) {
            return listOf(out[0], out[1]).joinToString("\n\n")
        }
        return out.joinToString("\n\n")
    }

    /** Hard cleanup for display of already-stored messages (history). */
    fun stripThinkTags(text: String): String =
        collapseRepeatedParagraphs(ANY_TAG_RE.replace(PAIR_RE.replace(text, ""), "").trim())
}
