package com.example.backlogium.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session

@Database(
    entities = [
        Game::class,
        Session::class,
        DailyProgress::class,
        PlayerProfile::class,
        HltbData::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class BacklogiumDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun sessionDao(): SessionDao
    abstract fun dailyProgressDao(): DailyProgressDao
    abstract fun playerProfileDao(): PlayerProfileDao
    abstract fun hltbDataDao(): HltbDataDao

    companion object {
        const val NAME = "backlogium.db"

        /**
         * v1 → v2: additive only — create the `hltb_data` cache table. No existing data is
         * altered or backfilled (add-hltb-integration).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hltb_data` (" +
                        "`appId` INTEGER NOT NULL, " +
                        "`hltbId` INTEGER, " +
                        "`mainStoryMinutes` INTEGER, " +
                        "`mainExtraMinutes` INTEGER, " +
                        "`completionistMinutes` INTEGER, " +
                        "`allStylesMinutes` INTEGER, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "`matchStatus` TEXT NOT NULL, " +
                        "`candidatesJson` TEXT, " +
                        "PRIMARY KEY(`appId`), " +
                        "FOREIGN KEY(`appId`) REFERENCES `games`(`appId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }
    }
}
