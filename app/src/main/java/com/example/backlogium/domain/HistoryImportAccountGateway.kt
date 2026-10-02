package com.example.backlogium.domain

/**
 * Narrow, JVM-testable window onto the account the history import may act for.
 *
 * The import revalidates account identity and the account-change admission barrier at its commit
 * boundary (stabilize-first-run-setup, task 5.2): credentials alone do not grant a baseline, and a
 * durable account reset in flight must never let an old account's request land on the replacement
 * account. An interface so the domain use case stays constructible on the JVM; the production
 * implementation reads the credential store and the account-change marker store.
 */
interface HistoryImportAccountGateway {

    /** The currently active Steam account, or null when no credentials are configured. */
    suspend fun activeSteamId(): String?

    /**
     * The Steam account id a durable account-change reset is waiting on, or null when no account
     * change is pending (mirrors [com.example.backlogium.data.credentials.AccountChangeMarkerStore]).
     */
    suspend fun pendingResetSteamId(): String?
}

/**
 * No account context at all. Used by manual construction sites (tests, legacy wiring) that never
 * exercise the account/readiness boundary — an import built on this gateway reports every request
 * as [HistoryImportResult.Superseded].
 */
object UnavailableHistoryImportAccountGateway : HistoryImportAccountGateway {
    override suspend fun activeSteamId(): String? = null
    override suspend fun pendingResetSteamId(): String? = null
}