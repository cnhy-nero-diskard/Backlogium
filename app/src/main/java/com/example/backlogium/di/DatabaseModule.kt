package com.example.backlogium.di

import android.content.Context
import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.CollectionDao
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.DiagnosticsDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.GameGenreCacheDao
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BacklogiumDatabase =
        Room.databaseBuilder(context, BacklogiumDatabase::class.java, BacklogiumDatabase.NAME)
            .addMigrations(
                BacklogiumDatabase.MIGRATION_1_2,
                BacklogiumDatabase.MIGRATION_2_3,
                BacklogiumDatabase.MIGRATION_3_4,
                BacklogiumDatabase.MIGRATION_4_5,
                BacklogiumDatabase.MIGRATION_5_6,
                BacklogiumDatabase.MIGRATION_6_7,
                BacklogiumDatabase.MIGRATION_7_8,
                BacklogiumDatabase.MIGRATION_8_9,
                BacklogiumDatabase.MIGRATION_9_10,
                BacklogiumDatabase.MIGRATION_10_11,
                BacklogiumDatabase.MIGRATION_11_12,
                BacklogiumDatabase.MIGRATION_12_13,
            )
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideGameDao(db: BacklogiumDatabase): GameDao = db.gameDao()

    @Provides
    fun provideGameGenreCacheDao(db: BacklogiumDatabase): GameGenreCacheDao = db.gameGenreCacheDao()

    @Provides
    fun provideSessionDao(db: BacklogiumDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideDailyProgressDao(db: BacklogiumDatabase): DailyProgressDao = db.dailyProgressDao()

    @Provides
    fun providePlayerProfileDao(db: BacklogiumDatabase): PlayerProfileDao = db.playerProfileDao()

    @Provides
    fun provideHltbDataDao(db: BacklogiumDatabase): HltbDataDao = db.hltbDataDao()

    @Provides
    fun provideAchievementDao(db: BacklogiumDatabase): AchievementDao = db.achievementDao()

    @Provides
    fun provideDiagnosticsDao(db: BacklogiumDatabase): DiagnosticsDao = db.diagnosticsDao()

    @Provides
    fun provideCollectionDao(db: BacklogiumDatabase): CollectionDao = db.collectionDao()
}
