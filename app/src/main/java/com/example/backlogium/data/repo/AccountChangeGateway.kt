package com.example.backlogium.data.repo

/**
 * Narrow view of [AccountChangeCoordinator] for the onboarding identity-change confirmation: the
 * one apply the flow drives. An interface so the flow can be tested on the JVM without the
 * coordinator's Room + worker graph.
 */
interface AccountChangeGateway {
    suspend fun apply(apiKey: String, steamId: String)
}
