package com.example.offlinellm.domain.model

/**
 * Split model output into optional thinking + visible answer.
 * Supports <think>...</think>, <thinking>...</thinking>, and bare "Thinking:" prefixes.
 */
object ResponseParser {

    data class Parts(
        val thinking: String?,
        val answer: String,
        val thinkingComplete: Boolean
    )

    private val TAG_PAIRS = listOf(
        "think" to Regex("(?is)<think>(.*?)</think>\\s*"),
        "thinking" to Regex("(?is)<thinking>(.*?)</thinking>\\s*"),
        "reasoning" to Regex("(?is)<reasoning>(.*?)</reasoning>\\s*"),
    )

    fun parse(raw: String, showThinking: Boolean): Parts {
        if (raw.isEmpty()) return Parts(null, "", true)

        for ((_, re) in TAG_PAIRS) {
            val m = re.find(raw)
            if (m != null) {
                val think = m.groupValues[1].trim()
                val answer = raw.replace(m.value, "").trim()
                return Parts(
                    thinking = if (showThinking) think.ifBlank { null } else null,
                    answer = answer.ifBlank { if (!showThinking) raw.trim() else "" },
                    thinkingComplete = true
                )
            }
        }

        // Open think tag still streaming
        val open = Regex("(?is)<(think|thinking|reasoning)>")
        val om = open.find(raw)
        if (om != null) {
            val after = raw.substring(om.range.last + 1)
            val closeName = om.groupValues[1]
            val closeIdx = after.indexOf("</$closeName>", ignoreCase = true)
            if (closeIdx < 0) {
                return Parts(
                    thinking = if (showThinking) after.trim() else null,
                    answer = if (showThinking) "" else raw,
                    thinkingComplete = false
                )
            }
        }

        return Parts(thinking = null, answer = raw.trim(), thinkingComplete = true)
    }
}
