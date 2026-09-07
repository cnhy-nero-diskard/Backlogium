package com.example.backlogium.work.setup

import kotlinx.coroutines.flow.Flow

/**
 * Narrow view of [SetupCoordinator] for the onboarding flow: the durable first-run takeover the
 * flow claims and releases, not the stage run itself. An interface so the flow can be tested on
 * the JVM with an in-memory flag rather than the coordinator's stage graph.
 */
interface FirstRunSetupGateway {
    val firstRunSetupActive: Flow<Boolean>

    suspend fun claimFirstRunSetup()

    suspend fun releaseFirstRunSetup()
}
