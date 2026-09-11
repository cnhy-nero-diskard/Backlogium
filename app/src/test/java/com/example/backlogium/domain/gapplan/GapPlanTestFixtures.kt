package com.example.backlogium.domain.gapplan

import com.example.backlogium.domain.DatedPlayTotal
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.PersonalPace
import com.example.backlogium.domain.PersonalPaceProfile
import java.time.LocalDate

/** The date every gap-plan test treats as "today", so no test depends on the wall clock. */
internal val TODAY: LocalDate = LocalDate.parse("2026-09-11")

/**
 * A game with everything the engine reads, defaulted to the simplest useful shape: owned,
 * unplayed, with both HLTB lengths resolved. Each test overrides only the facts it is about.
 */
internal fun gapGame(
    appId: Long,
    name: String = "Game $appId",
    source: GameSource = GameSource.STEAM_OWNED,
    steamPlaytimeMinutes: Int = 0,
    trackedMinutes: Int = 0,
    manualSharedMinutes: Int = 0,
    mainStoryMinutes: Int? = 600,
    completionistMinutes: Int? = 1_800,
    genreIds: List<String> = listOf("1"),
    multiplayer: Boolean = false,
) = GapPlanGame(
    appId = appId,
    name = name,
    source = source,
    steamPlaytimeMinutes = steamPlaytimeMinutes,
    trackedMinutes = trackedMinutes,
    manualSharedMinutes = manualSharedMinutes,
    mainStoryMinutes = mainStoryMinutes,
    completionistMinutes = completionistMinutes,
    genreIds = genreIds,
    multiplayer = multiplayer,
)

internal fun gapRequest(
    title: String = "Anticipated Game",
    targetDate: LocalDate = TODAY.plusDays(60),
    intent: GapPlanIntent = GapPlanIntent.STORY,
    includeUnplayed: Boolean = true,
    includeStarted: Boolean = true,
    manualTotalHours: Int? = null,
) = GapPlanRequest(
    anticipatedTitle = title,
    targetDate = targetDate,
    intent = intent,
    includeUnplayed = includeUnplayed,
    includeStarted = includeStarted,
    manualTotalHours = manualTotalHours,
)

/**
 * A reliable profile built through the real derivation rather than hand-constructed, so the tests
 * that depend on reliability depend on the same rule the app does. 40 consecutive active days of
 * [minutesPerDay] clears both reliability thresholds comfortably.
 */
internal fun reliablePace(minutesPerDay: Int = 120, today: LocalDate = TODAY): PersonalPaceProfile =
    PersonalPace.derive(
        totals = (1..40).map { DatedPlayTotal(today.minusDays(it.toLong()), minutesPerDay) },
        today = today,
    )

/** A profile with too little history to make a feasibility claim. */
internal fun learningPace(today: LocalDate = TODAY): PersonalPaceProfile =
    PersonalPace.derive(
        totals = listOf(DatedPlayTotal(today.minusDays(1), 60)),
        today = today,
    )

/** A candidate built directly, for composer tests that are about packing rather than scoring. */
internal fun candidate(
    appId: Long,
    remainingMinutes: Int,
    quality: Double = 0.5,
    genreIds: List<String> = listOf("1"),
    multiplayer: Boolean = false,
) = GapPlanCandidate(
    appId = appId,
    name = "Game $appId",
    source = GameSource.STEAM_OWNED,
    remainingMinutes = remainingMinutes,
    estimateMinutes = remainingMinutes,
    playedMinutes = 0,
    genreIds = genreIds,
    multiplayer = multiplayer,
    // The three components are chosen so the weighted sum is exactly `quality`, letting a packing
    // test state the candidate quality it means instead of solving for it.
    reviewQuality = quality,
    genreAffinity = quality,
    completionMomentum = quality,
    reasons = listOf(GapPlanReason.Fit(remainingMinutes)),
)
