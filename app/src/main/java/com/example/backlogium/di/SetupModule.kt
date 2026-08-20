package com.example.backlogium.di

import com.example.backlogium.data.setup.DataStoreSetupStateStore
import com.example.backlogium.data.setup.SetupStateStore
import com.example.backlogium.work.setup.SetupStageRegistry
import com.example.backlogium.work.setup.SetupStageSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the two seams first-run setup is built on: where its stages come from, and where their
 * outcomes are stored. Both are interfaces so a test can register its own stages and assert that
 * every surface picks them up without being changed.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SetupModule {

    @Binds
    abstract fun bindSetupStageSource(impl: SetupStageRegistry): SetupStageSource

    @Binds
    abstract fun bindSetupStateStore(impl: DataStoreSetupStateStore): SetupStateStore
}
