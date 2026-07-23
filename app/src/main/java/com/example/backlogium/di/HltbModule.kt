package com.example.backlogium.di

import com.example.backlogium.data.hltb.HltbDataSource
import com.example.backlogium.data.hltb.ScrapingHltbDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the HowLongToBeat data-source seam to its client-side scraping implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class HltbModule {

    @Binds
    abstract fun bindHltbDataSource(impl: ScrapingHltbDataSource): HltbDataSource
}
