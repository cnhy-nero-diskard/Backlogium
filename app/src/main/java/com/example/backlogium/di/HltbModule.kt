package com.example.backlogium.di

import com.example.backlogium.data.hltb.AndroidHltbDatasetConnectivity
import com.example.backlogium.data.hltb.FileHltbDatasetArtifactStore
import com.example.backlogium.data.hltb.HltbDataSource
import com.example.backlogium.data.hltb.HltbDatasetArtifactStore
import com.example.backlogium.data.hltb.HltbDatasetConnectivity
import com.example.backlogium.data.hltb.ScrapingHltbDataSource
import com.example.backlogium.data.repo.HltbDatasetLookup
import com.example.backlogium.data.repo.HltbDatasetRepository
import com.example.backlogium.data.repo.HltbLibraryCatalog
import com.example.backlogium.data.repo.RoomHltbLibraryCatalog
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the HowLongToBeat data-source seam to its client-side scraping implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class HltbModule {

    @Binds
    abstract fun bindHltbDataSource(impl: ScrapingHltbDataSource): HltbDataSource

    @Binds
    @Singleton
    abstract fun bindHltbDatasetLookup(impl: HltbDatasetRepository): HltbDatasetLookup

    @Binds
    @Singleton
    abstract fun bindHltbLibraryCatalog(impl: RoomHltbLibraryCatalog): HltbLibraryCatalog

    @Binds
    @Singleton
    abstract fun bindHltbDatasetArtifactStore(
        impl: FileHltbDatasetArtifactStore,
    ): HltbDatasetArtifactStore

    @Binds
    @Singleton
    abstract fun bindHltbDatasetConnectivity(
        impl: AndroidHltbDatasetConnectivity,
    ): HltbDatasetConnectivity
}
