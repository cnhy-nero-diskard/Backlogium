package com.example.backlogium.domain

import com.example.backlogium.gamification.RuleConfig

/** A rule snapshot plus the monotonic DataStore version that names it. */
data class VersionedRuleConfig(
    val config: RuleConfig,
    val version: Long,
)
