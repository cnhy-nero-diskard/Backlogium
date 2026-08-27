package com.example.backlogium.di

import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.DataStoreProgressMarksStore
import com.example.backlogium.data.repo.AndroidSharedGameNotifier
import com.example.backlogium.data.repo.DataStoreSettingsRepository
import com.example.backlogium.data.repo.PresenceObserver
import com.example.backlogium.data.repo.PresenceSessionRecorder
import com.example.backlogium.data.repo.SessionEndOutbox
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.data.repo.SharedGameNotifier
import com.example.backlogium.data.updates.AndroidInstalledPackageInfoProvider
import com.example.backlogium.data.updates.AppUpdateRepository
import com.example.backlogium.data.updates.DataStoreAppUpdateRepository
import com.example.backlogium.data.updates.AndroidUpdateNotifier
import com.example.backlogium.data.updates.AppUpdateManager
import com.example.backlogium.data.updates.FileUpdateArtifactStore
import com.example.backlogium.data.updates.InstalledPackageInfoProvider
import com.example.backlogium.data.updates.OkHttpUpdateDownloader
import com.example.backlogium.data.updates.PackageInstallerUpdateInstaller
import com.example.backlogium.data.updates.PackageUpdateVerifier
import com.example.backlogium.data.updates.UpdateArtifactStore
import com.example.backlogium.data.updates.UpdateDownloader
import com.example.backlogium.data.updates.UpdateInstaller
import com.example.backlogium.data.updates.UpdateNotifier
import com.example.backlogium.data.updates.UpdateDataStore
import com.example.backlogium.data.updates.UpdateManager
import com.example.backlogium.data.updates.UpdateStateStore
import com.example.backlogium.data.updates.UpdateVerifier
import com.example.backlogium.data.local.PostPlayGenerationStore
import com.example.backlogium.domain.PostPlayGenerations
import com.example.backlogium.domain.ProgressMarksStore
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

    @Binds
    @Singleton
    abstract fun bindSessionEndOutbox(impl: DataStoreSettingsRepository): SessionEndOutbox

    @Binds
    @Singleton
    abstract fun bindAppUpdateRepository(impl: DataStoreAppUpdateRepository): AppUpdateRepository

    @Binds
    @Singleton
    abstract fun bindAppUpdateManager(impl: UpdateManager): AppUpdateManager

    @Binds
    @Singleton
    abstract fun bindUpdateStateStore(impl: UpdateDataStore): UpdateStateStore

    @Binds
    @Singleton
    abstract fun bindInstalledPackageInfoProvider(
        impl: AndroidInstalledPackageInfoProvider,
    ): InstalledPackageInfoProvider

    @Binds
    @Singleton
    abstract fun bindUpdateArtifactStore(impl: FileUpdateArtifactStore): UpdateArtifactStore

    @Binds
    @Singleton
    abstract fun bindUpdateDownloader(impl: OkHttpUpdateDownloader): UpdateDownloader

    @Binds
    @Singleton
    abstract fun bindUpdateVerifier(impl: PackageUpdateVerifier): UpdateVerifier

    @Binds
    @Singleton
    abstract fun bindUpdateInstaller(impl: PackageInstallerUpdateInstaller): UpdateInstaller

    @Binds
    @Singleton
    abstract fun bindUpdateNotifier(impl: AndroidUpdateNotifier): UpdateNotifier

    @Binds
    @Singleton
    abstract fun bindProgressMarksStore(impl: DataStoreProgressMarksStore): ProgressMarksStore

    @Binds
    @Singleton
    abstract fun bindPostPlayGenerations(impl: PostPlayGenerationStore): PostPlayGenerations

    @Binds
    @Singleton
    abstract fun bindCredentialsProvider(impl: CredentialsRepository): CredentialsProvider

    @Binds
    @Singleton
    abstract fun bindDatabaseTransactionScope(impl: RoomDatabaseTransactionScope): DatabaseTransactionScope

    @Binds
    @Singleton
    abstract fun bindPresenceObserver(impl: PresenceSessionRecorder): PresenceObserver

    @Binds
    @Singleton
    abstract fun bindSharedGameNotifier(impl: AndroidSharedGameNotifier): SharedGameNotifier

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
