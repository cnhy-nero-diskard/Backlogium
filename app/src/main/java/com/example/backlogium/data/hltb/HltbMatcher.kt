package com.example.backlogium.data.hltb

import com.example.backlogium.data.local.entity.HltbMatchStatus

/**
 * Pure name-matching heuristic. Scores each HowLongToBeat candidate against the Steam name
 * with a normalized edit-distance similarity, then classifies:
 *
 * - a single sufficiently-confident, dominant candidate → [HltbMatchStatus.RESOLVED];
 * - candidates present but ambiguous or low-confidence → [HltbMatchStatus.NEEDS_REVIEW]
 *   (candidates retained, scored, for the review surface);
 * - no candidates at all → [HltbMatchStatus.UNMATCHED].
 *
 * Thresholds are tuned conservatively so borderline matches defer to the user rather than
 * silently assigning a wrong completion length.
 */
object HltbMatcher {

    /** Minimum similarity for the top candidate to auto-resolve. */
    const val CONFIDENT_THRESHOLD = 0.85

    /** The top candidate must beat the runner-up by at least this margin to auto-resolve. */
    const val DOMINANCE_MARGIN = 0.15

    sealed interface Classification {
        val status: HltbMatchStatus

        data class Resolved(val chosen: HltbCandidate) : Classification {
            override val status = HltbMatchStatus.RESOLVED
        }

        data class NeedsReview(val candidates: List<HltbCandidate>) : Classification {
            override val status = HltbMatchStatus.NEEDS_REVIEW
        }

        data object Unmatched : Classification {
            override val status = HltbMatchStatus.UNMATCHED
        }
    }

    fun classify(query: String, candidates: List<HltbCandidate>): Classification {
        if (candidates.isEmpty()) return Classification.Unmatched

        val scored = scored(query, candidates)

        val top = scored.first()
        val runnerUp = scored.getOrNull(1)
        val confident = top.confidence >= CONFIDENT_THRESHOLD
        val dominant = runnerUp == null || top.confidence - runnerUp.confidence >= DOMINANCE_MARGIN

        return if (confident && dominant) {
            Classification.Resolved(top)
        } else {
            Classification.NeedsReview(scored)
        }
    }

    /** Score and rank every candidate without applying an automatic classification. */
    fun scored(query: String, candidates: List<HltbCandidate>): List<HltbCandidate> = candidates
        .map { it.copy(confidence = similarity(query, it.name)) }
        .sortedByDescending { it.confidence }

    /**
     * Extended scoring for broader-search candidates: combines normalized edit similarity
     * with token overlap, core-title containment, low-value edition terms, and a strong
     * conflicting-sequel-number penalty. Scoring is always against the original Steam title.
     * Every broader-search result still requires manual review regardless of score.
     */
    fun scoredBroader(originalTitle: String, candidates: List<HltbCandidate>): List<HltbCandidate> {
        val normalizedOriginal = normalize(originalTitle)
        val originalTokens = tokenSet(normalizedOriginal)
        val coreTitle = extractCoreTitle(normalizedOriginal)
        return candidates.map { candidate ->
            var score = similarity(originalTitle, candidate.name)
            // Token overlap (Jaccard-ish)
            val candidateTokens = tokenSet(normalize(candidate.name))
            val overlap = if (originalTokens.isEmpty() || candidateTokens.isEmpty()) 0.0 else {
                val inter = originalTokens.intersect(candidateTokens).size.toDouble()
                val union = originalTokens.union(candidateTokens).size.toDouble()
                if (union == 0.0) 0.0 else inter / union
            }
            score = score * 0.6 + overlap * 0.3

            // Core-title containment bonus
            if (coreTitle.isNotEmpty() && normalize(candidate.name).contains(coreTitle)) {
                score += 0.05
            }

            // Low-value edition terms carry little weight: if candidate has them and original doesn't, slight penalty
            if (hasEditionTerm(candidate.name) && !hasEditionTerm(originalTitle)) {
                score -= 0.03
            }

            // Strong conflicting-sequel-number penalty
            val origNum = trailingNumber(normalizedOriginal)
            val candNum = trailingNumber(normalize(candidate.name))
            if (origNum != null && candNum != null && origNum != candNum) {
                score -= 0.35
            }

            score = score.coerceIn(0.0, 1.0)
            candidate.copy(confidence = score, source = HltbCandidateSource.BROADER_SEARCH)
        }.sortedByDescending { it.confidence }
    }

