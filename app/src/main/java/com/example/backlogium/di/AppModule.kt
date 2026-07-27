package com.example.backlogium.di

import com.example.backlogium.data.repo.DataStoreSettingsRepository
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.domain.SystemTimeProvider
import com.example.backlogium.domain.TimeProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    companion object {
        /**
         * Process-lifetime scope for shared repository flows. A [SupervisorJob] keeps one failing
         * flow from tearing down the others; nothing here is ever cancelled by design.
         */
        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
