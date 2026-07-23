package com.example.backlogium.di

import android.content.Context
import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
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
            .addMigrations(BacklogiumDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideGameDao(db: BacklogiumDatabase): GameDao = db.gameDao()

    @Provides
    fun provideSessionDao(db: BacklogiumDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideDailyProgressDao(db: BacklogiumDatabase): DailyProgressDao = db.dailyProgressDao()

    @Provides
    fun providePlayerProfileDao(db: BacklogiumDatabase): PlayerProfileDao = db.playerProfileDao()

    @Provides
    fun provideHltbDataDao(db: BacklogiumDatabase): HltbDataDao = db.hltbDataDao()
}
