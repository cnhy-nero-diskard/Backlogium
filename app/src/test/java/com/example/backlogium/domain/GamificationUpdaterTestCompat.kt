package com.example.backlogium.domain

import com.example.backlogium.gamification.RuleConfig
import java.time.LocalDate

/**
 * Keeps pre-progress-events tests focused on gamification values while production callers are
 * forced to declare provenance. New provenance tests call the required-source API directly.
 */
suspend fun GamificationUpdater.recompute(today: LocalDate, config: RuleConfig) =
    recompute(today = today, source = RecomputeSource.SYNC, config = config)

suspend fun GamificationUpdater.persist(result: GamificationResult) =
    persist(result = result, source = RecomputeSource.SYNC)
