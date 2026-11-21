package com.example.backlogium.data.backup

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the recovery backup seam to its storage implementation (recover-missed-playtime). Kept
 * beside the backup store rather than in the shared database module so the recovery tables' only
 * data-backup dependency stays in one file.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RecoveryBackupModule {

    @Binds
    @Singleton
    abstract fun bindRecoveryBackupStore(impl: DaoRecoveryBackupStore): RecoveryBackupStore
}
