package com.example.backlogium.domain.gapplan

import com.example.backlogium.domain.CollectionTimeBasis
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.displayedPlaytimeMinutes

/**
 * One visible library game as the engine needs it: plain values, already joined, with no lookup
 * left to perform.
 *
 * The four HLTB lengths arrive as they are stored — null where unresolved — because null and zero
 * are different answers and the distinction has to survive this far. [trackedMinutes] and
 * [manualSharedMinutes] are carried separately from [steamPlaytimeMinutes] so the source-aware
 * playtime rule can be applied here rather than approximated by the caller.
 */
data class GapPlanGame(
    val appId: Long,
    val name: String,
    val source: GameSource,
    val steamPlaytimeMinutes: Int,
    val trackedMinutes: Int,
    val manualSharedMinutes: Int,
    val mainStoryMinutes: Int?,
    val completionistMinutes: Int?,
    val genreIds: List<String>,
    /**
     * True only when cached participation categories positively say so. Unknown categories are
     * false here, which costs nothing: the flag gates an optional decoration, never membership.
     */
    val multiplayer: Boolean,
)

/**
 * The estimate for the selected basis, or null when there is none.
 *
 * A non-positive stored value is treated as *missing* rather than as zero work. A zero-minute
 * completion estimate is not a game that takes no time; it is an estimate that was never
 * resolved, and letting it through would make that game look free and rank it above everything.
 */
fun GapPlanGame.selectedEstimateMinutes(basis: CollectionTimeBasis): Int? = when (basis) {
    CollectionTimeBasis.MAIN_STORY -> mainStoryMinutes
    CollectionTimeBasis.COMPLETIONIST -> completionistMinutes
    // The gap-plan intents map only to the two above; the others are unreachable from a request
    // and are treated as uncovered rather than silently substituted with a different length.
    CollectionTimeBasis.MAIN_EXTRA, CollectionTimeBasis.ALL_STYLES -> null
}?.takeIf { it > 0 }

/**
 * The playtime the app is willing to show for this game — Steam's total for an owned game, and
 * tracked sessions plus the player's own estimate for a family-shared one.
 *
 * Reuses the existing domain rule rather than re-deriving it. A family-shared game has no Steam
 * playtime at all, so an owned-game rule applied to it would call every shared game unplayed and
 * plan its full length every time.
 */
fun GapPlanGame.displayedPlaytime(): Int = source.displayedPlaytimeMinutes(
    steamPlaytimeMinutes = steamPlaytimeMinutes,
    trackedMinutes = trackedMinutes,
    manualSharedMinutes = manualSharedMinutes,
)

/** How the eligibility pass classified one game — the input to both the pool and the coverage. */
private sealed interface Classification {
    data class Eligible(val remainingMinutes: Int, val estimateMinutes: Int, val playedMinutes: Int) :
        Classification

    /** Has an estimate, but nothing left to do at this basis. */
    data object Complete : Classification

    /** No usable estimate for the selected basis; unknown, never zero. */
    data object NoEstimate : Classification

    /** Excluded by the request's own unplayed/started selection, not by any library state. */
    data object ExcludedByRequest : Classification

    /** Has work left, but more than even the Full budget could hold. */
    data object DoesNotFit : Classification
}

/** The eligible pool plus the coverage facts the result surface has to disclose. */
data class EligibilityResult(
    val eligible: List<EligibleGame>,
    val coverage: GapPlanCoverage,
)

/** A game that passed eligibility, with the derived work figures the scorers need. */
data class EligibleGame(
    val game: GapPlanGame,
    val remainingMinutes: Int,
    val estimateMinutes: Int,
    val playedMinutes: Int,
)

/**
 * Derives the eligible pool for one request.
 *
 * Reads only what it is given. No HLTB request is issued, implied, or triggered: an unresolved
 * game contributes to the reported coverage gap and nothing else, which is what keeps the
 * explicit-target HLTB contract intact.
 *
 * [fullBudgetMinutes] is the *Full* variant's budget, used as the single fit ceiling. A game that
 * cannot fit the largest budget cannot fit any of them, so filtering once here keeps every variant
 * working from the same pool rather than three subtly different ones.
 *
 * [reviewsByAppId] and [genre knowledge] are not eligibility inputs at all — a game with neither
 * is still perfectly plannable — they are counted only so the result can say how well informed the
 * ranking was.
 */
fun deriveEligibility(
    games: List<GapPlanGame>,
    request: GapPlanRequest,
    fullBudgetMinutes: Int,
    hasCachedReview: (Long) -> Boolean,
): EligibilityResult {
    val basis = request.intent.timeBasis()
    val eligible = mutableListOf<EligibleGame>()
    var withEstimate = 0
    var missingEstimate = 0
    var alreadyComplete = 0

    for (game in games) {
        when (val classification = game.classify(request, basis, fullBudgetMinutes)) {
            is Classification.Eligible -> {
                withEstimate++
                eligible += EligibleGame(
                    game = game,
                    remainingMinutes = classification.remainingMinutes,
                    estimateMinutes = classification.estimateMinutes,
                    playedMinutes = classification.playedMinutes,
                )
            }
            Classification.Complete -> {
                withEstimate++
                alreadyComplete++
            }
            // Still has a usable estimate; it simply does not fit, which is not a coverage gap.
            Classification.DoesNotFit -> withEstimate++
            Classification.NoEstimate -> missingEstimate++
            // A request-scoped exclusion says nothing about what the library knows, so it is
            // counted only by whether an estimate exists.
            Classification.ExcludedByRequest ->
                if (game.selectedEstimateMinutes(basis) == null) missingEstimate++ else withEstimate++
        }
    }

    return EligibilityResult(
        eligible = eligible,
        coverage = GapPlanCoverage(
            visibleGames = games.size,
            withSelectedEstimate = withEstimate,
            missingSelectedEstimate = missingEstimate,
            alreadyComplete = alreadyComplete,
            withCachedReviews = games.count { hasCachedReview(it.appId) },
            withKnownGenres = games.count { it.genreIds.isNotEmpty() },
        ),
    )
}

private fun GapPlanGame.classify(
    request: GapPlanRequest,
    basis: CollectionTimeBasis,
    fullBudgetMinutes: Int,
): Classification {
    val estimate = selectedEstimateMinutes(basis) ?: return Classification.NoEstimate
    val played = displayedPlaytime()
    val started = played > 0
    if (started && !request.includeStarted) return Classification.ExcludedByRequest
    if (!started && !request.includeUnplayed) return Classification.ExcludedByRequest

    // Widened before subtracting: a legacy near-Int.MAX manual estimate must not wrap negative and
    // reappear as a tiny, irresistibly cheap candidate.
    val remaining = (estimate.toLong() - played.toLong()).coerceAtLeast(0L)
    if (remaining == 0L) return Classification.Complete
    if (remaining > fullBudgetMinutes.toLong()) return Classification.DoesNotFit
    return Classification.Eligible(
        remainingMinutes = remaining.toInt(),
        estimateMinutes = estimate,
        playedMinutes = played,
    )
}
