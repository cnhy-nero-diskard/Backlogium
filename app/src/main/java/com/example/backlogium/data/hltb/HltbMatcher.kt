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

        val scored = candidates
            .map { it.copy(confidence = similarity(query, it.name)) }
            .sortedByDescending { it.confidence }

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
