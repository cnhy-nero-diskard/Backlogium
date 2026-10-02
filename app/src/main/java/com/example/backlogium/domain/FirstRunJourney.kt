package com.example.backlogium.domain

/** The user's decision journey is independent of scheduler and import-operation completion. */
enum class FirstRunPhase { SETUP, HISTORY_CHOICE, IMPORT_REQUESTED, COMPLETE, DEFERRED }

data class FirstRunJourney(
    val accountSteamId: String,
    val phase: FirstRunPhase,
    val importRequestId: String? = null,
) {
    val owed: Boolean get() = phase != FirstRunPhase.COMPLETE && phase != FirstRunPhase.DEFERRED
}
