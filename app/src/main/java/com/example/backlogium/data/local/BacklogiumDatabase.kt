package com.example.backlogium.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Achievement
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
        Achievement::class,
    ],
    version = 7,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class BacklogiumDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun sessionDao(): SessionDao
    abstract fun dailyProgressDao(): DailyProgressDao
    abstract fun playerProfileDao(): PlayerProfileDao
    abstract fun hltbDataDao(): HltbDataDao
    abstract fun achievementDao(): AchievementDao

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

        /**
         * v2 → v3: additive only — create the `achievements` table. No existing data is
         * altered or backfilled (add-steam-achievements).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `achievements` (" +
                        "`appId` INTEGER NOT NULL, " +
                        "`apiName` TEXT NOT NULL, " +
                        "`displayName` TEXT, " +
                        "`iconUrl` TEXT, " +
                        "`unlocked` INTEGER NOT NULL, " +
                        "`unlockedAt` INTEGER, " +
                        "`globalPercent` REAL, " +
                        "`snapshotPercent` REAL, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`appId`, `apiName`), " +
                        "FOREIGN KEY(`appId`) REFERENCES `games`(`appId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }

        /**
         * v3 → v4: additive only — add the opt-in playtime-backfill columns. `games` gains a
         * frozen historical-playtime offset and `player_profile` gains the one-time "imported"
         * flag. Both default to the current (no-backfill) behavior, so existing installs are
         * unchanged until the user opts in (add-playtime-backfill).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `games` ADD COLUMN `backfillMinutes` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `player_profile` " +
                        "ADD COLUMN `playtimeBackfilled` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v4 → v5: additive only — `player_profile` gains the Steam identity columns backing the
         * profile header. Both nullable with no backfill, so existing installs render the
         * fallback presentation until the next sync populates them (add-steam-profile-header).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `player_profile` ADD COLUMN `personaName` TEXT")
                db.execSQL("ALTER TABLE `player_profile` ADD COLUMN `avatarUrl` TEXT")
            }
        }

        /**
         * v5 → v6: additive only — index `sessions(appId, startAt, endAt)` for the backup/restore
         * merge engine's natural-key lookup (add-backup-restore). No existing data is altered.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sessions_appId_startAt_endAt` " +
                        "ON `sessions` (`appId`, `startAt`, `endAt`)",
                )
            }
        }

        /**
         * v6 → v7: additive only — `achievements` gains the schema's description and hidden flag
         * (enhance-game-detail). Deliberately not backfilled: populating descriptions eagerly would
         * cost one `GetSchemaForGame` call per owned game with achievements, so existing rows keep
         * a null `description` until their game's next natural fetch. `hidden` defaults to 0, the
         * correct assumption for achievements already visible to the player.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `achievements` ADD COLUMN `description` TEXT")
                db.execSQL(
                    "ALTER TABLE `achievements` ADD COLUMN `hidden` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }
}
