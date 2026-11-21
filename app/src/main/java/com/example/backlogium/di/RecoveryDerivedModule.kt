package com.example.backlogium.di

import com.example.backlogium.domain.GamificationRecoveryDerivedCompute
import com.example.backlogium.domain.RecoveryDerivedCompute
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the staged compute/persist port to the real [GamificationUpdater]-backed implementation so
 * [com.example.backlogium.domain.RecoveryDerivedFinalizer] is fully `@Inject`-constructible.
 * There is exactly one implementation on device; a test that needs determinism constructs the
 * finalizer directly with a fake [RecoveryDerivedCompute] instead of going through Hilt.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RecoveryDerivedModule {
    @Binds
    abstract fun bindRecoveryDerivedCompute(
        impl: GamificationRecoveryDerivedCompute,
    ): RecoveryDerivedCompute
}