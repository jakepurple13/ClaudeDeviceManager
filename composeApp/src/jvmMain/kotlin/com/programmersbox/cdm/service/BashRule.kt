package com.programmersbox.cdm.service

/** One `Bash(...)` permission rule from a Claude Code settings file, matched against a single command segment. */
sealed interface BashRule {
    fun matches(segment: String): Boolean

    data object All : BashRule {
        override fun matches(segment: String) = true
    }

    data class Exact(val command: String) : BashRule {
        override fun matches(segment: String) = segment.trim() == command
    }

    /** `Bash(x:*)`: [prefix] alone, or [prefix] followed by whitespace and anything. A `*` inside [prefix] is a glob. */
    data class Prefix(val prefix: String) : BashRule {
        private val regex = Regex("(?:${globRegex(prefix)})(?:\\s.*)?", RegexOption.DOT_MATCHES_ALL)
        override fun matches(segment: String) = regex.matches(segment.trim())
    }

    /** Any other pattern containing `*`; `*` matches any characters, everything else is literal. */
    data class Glob(val pattern: String) : BashRule {
        private val regex = Regex(globRegex(pattern), RegexOption.DOT_MATCHES_ALL)
        override fun matches(segment: String) = regex.matches(segment.trim())
    }

    companion object {
        /** Null for anything that is not a well-formed Bash rule (other tools, unbalanced, empty). */
        fun parse(rule: String): BashRule? {
            val r = rule.trim()
            if (r == "Bash") return All
            if (!r.startsWith("Bash(") || !r.endsWith(")")) return null
            val body = r.substring(5, r.length - 1).trim()
            if (body.isEmpty()) return null
            return when {
                body.endsWith(":*") -> Prefix(body.removeSuffix(":*").trim()).takeIf { it.prefix.isNotEmpty() } ?: All
                '*' in body -> Glob(body)
                else -> Exact(body)
            }
        }

        private fun globRegex(p: String) = p.split('*').joinToString(".*") { Regex.escape(it) }
    }
}
