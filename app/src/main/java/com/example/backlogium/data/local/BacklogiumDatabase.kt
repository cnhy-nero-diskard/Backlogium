package com.example.backlogium.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.backlogium.data.diagnostics.NETWORK_REQUEST_STATUS
import com.example.backlogium.data.diagnostics.REQUEST_COUNTER_HOUR_MILLIS
import com.example.backlogium.data.diagnostics.requestRoute
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.CollectionDao
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.DiagnosticsDao
import com.example.backlogium.data.local.dao.ExcludedSharedGameDao
import com.example.backlogium.data.local.dao.GameAchievementSyncDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.GameGenreCacheDao
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.HltbDatasetDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.dao.SteamAssetDao
import com.example.backlogium.data.local.dao.WishlistDao
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.ExcludedSharedGame
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameAchievementSync
import com.example.backlogium.data.local.entity.GameGenreCache
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbDatasetState
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.WishlistItem
import com.example.backlogium.data.local.entity.WishlistPriceObservation
import com.example.backlogium.data.local.entity.PresenceDecision
import com.example.backlogium.data.local.entity.RequestBreakdown
import com.example.backlogium.data.local.entity.RequestTotal
import com.example.backlogium.data.local.entity.SteamAssetDownloadState
import com.example.backlogium.data.local.entity.SteamAssetManifest
import com.example.backlogium.data.local.entity.SyncRun

