package com.example.backlogium.domain

/**
 * The shared, observable state of the explicit Steam-history import, projected for onboarding and
 * Settings (stabilize-first-run-setup, task 5.1). Replaces UI-only busy flags with one flow both
 * surfaces render, so a visible busy state and a settled result can never disagree.
 *
 * Running and Completed are attributable to the exact consent that produced them ([requestId] and
 * [accountSteamId]) so the parent first-run-phase store can transition its own phase on the exact
 * request and never attribute one account's completion to another.
 */
sealed interface HistoryImportState {

    /** No explicit import is admitted and no recent completion is pending presentation. */
    data object Idle : HistoryImportState

    /**
     * An explicit consent is durably recorded and its operation is admitted and running. State is
     * only ever emitted *after* the consent lands in the request store, so observing [Running]
     * means the request survives process death and the phase store may record
     * IMPORT_REQUESTED/record the request.
     */
    data class Running(
        val requestId: String,
        val accountSteamId: String,
    ) : HistoryImportState

    /**
     * The admitted operation settled with [result], attributed to the exact request/account that
     * produced it, retained until the next admission.
     */
    data class Completed(
        val result: HistoryImportResult,
        val requestId: String,
        val accountSteamId: String,
    ) : HistoryImportState
}