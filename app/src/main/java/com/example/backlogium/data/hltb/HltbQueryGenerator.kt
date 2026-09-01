package com.example.backlogium.data.hltb

/**
 * Pure deterministic relaxed query generation for broader HLTB search.
 * Produces at most three additional queries from the original Steam title:
 * 1. remove recognized storefront/edition noise,
 * 2. reduce a subtitle after `:`, em/en dash, or spaced hyphen while retaining numbered core,
 * 3. remove a leading article and create one safe Arabic/Roman numeral alternative for a terminal sequel number.
 *
 * Empty, duplicate, and unchanged variants are discarded.
 */
object HltbQueryGenerator {

    private val EDITION_TERMS = listOf(
        // longest first so partial matches don't shadow longer ones
        "game of the year edition",
        "game of the year",
        "definitive edition",
        "enhanced edition",
        "complete edition",
        "deluxe edition",
        "ultimate edition",
        "premium edition",
        "collectors edition",
        "collector's edition",
        "special edition",
        "extended edition",
        "remastered",
        "goty",
    )

    // Platform/storefront markers that are low-value noise
    private val PLATFORM_TERMS = listOf(
        "steam edition",
        "windows edition",
    )

    private val BRACKET_REGEX = Regex("""\s*[\[\(][^\]\)]*[\]\)]\s*""")
    private val TRADEMARK_REGEX = Regex("[™®©]")

    // For subtitle reduction: split on the first of these separators
    private val SUBTITLE_SEPARATORS = listOf(
        Regex("""\s*:\s*"""),
        Regex("""\s*—\s*"""), // em dash
        Regex("""\s*–\s*"""), // en dash
        Regex("""\s+-\s+"""), // spaced hyphen
    )

    private val LEADING_ARTICLE_REGEX = Regex("""^(?i)(the|a|an)\s+""")

    private val TRAILING_ARABIC_REGEX = Regex("""^(.*\D)(\d+)\s*$""")
    private val TRAILING_ROMAN_REGEX = Regex("""^(.*\s)([IVXLCDM]+)\s*$""", RegexOption.IGNORE_CASE)

    private val ROMAN_TO_ARABIC = mapOf(
        "I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5,
        "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9, "X" to 10,
        "XI" to 11, "XII" to 12, "XIII" to 13, "XIV" to 14, "XV" to 15,
    )

    private val ARABIC_TO_ROMAN = ROMAN_TO_ARABIC.entries.associate { (k, v) -> v to k }

    /**
     * Generate ordered distinct variants that differ from the normalized primary query.
     * At most three results.
     */
    fun variants(original: String): List<String> {
        val primary = HltbMatcher.normalize(original)
        if (primary.isEmpty()) return emptyList()
        val results = linkedSetOf<String>()

        fun addIfValid(candidate: String) {
            val normalized = HltbMatcher.normalize(candidate)
            if (normalized.isEmpty()) return
            if (normalized == primary) return
            if (results.contains(normalized)) return
            if (results.size >= 3) return
            results.add(normalized)
        }

        // 1. Edition/storefront noise removal
        val withoutEdition = removeEditionNoise(original)
        if (withoutEdition != null) addIfValid(withoutEdition)

        // 2. Subtitle reduction (core without subtitle)
        val withoutSubtitle = reduceSubtitle(original)
        if (withoutSubtitle != null) addIfValid(withoutSubtitle)

        // 2b: combine edition removal then subtitle reduction
        if (withoutEdition != null) {
            val combined = reduceSubtitle(withoutEdition)
            if (combined != null) addIfValid(combined)
        }

        // 3. Leading article normalization + numeral alternative
        val withoutArticle = removeLeadingArticle(original)
        if (withoutArticle != null) addIfValid(withoutArticle)

        // Numeral alternative (terminal)
        val numeralAlt = numeralAlternative(original)
        if (numeralAlt != null) addIfValid(numeralAlt)

        // Numeral alternative on subtitle-reduced form
        if (withoutSubtitle != null) {
            val alt2 = numeralAlternative(withoutSubtitle)
            if (alt2 != null) addIfValid(alt2)
        }

        // Dedup and cap already handled; ordered insertion preserved
        return results.take(3)
    }

    /** Remove recognized edition/storefront noise while retaining meaningful base titles. */
    fun removeEditionNoise(title: String): String? {
        var cleaned = title
        // Strip trademark/bracket noise first
        cleaned = TRADEMARK_REGEX.replace(cleaned, "")
        cleaned = BRACKET_REGEX.replace(cleaned, " ")
        var changed = false
        val lower = cleaned.lowercase()
        for (term in EDITION_TERMS + PLATFORM_TERMS) {
            // Match term as a suffix or standalone: allow preceding space or start, followed by end
            val pattern = Regex("""(?i)(?:^|\s)${Regex.escape(term)}\s*$""")
            if (pattern.containsMatchIn(cleaned)) {
                cleaned = pattern.replace(cleaned, "")
                changed = true
            } else if (lower.contains(term)) {
                // Also handle inline occurrence bounded by word boundaries
                val inline = Regex("""(?i)\b${Regex.escape(term)}\b""")
                if (inline.containsMatchIn(cleaned)) {
                    cleaned = inline.replace(cleaned, " ")
                    changed = true
                }
            }
        }
        cleaned = cleaned.replace(Regex("""\s+"""), " ").trim()
        if (!changed) return null
        if (cleaned.isEmpty()) return null
        return cleaned
    }

    /** Reduce a subtitle after `:`, em/en dash, or spaced hyphen while retaining the numbered core. */
    fun reduceSubtitle(title: String): String? {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null
        for (sep in SUBTITLE_SEPARATORS) {
            val parts = sep.split(trimmed, limit = 2)
            if (parts.size == 2) {
                val core = parts[0].trim()
                val subtitle = parts[1].trim()
                if (core.isNotEmpty() && subtitle.isNotEmpty()) {
                    // Retain core; ensure we keep sequel numbers that are part of core
                    return core
                }
            }
        }
        return null
    }

    fun removeLeadingArticle(title: String): String? {
        val trimmed = title.trim()
        val match = LEADING_ARTICLE_REGEX.find(trimmed) ?: return null
        val without = trimmed.substring(match.value.length).trim()
        if (without.isEmpty()) return null
        return without
    }

    /** Safe terminal Arabic<->Roman alternative, one variant only. */
    fun numeralAlternative(title: String): String? {
        val trimmed = title.trim()
        // Try Arabic trailing
        val arabicMatch = TRAILING_ARABIC_REGEX.find(trimmed)
        if (arabicMatch != null) {
            val prefix = arabicMatch.groupValues[1]
            val num = arabicMatch.groupValues[2].toIntOrNull() ?: return null
            // Only 1..15 considered safe
            val roman = ARABIC_TO_ROMAN[num] ?: return null
            return (prefix + roman).trim()
        }
        val romanMatch = TRAILING_ROMAN_REGEX.find(trimmed)
        if (romanMatch != null) {
            val prefix = romanMatch.groupValues[1]
            val roman = romanMatch.groupValues[2].uppercase()
            val arabic = ROMAN_TO_ARABIC[roman] ?: return null
            // Avoid converting non-numeral words like "of" etc. Ensure roman is plausible: 1-15
            if (prefix.trim().isEmpty()) return null
            return (prefix + arabic.toString()).trim()
        }
        return null
    }
}