@Database(
    entities = [
        Game::class,
        Session::class,
        DailyProgress::class,
        PlayerProfile::class,
        HltbData::class,
        HltbDatasetState::class,
        Achievement::class,
        SyncRun::class,
        RequestBreakdown::class,
        RequestTotal::class,
        PresenceDecision::class,
        Collection::class,
        CollectionMember::class,
        GameGenreCache::class,
        SteamAssetManifest::class,
        SteamAssetDownloadState::class,
        GameAchievementSync::class,
        ExcludedSharedGame::class,
        WishlistItem::class,
        WishlistPriceObservation::class,
    ],
    version = 26,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class BacklogiumDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun sessionDao(): SessionDao
    abstract fun dailyProgressDao(): DailyProgressDao
    abstract fun playerProfileDao(): PlayerProfileDao
    abstract fun hltbDataDao(): HltbDataDao
    abstract fun hltbDatasetDao(): HltbDatasetDao
    abstract fun achievementDao(): AchievementDao
    abstract fun diagnosticsDao(): DiagnosticsDao
    abstract fun collectionDao(): CollectionDao
    abstract fun gameGenreCacheDao(): GameGenreCacheDao
    abstract fun gameAchievementSyncDao(): GameAchievementSyncDao
    abstract fun steamAssetDao(): SteamAssetDao
    abstract fun excludedSharedGameDao(): ExcludedSharedGameDao
    abstract fun wishlistDao(): WishlistDao

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

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `trigger` TEXT NOT NULL, `requestCount` INTEGER NOT NULL, `requestMillis` INTEGER NOT NULL, `gamesExamined` INTEGER NOT NULL, `gamesUpdated` INTEGER NOT NULL, `outcome` TEXT NOT NULL, `errorMessage` TEXT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_runs_startedAt` ON `sync_runs` (`startedAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `request_breakdowns` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` INTEGER NOT NULL, `endpoint` TEXT NOT NULL, `status` INTEGER, `requestCount` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, FOREIGN KEY(`runId`) REFERENCES `sync_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_request_breakdowns_runId` ON `request_breakdowns` (`runId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `presence_decisions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `trigger` TEXT NOT NULL, `outcome` TEXT NOT NULL, `appId` INTEGER, `retainedPriorState` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_presence_decisions_at` ON `presence_decisions` (`at`)")
            }
        }

        /**
         * v8 → v9: additive only — create the `collections` and `collection_members` tables
         * (add-custom-collections). No existing table is altered; `isGoal`, `targetMinutes`,
         * and all existing columns are untouched. A fresh install and an upgrade both start with
         * zero collections, so Home renders its empty state until the user creates one.
         *
         * `mode`/`sort` are stored as their enum names (TEXT). The members FK to `collections`
         * with CASCADE drops memberships when a collection is deleted; there is deliberately no
         * FK to `games` (soft app-id reference).
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collections` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`mode` TEXT NOT NULL, " +
                        "`sort` TEXT NOT NULL, " +
                        "`targetDate` TEXT, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collection_members` (" +
                        "`collectionId` INTEGER NOT NULL, " +
                        "`appId` INTEGER NOT NULL, " +
                        "`orderIndex` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`collectionId`, `appId`), " +
                        "FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_collection_members_collectionId` " +
                        "ON `collection_members` (`collectionId`)",
                )
            }
        }

        /**
         * v9 → v10: additive only — add an optional accent to collections and a manual done flag
         * to collection_members (refine-collections-ui). No existing data is altered or backfilled:
         * null accent = default styling, 0 done = not done.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `collections` ADD COLUMN `accent` TEXT")
                db.execSQL(
                    "ALTER TABLE `collection_members` ADD COLUMN `done` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /** v10 -> v11: add the selected HLTB basis used for deadline planning. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `collections` ADD COLUMN `timeBasis` TEXT NOT NULL DEFAULT 'COMPLETIONIST'",
                )
            }
        }

        /**
         * v11 -> v12: add the independently refreshed Steam Store genre cache. Existing games
         * deliberately receive no rows, keeping their genres unknown until background enrichment.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `game_genre_cache` (" +
                        "`appId` INTEGER NOT NULL, " +
                        "`genresJson` TEXT NOT NULL, " +
                        "`checkedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`appId`), " +
                        "FOREIGN KEY(`appId`) REFERENCES `games`(`appId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }

        /**
         * v12 -> v13: add optional collection descriptions and explicit collection display order.
         * Existing rows are seeded in the same `createdAt ASC, id ASC` order used before v13 so
         * the first ordered listing is unchanged for existing installs.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `collections` ADD COLUMN `description` TEXT")
                db.execSQL(
                    "ALTER TABLE `collections` ADD COLUMN `displayOrder` INTEGER NOT NULL DEFAULT 0",
                )

                db.query("SELECT `id` FROM `collections` ORDER BY `createdAt` ASC, `id` ASC").use { cursor ->
                    var displayOrder = 0
                    while (cursor.moveToNext()) {
                        db.execSQL(
                            "UPDATE `collections` SET `displayOrder` = ? WHERE `id` = ?",
                            arrayOf(displayOrder, cursor.getLong(0)),
                        )
                        displayOrder++
                    }
                }
            }
        }

        /**
         * v13 -> v14: tiered achievement refresh.
         *
         * - `sync_runs` gains `hotCount`, `warmCount`, `coldCount`, `neverCount` so tier distribution
         *   is observable per run (task 1.2). Defaults to 0; existing runs have no tier data.
         * - `game_achievement_sync` is the new per-game metadata table keyed by `appId`, holding
         *   per-data-kind freshness timestamps and whether the game has achievements (task 2.1).
         *   Existing rows are not backfilled: a missing row means "no stored achievement data",
         *   which tier selection already treats as eligible.
         * - Existing synthetic `NO_ACHIEVEMENTS_MARKER` rows in `achievements` are translated into
         *   `game_achievement_sync` rows with `hasAchievements = 0` and then deleted (task 2.3).
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `sync_runs` ADD COLUMN `hotCount` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `sync_runs` ADD COLUMN `warmCount` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `sync_runs` ADD COLUMN `coldCount` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `sync_runs` ADD COLUMN `neverCount` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `game_achievement_sync` (" +
                        "`appId` INTEGER NOT NULL, " +
                        "`playerStateFetchedAt` INTEGER, " +
                        "`schemaFetchedAt` INTEGER, " +
                        "`hasAchievements` INTEGER, " +
                        "`checkedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`appId`), " +
                        "FOREIGN KEY(`appId`) REFERENCES `games`(`appId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_game_achievement_sync_appId` " +
                        "ON `game_achievement_sync` (`appId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_game_achievement_sync_playerStateFetchedAt` " +
                        "ON `game_achievement_sync` (`playerStateFetchedAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_game_achievement_sync_schemaFetchedAt` " +
                        "ON `game_achievement_sync` (`schemaFetchedAt`)",
                )
                // Translate the old "no achievements" sentinel rows into metadata rows.
                db.execSQL(
                    "INSERT OR REPLACE INTO `game_achievement_sync` " +
                        "(`appId`, `playerStateFetchedAt`, `schemaFetchedAt`, `hasAchievements`, `checkedAt`) " +
                        "SELECT `appId`, `fetchedAt`, NULL, 0, `fetchedAt` " +
                        "FROM `achievements` WHERE `apiName` = '__no_achievements__'",
                )
                db.execSQL(
                    "DELETE FROM `achievements` WHERE `apiName` = '__no_achievements__'",
                )
            }
        }

        /** v14 -> v15: record derived-rule provenance and retain retired achievements as tombstones. */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `player_profile` " +
                        "ADD COLUMN `gamificationConfigVersion` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `achievements` ADD COLUMN `retired` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v15 -> v16: track whether a backup merge's raw data has committed without its follow-up
         * gamification recompute having completed yet (auditfix-backup-integrity). Defaults to
         * false; existing installs have no merge in flight, so this is a pure no-op on upgrade.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `player_profile` " +
                        "ADD COLUMN `pendingImportRecompute` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v16 -> v17: make HLTB a standalone title cache. Completion estimates belong to a
         * game title rather than to the current account's ownership, so deleting `games` must not
         * cascade and discard them during an account reset or an ordinary library removal.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hltb_data_new` (" +
                        "`appId` INTEGER NOT NULL, " +
                        "`hltbId` INTEGER, " +
                        "`mainStoryMinutes` INTEGER, " +
                        "`mainExtraMinutes` INTEGER, " +
                        "`completionistMinutes` INTEGER, " +
                        "`allStylesMinutes` INTEGER, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "`matchStatus` TEXT NOT NULL, " +
                        "`candidatesJson` TEXT, " +
                        "PRIMARY KEY(`appId`))",
                )
                db.execSQL(
                    "INSERT INTO `hltb_data_new` " +
                        "(`appId`, `hltbId`, `mainStoryMinutes`, `mainExtraMinutes`, " +
                        "`completionistMinutes`, `allStylesMinutes`, `fetchedAt`, " +
                        "`matchStatus`, `candidatesJson`) " +
                        "SELECT `appId`, `hltbId`, `mainStoryMinutes`, `mainExtraMinutes`, " +
                        "`completionistMinutes`, `allStylesMinutes`, `fetchedAt`, " +
                        "`matchStatus`, `candidatesJson` FROM `hltb_data`",
                )
                db.execSQL("DROP TABLE `hltb_data`")
                db.execSQL("ALTER TABLE `hltb_data_new` RENAME TO `hltb_data`")
            }
        }

        /**
         * v17 -> v18: repair `achievements.unlockedAt` rows written by versions before the
         * `AchievementMerge` fix that stored Steam's `unlocktime` (epoch seconds) directly instead
         * of converting to epoch millis (auditfix-account-identity review). No schema change —
         * this is a one-time data-only correction.
         *
         * A stored value is unambiguously second-scale, never a legitimate millis timestamp, if
         * it falls below [com.example.backlogium.data.backup.BackupValidator.EARLIEST_PLAUSIBLE_DATE]
         * (2003-01-01) expressed in millis: today's date in seconds is still three orders of
         * magnitude below that bound, and no correctly-converted millis value for an achievement
         * unlocked after Steam existed can be. Multiplying those rows by 1000 is therefore safe
         * and idempotent — a row already in millis is always >= the bound and untouched.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE `achievements` SET `unlockedAt` = `unlockedAt` * 1000 " +
                        "WHERE `unlockedAt` IS NOT NULL AND `unlockedAt` > 0 " +
                        "AND `unlockedAt` < $EARLIEST_PLAUSIBLE_UNLOCK_MILLIS",
                )
            }
        }

        /** v18 -> v19: retain request counts independently of the short raw-run retention. */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `request_totals` (" +
                        "`hourStart` INTEGER NOT NULL, " +
                        "`route` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`ok` INTEGER NOT NULL, " +
                        "`count` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`hourStart`, `route`, `status`))",
                )

                val totals = linkedMapOf<BackfillKey, Int>()
                db.query(
                    "SELECT r.`startedAt`, b.`endpoint`, b.`status`, b.`requestCount` " +
                        "FROM `sync_runs` r INNER JOIN `request_breakdowns` b ON r.`id` = b.`runId`",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val route = requestRoute(cursor.getString(1)) ?: continue
                        val startedAt = cursor.getLong(0)
                        val hourStart = startedAt - Math.floorMod(startedAt, REQUEST_COUNTER_HOUR_MILLIS)
                        val status = if (cursor.isNull(2)) {
                            NETWORK_REQUEST_STATUS
                        } else {
                            cursor.getInt(2).toString()
                        }
                        val ok = !cursor.isNull(2) && cursor.getInt(2) in 200..299
                        val key = BackfillKey(hourStart, route, status, ok)
                        totals[key] = (totals[key] ?: 0) + cursor.getInt(3)
                    }
                }

                totals.forEach { (key, count) ->
                    db.execSQL(
                        "INSERT INTO `request_totals` " +
                            "(`hourStart`, `route`, `status`, `ok`, `count`) VALUES (?, ?, ?, ?, ?)",
                        arrayOf(key.hourStart, key.route, key.status, if (key.ok) 1 else 0, count),
                    )
                }
            }
        }

        /** v19 -> v20: durable, derived Steam CDN asset manifest and last-run summary. */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `steam_asset_manifest` (`normalizedUrl` TEXT NOT NULL, `kind` TEXT NOT NULL, `relativePath` TEXT, `byteCount` INTEGER NOT NULL, `checksum` TEXT, `state` TEXT NOT NULL, `lastSuccessAt` INTEGER, `lastCheckedAt` INTEGER NOT NULL, PRIMARY KEY(`normalizedUrl`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `steam_asset_download_state` (`id` INTEGER NOT NULL, `mode` TEXT NOT NULL, `completedAt` INTEGER NOT NULL, `storedCount` INTEGER NOT NULL, `alreadyPresentCount` INTEGER NOT NULL, `unavailableCount` INTEGER NOT NULL, `failedCount` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        /**
         * v20 -> v21: record recency observations on the games schema. `games` gains
         * `firstSeenAt`, `lastPlayedAt`, and `returnedToPlayAt`, all nullable with no backfill.
         *
         * This must stay exactly the schema `master` already shipped at version 21: an install
         * that upgraded from a genuine `master` build is stamped version 21 with only these three
         * columns, and Room's identity check is content-based, not just the version number. Fold
         * anything else into a later migration instead of editing this one.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `games` ADD COLUMN `firstSeenAt` INTEGER")
                db.execSQL("ALTER TABLE `games` ADD COLUMN `lastPlayedAt` INTEGER")
                db.execSQL("ALTER TABLE `games` ADD COLUMN `returnedToPlayAt` INTEGER")
            }
        }

        /**
         * v21 -> v22: family-shared games (add-family-shared-games).
         *
         * - `games` gains `source`, defaulting to `STEAM_OWNED`. A widening with no data movement:
         *   every existing row *is* an owned game, since the owned-games sync was until now the
         *   only path that could create one. Stored as the enum name, matching `Converters`.
         * - `excluded_shared_games` records app ids the player removed after admission, so a
         *   removal survives further play. Deliberately no foreign key to `games` — the row exists
         *   precisely because the game row does not — and `name` is carried so Settings can list a
         *   removal with nothing else left to read it from.
         *
         * `source` is added conditionally: an install that ran this branch before the v21/v22
         * split (a build between the family-shared feature landing and this fix) is *also*
         * stamped version 21, but its `games` table already has `source` — that earlier build's
         * `MIGRATION_20_21` put it there. An unconditional `ADD COLUMN` would fail on that shape
         * with a duplicate-column error, so this checks first rather than assuming master's
         * recency-only v21 is the only one that exists in the wild.
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("games", "source")) {
                    db.execSQL(
                        "ALTER TABLE `games` ADD COLUMN `source` TEXT NOT NULL " +
                            "DEFAULT 'STEAM_OWNED'",
                    )
                }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `excluded_shared_games` (" +
                        "`appId` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`excludedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`appId`))",
                )
            }
        }

        /**
         * v22 -> v23: an optional store-region setting on `player_profile` (add-wishlist-section).
         *
         * Additive and deliberately unbackfilled. The public profile location in
         * `GetPlayerSummaries` is not the payment-derived Store Country, so NULL is the safe
         * default and the price request asserts no region until an explicit setting exists.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `player_profile` ADD COLUMN `storeRegion` TEXT")
            }
        }

        /**
         * v23 -> v24: the wishlist's own two tables (add-wishlist-section).
         *
         * Neither carries a foreign key to `games`, and that is the point rather than an
         * oversight: a wishlisted app id is one the player does not own, so there is no parent row
         * to reference. Keeping wants out of `games` keeps them out of every library count, XP
         * denominator, completion figure, and analytic without any of those queries having to
         * learn to exclude them.
         *
         * `wishlist_price_observations` is append-only — one row per app per observation — because
         * price history is cheap to start accumulating now and impossible to reconstruct later.
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wishlist_items` (" +
                        "`appId` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`artworkUrl` TEXT NOT NULL, " +
                        "`priority` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "`lastSeenAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`appId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wishlist_price_observations` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`appId` INTEGER NOT NULL, " +
                        "`observedAt` INTEGER NOT NULL, " +
                        "`currency` TEXT, " +
                        "`finalMinorUnits` INTEGER, " +
                        "`initialMinorUnits` INTEGER, " +
                        "`discountPercent` INTEGER, " +
                        "`formatted` TEXT, " +
                        "`listFormatted` TEXT)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_wishlist_price_observations_appId_observedAt` " +
                        "ON `wishlist_price_observations` (`appId`, `observedAt`)",
                )
            }
        }

        /** v24 -> v25: persist the last successful wishlist membership read. */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `player_profile` ADD COLUMN " +
                        "`lastSuccessfulWishlistReadAt` INTEGER",
                )
            }
        }

        /** v25 -> v26: record whether each HLTB correspondence is dataset, automatic, or manual. */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `hltb_data` ADD COLUMN " +
                        "`origin` TEXT NOT NULL DEFAULT 'AUTOMATIC'",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hltb_dataset_state` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`schemaVersion` INTEGER NOT NULL, " +
                        "`datasetVersion` INTEGER NOT NULL, " +
                        "`gatheredAt` INTEGER NOT NULL, " +
                        "`payloadJson` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

        private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
            query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return true
                }
            }
            return false
        }

        private data class BackfillKey(
            val hourStart: Long,
            val route: String,
            val status: String,
            val ok: Boolean,
        )

        /**
         * 2003-01-01T00:00:00Z in epoch millis — mirrors
         * [com.example.backlogium.data.backup.BackupValidator.EARLIEST_PLAUSIBLE_DATE]. Kept as a
         * literal constant here (rather than importing that object) so this migration's behavior
         * never shifts if the validator's plausibility window is later tuned for an unrelated
         * reason.
         */
        private const val EARLIEST_PLAUSIBLE_UNLOCK_MILLIS = 1_041_379_200_000L
    }
}