    /**
     * Cross-query HLTB-id deduplication: retains the richest candidate payload and marks every
     * merged result as BROADER_SEARCH.
     */
    fun deduplicateBroader(candidates: List<HltbCandidate>): List<HltbCandidate> {
        val byId = linkedMapOf<Long, HltbCandidate>()
        for (c in candidates) {
            val existing = byId[c.hltbId]
            if (existing == null) {
                byId[c.hltbId] = c.copy(source = HltbCandidateSource.BROADER_SEARCH)
            } else {
                // Keep richest: most non-null lengths, then larger confidence, then image present
                val existingRichness = richness(existing)
                val newRichness = richness(c)
                val chosen = when {
                    newRichness > existingRichness -> c
                    newRichness < existingRichness -> existing
                    c.confidence > existing.confidence -> c
                    else -> existing
                }
                byId[c.hltbId] = chosen.copy(source = HltbCandidateSource.BROADER_SEARCH)
            }
        }
        return byId.values.toList()
    }

    private fun richness(c: HltbCandidate): Int =
        listOf(c.mainStoryMinutes, c.mainExtraMinutes, c.completionistMinutes, c.allStylesMinutes).count { it != null } * 10 +
            (if (c.imageUrl != null) 1 else 0)

    private fun tokenSet(normalized: String): Set<String> =
        normalized.split(WHITESPACE_REGEX).filter { it.isNotBlank() && it.length > 1 }.toSet()

    private fun extractCoreTitle(normalized: String): String {
        // Core is the substring before trailing number
        val match = Regex("""^(.*\D)\d+\s*$""").find(normalized)
        return (match?.groupValues?.getOrNull(1) ?: normalized).trim()
    }

    private fun hasEditionTerm(name: String): Boolean {
        val lower = normalize(name)
        return EDITION_TOKENS.any { lower.contains(it) }
    }

    private val EDITION_TOKENS = setOf(
        "enhanced edition", "definitive edition", "game of the year", "goty", "remastered",
        "complete edition", "deluxe edition", "ultimate edition",
    )

    // Terminal Roman sequel numeral on an already-normalized (lowercase) title
    private val TRAILING_ROMAN_NUMERAL_REGEX = Regex("""(.*\s)?([ivxlcdm]+)$""")

    private fun trailingNumber(normalized: String): Int? {
        // Terminal Roman numerals count for the conflict comparison too
        // (e.g. "final fantasy vii" vs "final fantasy viii").
        TRAILING_ROMAN_NUMERAL_REGEX.find(normalized)?.let { match ->
            HltbQueryGenerator.ROMAN_TO_ARABIC[match.groupValues[2].uppercase()]?.let { return it }
        }
        val match = Regex("""(\d+)\s*$""").find(normalized) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /** Normalized similarity in 0.0..1.0 (1.0 = identical after normalization). */
    fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val distance = levenshtein(na, nb)
        val longest = maxOf(na.length, nb.length)
        return 1.0 - distance.toDouble() / longest
    }

    /** Lowercase, strip trademark glyphs/punctuation, collapse whitespace. */
    fun normalize(s: String): String = s
        .lowercase()
        .replace(TRADEMARK_REGEX, "")
        .replace(NON_ALNUM_REGEX, " ")
        .trim()
        .replace(WHITESPACE_REGEX, " ")

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1, // insertion
                    prev[j] + 1, // deletion
                    prev[j - 1] + cost, // substitution
                )
            }
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return prev[b.length]
    }

    private val TRADEMARK_REGEX = Regex("[™®©]")
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
    private val WHITESPACE_REGEX = Regex("\\s+")
}
