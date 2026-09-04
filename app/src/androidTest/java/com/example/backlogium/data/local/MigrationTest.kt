package com.example.backlogium.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration coverage is intentionally discoverable here: whenever the database version
 * increases, add the corresponding preceding-version fixture and data-survival assertions
 * before merging the migration. Schema validation by itself cannot catch a migration that
 * creates the right columns while dropping the user's rows.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val migrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BacklogiumDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun deepHistory_v13ToV14_preservesRepresentativeDataAndTranslatesSentinel() {
        val databaseName = "migration-v13-${System.nanoTime()}"
        val rawHelper = openRawV13Database(databaseName)

        try {
            rawHelper.writableDatabase.seedRepresentativeData()
        } finally {
            rawHelper.close()
        }

        try {
            val migrated = migrationTestHelper.runMigrationsAndValidate(
                databaseName,
                14,
                true,
                BacklogiumDatabase.MIGRATION_13_14,
            )
            try {
                migrated.assertRepresentativeData()
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun v14ToV15_preservesAchievementSnapshotAndSeedsNewColumns() {
        val databaseName = "migration-v14-${System.nanoTime()}"
        val database = migrationTestHelper.createDatabase(databaseName, 14)
        try {
            database.execSQL(
                "INSERT INTO games " +
                    "(appId, name, iconUrl, playtimeForever, playtime2Weeks, lastPlaytime, " +
                    "isGoal, targetMinutes, lastSyncedAt, backfillMinutes) VALUES " +
                    "(440, 'Game', '', 100, 0, 100, 1, 200, 1700000000000, 12)",
            )
            database.execSQL(
                "INSERT INTO achievements " +
                    "(appId, apiName, displayName, iconUrl, unlocked, unlockedAt, globalPercent, " +
                    "snapshotPercent, description, hidden, fetchedAt) VALUES " +
                    "(440, 'ACH_WIN', 'Win', '', 1, 1700000000000, 12.5, 13.75, " +
                    "'Win', 0, 1700000001000)",
            )
            database.execSQL(
                "INSERT INTO player_profile " +
                    "(id, steamId, steamLevel, totalXp, level, currentStreak, longestStreak, " +
                    "lastSyncAt, lastSyncError, playtimeBackfilled, personaName, avatarUrl) VALUES " +
                    "(0, '76561198000000000', 42, 100, 2, 1, 3, 1700000000000, " +
                    "NULL, 0, 'Player', 'avatar')",
            )
        } finally {
            database.close()
        }

        try {
            val migrated = migrationTestHelper.runMigrationsAndValidate(
                databaseName,
                15,
                true,
                BacklogiumDatabase.MIGRATION_14_15,
            )
            try {
                migrated.query(
                    "SELECT snapshotPercent, retired FROM achievements " +
                        "WHERE appId = 440 AND apiName = 'ACH_WIN'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(13.75, cursor.getDouble(0), 0.0)
                    assertEquals(0, cursor.getInt(1))
                }
                migrated.query(
                    "SELECT gamificationConfigVersion FROM player_profile WHERE id = 0",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0L, cursor.getLong(0))
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun v15ToV16_preservesProfileAndDefaultsPendingImportRecomputeToFalse() {
        val databaseName = "migration-v15-${System.nanoTime()}"
        val database = migrationTestHelper.createDatabase(databaseName, 15)
        try {
            database.execSQL(
                "INSERT INTO player_profile " +
                    "(id, steamId, steamLevel, totalXp, level, currentStreak, longestStreak, " +
                    "gamificationConfigVersion, lastSyncAt, lastSyncError, playtimeBackfilled, " +
                    "personaName, avatarUrl) VALUES " +
                    "(0, '76561198000000000', 42, 100, 2, 1, 3, 5, 1700000000000, " +
                    "NULL, 0, 'Player', 'avatar')",
            )
        } finally {
            database.close()
        }

        try {
            val migrated = migrationTestHelper.runMigrationsAndValidate(
                databaseName,
                16,
                true,
                BacklogiumDatabase.MIGRATION_15_16,
            )
            try {
                migrated.query(
                    "SELECT totalXp, pendingImportRecompute FROM player_profile WHERE id = 0",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(100, cursor.getInt(0))
                    assertEquals(0, cursor.getInt(1))
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun v16ToV17_detachesHltbAndKeepsRowsWhenGameIsDeleted() {
        val databaseName = "migration-v16-${System.nanoTime()}"
        val database = migrationTestHelper.createDatabase(databaseName, 16)
        try {
            database.execSQL(
                "INSERT INTO games " +
                    "(appId, name, iconUrl, playtimeForever, playtime2Weeks, lastPlaytime, " +
                    "isGoal, targetMinutes, lastSyncedAt, backfillMinutes) VALUES " +
                    "(440, 'Game', '', 100, 0, 100, 0, NULL, 1700000000000, 0)",
            )
            database.execSQL(
                "INSERT INTO hltb_data " +
                    "(appId, hltbId, mainStoryMinutes, mainExtraMinutes, completionistMinutes, " +
                    "allStylesMinutes, fetchedAt, matchStatus, candidatesJson) VALUES " +
                    "(440, 99, 60, 90, 120, 150, 1700000000000, 'RESOLVED', NULL)",
            )
        } finally {
            database.close()
        }

        try {
            val migrated = migrationTestHelper.runMigrationsAndValidate(
                databaseName,
                17,
                true,
                BacklogiumDatabase.MIGRATION_16_17,
            )
            try {
                migrated.query("PRAGMA foreign_key_list(`hltb_data`)").use { cursor ->
                    assertFalse(cursor.moveToFirst())
                }
                migrated.execSQL("DELETE FROM games WHERE appId = 440")
                migrated.query("SELECT COUNT(*) FROM hltb_data WHERE appId = 440").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
                assertForeignKeyReferencesGames(migrated, "sessions")
                assertForeignKeyReferencesGames(migrated, "achievements")
                assertForeignKeyReferencesGames(migrated, "game_genre_cache")
                assertForeignKeyReferencesGames(migrated, "game_achievement_sync")
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun v17ToV18_repairsSecondScaleUnlockedAtButLeavesMillisAndNullsAlone() {
        val databaseName = "migration-v17-${System.nanoTime()}"
        val database = migrationTestHelper.createDatabase(databaseName, 17)
        try {
            database.execSQL(
                "INSERT INTO games " +
                    "(appId, name, iconUrl, playtimeForever, playtime2Weeks, lastPlaytime, " +
                    "isGoal, targetMinutes, lastSyncedAt, backfillMinutes) VALUES " +
                    "(440, 'Game', '', 100, 0, 100, 0, NULL, 1700000000000, 0)",
            )
            // Written by a pre-fix app version: Steam's unlocktime (seconds) stored verbatim.
            database.execSQL(
                "INSERT INTO achievements " +
                    "(appId, apiName, displayName, iconUrl, unlocked, unlockedAt, globalPercent, " +
                    "snapshotPercent, description, hidden, retired, fetchedAt) VALUES " +
                    "(440, 'ACH_SECONDS', 'Win', '', 1, 1700000000, 12.5, 13.75, " +
                    "'Win', 0, 0, 1700000001000)",
            )
            // Already correct (post-fix write, or simply never wrong): must be left untouched.
            database.execSQL(
                "INSERT INTO achievements " +
                    "(appId, apiName, displayName, iconUrl, unlocked, unlockedAt, globalPercent, " +
                    "snapshotPercent, description, hidden, retired, fetchedAt) VALUES " +
                    "(440, 'ACH_MILLIS', 'Explore', '', 1, 1700000000000, 5.0, 5.0, " +
                    "'Explore', 0, 0, 1700000001000)",
            )
            // Locked achievement: unlockedAt is null and must stay null, not become 0.
            database.execSQL(
                "INSERT INTO achievements " +
                    "(appId, apiName, displayName, iconUrl, unlocked, unlockedAt, globalPercent, " +
                    "snapshotPercent, description, hidden, retired, fetchedAt) VALUES " +
                    "(440, 'ACH_LOCKED', 'Locked', '', 0, NULL, NULL, NULL, NULL, 0, 0, " +
                    "1700000001000)",
            )
        } finally {
            database.close()
        }

        try {
            val migrated = migrationTestHelper.runMigrationsAndValidate(
                databaseName,
                18,
                true,
                BacklogiumDatabase.MIGRATION_17_18,
            )
            try {
                migrated.query(
                    "SELECT unlockedAt FROM achievements WHERE apiName = 'ACH_SECONDS'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1700000000000L, cursor.getLong(0))
                }
                migrated.query(
                    "SELECT unlockedAt FROM achievements WHERE apiName = 'ACH_MILLIS'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1700000000000L, cursor.getLong(0))
                }
                migrated.query(
                    "SELECT unlockedAt FROM achievements WHERE apiName = 'ACH_LOCKED'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.isNull(0))
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun v18ToV19_backfillsRequestTotalsAndDropsMalformedIdentifiers() {
        val databaseName = "migration-v18-${System.nanoTime()}"
        val database = migrationTestHelper.createDatabase(databaseName, 18)
        val startedAt = 1_700_000_001_000L
        val hourStart = startedAt - Math.floorMod(startedAt, 3_600_000L)
        try {
            database.execSQL(
                "INSERT INTO sync_runs " +
                    "(id, startedAt, durationMs, trigger, requestCount, requestMillis, " +
                    "gamesExamined, gamesUpdated, outcome, errorMessage, hotCount, warmCount, " +
                    "coldCount, neverCount) VALUES " +
                    "(41, $startedAt, 1000, 'TEST', 10, 100, 1, 1, 'success', NULL, 0, 0, 0, 0)",
            )
            database.execSQL(
                "INSERT INTO request_breakdowns " +
                    "(id, runId, endpoint, status, requestCount, durationMs) VALUES " +
                    "(51, 41, 'https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/?key=secret&steamid=76561198000000001&appid=440', 200, 2, 10), " +
                    "(52, 41, 'https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/?key=secret&steamid=76561198000000002&appid=441', 200, 3, 10), " +
                    "(53, 41, 'https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/?key=secret&steamid=76561198000000001&appid=440', NULL, 1, 10), " +
                    "(54, 41, 'https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/?key=secret&steamid=76561198000000001&appid=440', 403, 4, 10), " +
                    "(55, 41, 'not-a-request-url', 200, 99, 10)",
            )
        } finally {
            database.close()
        }

        try {
            val migrated = migrationTestHelper.runMigrationsAndValidate(
                databaseName,
                19,
                true,
                BacklogiumDatabase.MIGRATION_18_19,
            )
            try {
                val rows = mutableListOf<List<Any?>>()
                migrated.query(
                    "SELECT hourStart, route, status, ok, count " +
                        "FROM request_totals ORDER BY status",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        rows += listOf(
                            cursor.getLong(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getInt(3),
                            cursor.getInt(4),
                        )
                    }
                }
                assertEquals(
                    listOf(
                        listOf(hourStart, "/IPlayerService/GetOwnedGames/v1/", "200", 1, 5),
                        listOf(hourStart, "/IPlayerService/GetOwnedGames/v1/", "403", 0, 4),
                        listOf(hourStart, "/IPlayerService/GetOwnedGames/v1/", "network", 0, 1),
                    ),
                    rows,
                )
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun v19ToV20_addsSteamAssetTablesAndLeavesExistingDataUntouched() {
        val databaseName = "migration-v19-${System.nanoTime()}"
        val database = migrationTestHelper.createDatabase(databaseName, 19)
        try {
            database.execSQL(
                "INSERT INTO games " +
                    "(appId, name, iconUrl, playtimeForever, playtime2Weeks, lastPlaytime, " +
                    "isGoal, targetMinutes, lastSyncedAt, backfillMinutes) VALUES " +
                    "(440, 'Game', 'icon-hash', 100, 0, 100, 0, NULL, 1700000000000, 0)",
            )
        } finally {
            database.close()
        }

        try {
            val migrated = migrationTestHelper.runMigrationsAndValidate(
                databaseName,
                20,
                true,
                BacklogiumDatabase.MIGRATION_19_20,
            )
            try {
                assertTableInfo(
                    migrated,
                    "steam_asset_manifest",
                    listOf(
                        ColumnInfo("normalizedUrl", "TEXT", notNull = true, pk = 1),
                        ColumnInfo("kind", "TEXT", notNull = true, pk = 0),
                        ColumnInfo("relativePath", "TEXT", notNull = false, pk = 0),
                        ColumnInfo("byteCount", "INTEGER", notNull = true, pk = 0),
                        ColumnInfo("checksum", "TEXT", notNull = false, pk = 0),
                        ColumnInfo("state", "TEXT", notNull = true, pk = 0),
                        ColumnInfo("lastSuccessAt", "INTEGER", notNull = false, pk = 0),
                        ColumnInfo("lastCheckedAt", "INTEGER", notNull = true, pk = 0),
                    ),
                )
                assertTableInfo(
                    migrated,
                    "steam_asset_download_state",
                    listOf(
                        ColumnInfo("id", "INTEGER", notNull = true, pk = 1),
                        ColumnInfo("mode", "TEXT", notNull = true, pk = 0),
                        ColumnInfo("completedAt", "INTEGER", notNull = true, pk = 0),
                        ColumnInfo("storedCount", "INTEGER", notNull = true, pk = 0),
                        ColumnInfo("alreadyPresentCount", "INTEGER", notNull = true, pk = 0),
                        ColumnInfo("unavailableCount", "INTEGER", notNull = true, pk = 0),
                        ColumnInfo("failedCount", "INTEGER", notNull = true, pk = 0),
                    ),
                )

                // The migration only adds tables; a pre-existing row must survive untouched.
                migrated.query("SELECT appId, name, iconUrl FROM games").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(440L, cursor.getLong(0))
                    assertEquals("Game", cursor.getString(1))
                    assertEquals("icon-hash", cursor.getString(2))
                    assertFalse(cursor.moveToNext())
                }

                // Both new tables start empty.
                migrated.query("SELECT COUNT(*) FROM steam_asset_manifest").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                migrated.query("SELECT COUNT(*) FROM steam_asset_download_state").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun v20ToV21_addsNullableRecencyColumns() {
        val databaseName = "migration-v20-${System.nanoTime()}"
        val database = migrationTestHelper.createDatabase(databaseName, 20)
        try {
            database.execSQL(
                "INSERT INTO games " +
                    "(appId, name, iconUrl, playtimeForever, playtime2Weeks, lastPlaytime, " +
                    "isGoal, targetMinutes, lastSyncedAt, backfillMinutes) VALUES " +
                    "(440, 'Game', 'icon-hash', 100, 0, 100, 1, 240, 1700000000000, 55)",
            )
        } finally {
            database.close()
        }

        try {
            val migrated = migrationTestHelper.runMigrationsAndValidate(
                databaseName,
                21,
                true,
                BacklogiumDatabase.MIGRATION_20_21,
            )
            try {
                migrated.query(
                    "SELECT appId, name, playtimeForever, firstSeenAt, lastPlayedAt, " +
                        "returnedToPlayAt FROM games",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(440L, cursor.getLong(0))
                    assertEquals("Game", cursor.getString(1))
                    assertEquals(100, cursor.getInt(2))
                    assertTrue(cursor.isNull(3))
                    assertTrue(cursor.isNull(4))
                    assertTrue(cursor.isNull(5))
                    assertFalse(cursor.moveToNext())
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun v21ToV22_addsFamilySourceAndExclusionTable() {
        val databaseName = "migration-v21-${System.nanoTime()}"
        val database = migrationTestHelper.createDatabase(databaseName, 21)
        try {
            database.execSQL(
                "INSERT INTO games " +
                    "(appId, name, iconUrl, playtimeForever, playtime2Weeks, lastPlaytime, " +
                    "isGoal, targetMinutes, lastSyncedAt, backfillMinutes, firstSeenAt, " +
                    "lastPlayedAt, returnedToPlayAt) VALUES " +
                    "(440, 'Game', 'icon-hash', 100, 0, 100, 1, 240, 1700000000000, 55, " +
                    "NULL, NULL, NULL)",
            )
        } finally {
            database.close()
        }

        try {
            val migrated = migrationTestHelper.runMigrationsAndValidate(
                databaseName,
                22,
                true,
                BacklogiumDatabase.MIGRATION_21_22,
            )
            try {
                assertTableInfo(
                    migrated,
                    "excluded_shared_games",
                    listOf(
                        ColumnInfo("appId", "INTEGER", notNull = true, pk = 1),
                        ColumnInfo("name", "TEXT", notNull = true, pk = 0),
                        ColumnInfo("excludedAt", "INTEGER", notNull = true, pk = 0),
                    ),
                )

                // Every pre-migration row is an owned game: the owned-games sync was the only
                // path that could create one.
                migrated.query("SELECT appId, name, playtimeForever, source FROM games").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(440L, cursor.getLong(0))
                    assertEquals("Game", cursor.getString(1))
                    assertEquals(100, cursor.getInt(2))
                    assertEquals("STEAM_OWNED", cursor.getString(3))
                    assertFalse(cursor.moveToNext())
                }

                migrated.query("SELECT COUNT(*) FROM excluded_shared_games").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    /**
     * Regression: a build of this branch prior to the v21/v22 split (e.g. commit `bc281f7`) was
     * also stamped version 21, but its `games` table already carried `source` and
     * `excluded_shared_games` already existed — that build's `MIGRATION_20_21` put both there
     * directly, before they were split out into [MIGRATION_21_22]. [MIGRATION_21_22] must
     * tolerate this shape too, not just master's recency-only v21.
     *
     * Starts from [MigrationTestHelper.createDatabase]'s real, complete v21 fixture (every table,
     * not just `games`) and grafts on the two pieces that build's v21 already had, rather than
     * hand-building a partial schema that `runMigrationsAndValidate`'s full-schema comparison
     * would then reject as incomplete.
     */
    @Test
    fun v21ToV22_toleratesAPriorBranchBuildsV21ThatAlreadyHasSource() {
        val databaseName = "migration-v21-legacy-${System.nanoTime()}"
        val database = migrationTestHelper.createDatabase(databaseName, 21)
        try {
            database.execSQL(
                "ALTER TABLE `games` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'STEAM_OWNED'",
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `excluded_shared_games` (" +
                    "`appId` INTEGER NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`excludedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`appId`))",
            )
            database.execSQL(
                "INSERT INTO games " +
                    "(appId, name, iconUrl, playtimeForever, playtime2Weeks, lastPlaytime, " +
                    "isGoal, targetMinutes, lastSyncedAt, backfillMinutes, source, " +
                    "firstSeenAt, lastPlayedAt, returnedToPlayAt) VALUES " +
                    "(441, 'Borrowed Game', 'icon-hash', 30, 0, 30, 0, NULL, 1700000000000, 0, " +
                    "'FAMILY_SHARED', NULL, NULL, NULL)",
            )
        } finally {
            database.close()
        }

        try {
            val migrated = migrationTestHelper.runMigrationsAndValidate(
                databaseName,
                22,
                true,
                BacklogiumDatabase.MIGRATION_21_22,
            )
            try {
                // The pre-existing source value must survive untouched, not be reset to the
                // ADD COLUMN default that a naive unconditional migration would have applied.
                migrated.query("SELECT appId, source FROM games WHERE appId = 441").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(441L, cursor.getLong(0))
                    assertEquals("FAMILY_SHARED", cursor.getString(1))
                    assertFalse(cursor.moveToNext())
                }

                assertTableInfo(
                    migrated,
                    "excluded_shared_games",
                    listOf(
                        ColumnInfo("appId", "INTEGER", notNull = true, pk = 1),
                        ColumnInfo("name", "TEXT", notNull = true, pk = 0),
                        ColumnInfo("excludedAt", "INTEGER", notNull = true, pk = 0),
                    ),
                )
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    /**
     * v22 -> v23: `player_profile` gains `storeRegion` (add-wishlist-section). Additive and
     * unbackfilled — the column arrives NULL and holds only an explicitly configured region,
     * never the profile's public location — so what matters is that every aggregate already on
     * the row survives.
     */
    @Test
    fun v22ToV23_addsStoreRegionAndPreservesProfileAggregates() {
        val databaseName = "migration-v22-${System.nanoTime()}"
        val database = migrationTestHelper.createDatabase(databaseName, 22)
        try {
            database.execSQL(
                "INSERT INTO player_profile " +
                    "(id, steamId, steamLevel, totalXp, level, currentStreak, longestStreak, " +
                    "gamificationConfigVersion, lastSyncAt, lastSyncError, playtimeBackfilled, " +
                    "personaName, avatarUrl, pendingImportRecompute) VALUES " +
                    "(0, '76561197960287930', 42, 1200, 7, 3, 19, 5, 1700000000000, NULL, 1, " +
                    "'Nero', 'https://cdn/full.jpg', 0)",
            )
        } finally {
            database.close()
        }

        try {
            val migrated = migrationTestHelper.runMigrationsAndValidate(
                databaseName,
                23,
                true,
                BacklogiumDatabase.MIGRATION_22_23,
            )
            try {
                migrated.query(
                    "SELECT steamId, steamLevel, totalXp, level, currentStreak, longestStreak, " +
                        "personaName, avatarUrl, storeRegion FROM player_profile WHERE id = 0",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("76561197960287930", cursor.getString(0))
                    assertEquals(42, cursor.getInt(1))
                    assertEquals(1200, cursor.getInt(2))
                    assertEquals(7, cursor.getInt(3))
                    assertEquals(3, cursor.getInt(4))
                    assertEquals(19, cursor.getInt(5))
                    assertEquals("Nero", cursor.getString(6))
                    assertEquals("https://cdn/full.jpg", cursor.getString(7))
                    assertTrue(cursor.isNull(8))
                    assertFalse(cursor.moveToNext())
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    /**
     * v23 -> v24: the wishlist's own two tables (add-wishlist-section). Purely additive — nothing
     * existing is touched — so the assertions are about the shape that lets wants stay out of the
     * owned library: no foreign key to `games` on either table, and an append-only observation
     * log keyed by its own rowid rather than by app id.
     */
    @Test
    fun v23ToV24_addsWishlistTablesWithNoLinkToGames() {
        val databaseName = "migration-v23-${System.nanoTime()}"
        val database = migrationTestHelper.createDatabase(databaseName, 23)
        try {
            database.execSQL(
                "INSERT INTO games " +
                    "(appId, name, iconUrl, playtimeForever, playtime2Weeks, lastPlaytime, " +
                    "isGoal, targetMinutes, lastSyncedAt, backfillMinutes, source, " +
                    "firstSeenAt, lastPlayedAt, returnedToPlayAt) VALUES " +
                    "(440, 'Owned Game', 'icon-hash', 100, 0, 100, 0, NULL, 1700000000000, 0, " +
                    "'STEAM_OWNED', NULL, NULL, NULL)",
            )
        } finally {
            database.close()
        }

        try {
            val migrated = migrationTestHelper.runMigrationsAndValidate(
                databaseName,
                24,
                true,
                BacklogiumDatabase.MIGRATION_23_24,
            )
            try {
                assertTableInfo(
                    migrated,
                    "wishlist_items",
                    listOf(
                        ColumnInfo("appId", "INTEGER", notNull = true, pk = 1),
                        ColumnInfo("name", "TEXT", notNull = true, pk = 0),
                        ColumnInfo("artworkUrl", "TEXT", notNull = true, pk = 0),
                        ColumnInfo("priority", "INTEGER", notNull = true, pk = 0),
                        ColumnInfo("addedAt", "INTEGER", notNull = true, pk = 0),
                        ColumnInfo("lastSeenAt", "INTEGER", notNull = true, pk = 0),
                    ),
                )

                // An app id the player does not own has no `games` row to reference, so a foreign
                // key here would make the whole feature unstorable.
                migrated.query("PRAGMA foreign_key_list(`wishlist_items`)").use { cursor ->
                    assertFalse(cursor.moveToFirst())
                }
                migrated.query("PRAGMA foreign_key_list(`wishlist_price_observations`)").use { cursor ->
                    assertFalse(cursor.moveToFirst())
                }

                // Two observations for one app must coexist: the log is appended, never updated.
                migrated.execSQL(
                    "INSERT INTO wishlist_price_observations " +
                        "(appId, observedAt, currency, finalMinorUnits, initialMinorUnits, " +
                        "discountPercent, formatted, listFormatted) VALUES " +
                        "(292030, 1700000000000, 'PHP', 209900, 209900, 0, 'P2,099.00', NULL), " +
                        "(292030, 1700000600000, 'PHP', 104950, 209900, 50, 'P1,049.50', 'P2,099.00')",
                )
                migrated.query("SELECT COUNT(*) FROM wishlist_price_observations").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(2, cursor.getInt(0))
                }

                // The owned library is untouched by any of it.
                migrated.query("SELECT COUNT(*) FROM games").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    /** v24 -> v25: successful wishlist reads gain durable freshness metadata. */
    @Test
    fun v24ToV25_addsWishlistReadTimestampAndPreservesProfile() {
        val databaseName = "migration-v24-${System.nanoTime()}"
        val database = migrationTestHelper.createDatabase(databaseName, 24)
        try {
            database.execSQL(
                "INSERT INTO player_profile " +
                    "(id, steamId, steamLevel, totalXp, level, currentStreak, longestStreak, " +
                    "gamificationConfigVersion, lastSyncAt, lastSyncError, playtimeBackfilled, " +
                    "personaName, avatarUrl, storeRegion, pendingImportRecompute) VALUES " +
                    "(0, '76561197960287930', 42, 1200, 7, 3, 19, 5, 1700000000000, NULL, 1, " +
                    "'Nero', 'https://cdn/full.jpg', 'PH', 0)",
            )
        } finally {
            database.close()
        }

        try {
            val migrated = migrationTestHelper.runMigrationsAndValidate(
                databaseName,
                25,
                true,
                BacklogiumDatabase.MIGRATION_24_25,
            )
            try {
                migrated.query(
                    "SELECT steamId, steamLevel, totalXp, level, currentStreak, longestStreak, " +
                        "personaName, avatarUrl, storeRegion, lastSuccessfulWishlistReadAt " +
                        "FROM player_profile WHERE id = 0",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("76561197960287930", cursor.getString(0))
                    assertEquals(42, cursor.getInt(1))
                    assertEquals(1200, cursor.getInt(2))
                    assertEquals(7, cursor.getInt(3))
                    assertEquals(3, cursor.getInt(4))
                    assertEquals(19, cursor.getInt(5))
                    assertEquals("Nero", cursor.getString(6))
                    assertEquals("https://cdn/full.jpg", cursor.getString(7))
                    assertEquals("PH", cursor.getString(8))
                    assertTrue(cursor.isNull(9))
                    assertFalse(cursor.moveToNext())
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    /** v25 -> v26: existing HLTB rows survive and default to automatic device provenance. */
    @Test
    fun v25ToV26_addsHltbOriginAndPreservesRows() {
        val databaseName = "migration-v25-${System.nanoTime()}"
        val database = migrationTestHelper.createDatabase(databaseName, 25)
        try {
            database.execSQL(
                "INSERT INTO hltb_data " +
                    "(appId, hltbId, mainStoryMinutes, mainExtraMinutes, completionistMinutes, " +
                    "allStylesMinutes, fetchedAt, matchStatus, candidatesJson) VALUES " +
                    "(620, 42, 300, 450, 600, 480, 1700000000000, 'RESOLVED', NULL)",
            )
        } finally {
            database.close()
        }

        try {
            val migrated = migrationTestHelper.runMigrationsAndValidate(
                databaseName,
                26,
                true,
                BacklogiumDatabase.MIGRATION_25_26,
            )
            try {
                migrated.query(
                    "SELECT appId, hltbId, completionistMinutes, fetchedAt, matchStatus, origin " +
                        "FROM hltb_data WHERE appId = 620",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(620L, cursor.getLong(0))
                    assertEquals(42L, cursor.getLong(1))
                    assertEquals(600, cursor.getInt(2))
                    assertEquals(1_700_000_000_000L, cursor.getLong(3))
                    assertEquals("RESOLVED", cursor.getString(4))
                    assertEquals("AUTOMATIC", cursor.getString(5))
                    assertFalse(cursor.moveToNext())
                }
                migrated.query("SELECT COUNT(*) FROM hltb_dataset_state").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                migrated.query("SELECT COUNT(*) FROM hltb_dataset_mappings").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                migrated.query("SELECT COUNT(*) FROM hltb_dataset_lengths").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    /**
     * v26 -> v27: a family-shared game's manual playtime estimate
     * (add-shared-game-playtime-and-filter). Additive and independent of `backfillMinutes` — the
     * migration must not touch that column, and every existing row (owned or shared) must default
     * the new column to 0.
     */
    @Test
    fun v26ToV27_addsManualSharedMinutesAndPreservesExistingBackfill() {
        val databaseName = "migration-v26-${System.nanoTime()}"
        val database = migrationTestHelper.createDatabase(databaseName, 26)
        try {
            database.execSQL(
                "INSERT INTO games " +
                    "(appId, name, iconUrl, playtimeForever, playtime2Weeks, lastPlaytime, " +
                    "isGoal, targetMinutes, lastSyncedAt, backfillMinutes, source, " +
                    "firstSeenAt, lastPlayedAt, returnedToPlayAt) VALUES " +
                    "(440, 'Owned Game', 'icon-hash', 12345, 67, 12340, 1, NULL, " +
                    "1700000000000, 42, 'STEAM_OWNED', NULL, NULL, NULL), " +
                    "(441, 'Borrowed Game', 'icon-441', 0, 0, 0, 0, NULL, " +
                    "1700000000001, 0, 'FAMILY_SHARED', 1700000000001, 1700000000001, NULL)",
            )
        } finally {
            database.close()
        }

        try {
            val migrated = migrationTestHelper.runMigrationsAndValidate(
                databaseName,
                27,
                true,
                BacklogiumDatabase.MIGRATION_26_27,
            )
            try {
                migrated.query(
                    "SELECT appId, backfillMinutes, manualSharedMinutes FROM games ORDER BY appId",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(440L, cursor.getLong(0))
                    assertEquals(42, cursor.getInt(1))
                    assertEquals(0, cursor.getInt(2))
                    assertTrue(cursor.moveToNext())
                    assertEquals(441L, cursor.getLong(0))
                    assertEquals(0, cursor.getInt(1))
                    assertEquals(0, cursor.getInt(2))
                    assertFalse(cursor.moveToNext())
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private data class ColumnInfo(val name: String, val type: String, val notNull: Boolean, val pk: Int)

    private fun assertTableInfo(database: SupportSQLiteDatabase, table: String, expected: List<ColumnInfo>) {
        val actual = mutableListOf<ColumnInfo>()
        database.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val pkIndex = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                actual += ColumnInfo(
                    cursor.getString(nameIndex),
                    cursor.getString(typeIndex),
                    cursor.getInt(notNullIndex) != 0,
                    cursor.getInt(pkIndex),
                )
            }
        }
        assertEquals(expected, actual)
    }

    private fun assertForeignKeyReferencesGames(database: SupportSQLiteDatabase, table: String) {
        database.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            val tableIndex = cursor.getColumnIndexOrThrow("table")
            assertTrue(cursor.moveToFirst())
            assertEquals("games", cursor.getString(tableIndex))
        }
    }

    private fun openRawV13Database(databaseName: String): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(13) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `games` (" +
                        "`appId` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`iconUrl` TEXT NOT NULL, " +
                        "`playtimeForever` INTEGER NOT NULL, " +
                        "`playtime2Weeks` INTEGER NOT NULL, " +
                        "`lastPlaytime` INTEGER NOT NULL, " +
                        "`isGoal` INTEGER NOT NULL, " +
                        "`targetMinutes` INTEGER, " +
                        "`lastSyncedAt` INTEGER NOT NULL, " +
                        "`backfillMinutes` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`appId`))",
                )
                db.execSQL(
                    "CREATE TABLE `sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`appId` INTEGER NOT NULL, " +
                        "`startAt` INTEGER NOT NULL, " +
                        "`endAt` INTEGER, " +
                        "`minutes` INTEGER NOT NULL, " +
                        "`open` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`appId`) REFERENCES `games`(`appId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE TABLE `daily_progress` (" +
                        "`date` TEXT NOT NULL, " +
                        "`minutesPlayed` INTEGER NOT NULL, " +
                        "`goalMinutesPlayed` INTEGER NOT NULL, " +
                        "`questMet` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`date`))",
                )
                db.execSQL(
                    "CREATE TABLE `player_profile` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`steamId` TEXT NOT NULL, " +
                        "`steamLevel` INTEGER NOT NULL, " +
                        "`totalXp` INTEGER NOT NULL, " +
                        "`level` INTEGER NOT NULL, " +
                        "`currentStreak` INTEGER NOT NULL, " +
                        "`longestStreak` INTEGER NOT NULL, " +
                        "`lastSyncAt` INTEGER NOT NULL, " +
                        "`lastSyncError` TEXT, " +
                        "`playtimeBackfilled` INTEGER NOT NULL, " +
                        "`personaName` TEXT, " +
                        "`avatarUrl` TEXT, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE `hltb_data` (" +
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
                db.execSQL(
                    "CREATE TABLE `achievements` (" +
                        "`appId` INTEGER NOT NULL, " +
                        "`apiName` TEXT NOT NULL, " +
                        "`displayName` TEXT, " +
                        "`iconUrl` TEXT, " +
                        "`unlocked` INTEGER NOT NULL, " +
                        "`unlockedAt` INTEGER, " +
                        "`globalPercent` REAL, " +
                        "`snapshotPercent` REAL, " +
                        "`description` TEXT, " +
                        "`hidden` INTEGER NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`appId`, `apiName`), " +
                        "FOREIGN KEY(`appId`) REFERENCES `games`(`appId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE TABLE `sync_runs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`trigger` TEXT NOT NULL, " +
                        "`requestCount` INTEGER NOT NULL, " +
                        "`requestMillis` INTEGER NOT NULL, " +
                        "`gamesExamined` INTEGER NOT NULL, " +
                        "`gamesUpdated` INTEGER NOT NULL, " +
                        "`outcome` TEXT NOT NULL, " +
                        "`errorMessage` TEXT)",
                )
                db.execSQL("CREATE INDEX `index_sync_runs_startedAt` ON `sync_runs` (`startedAt`)")
                db.execSQL(
                    "CREATE TABLE `request_breakdowns` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`runId` INTEGER NOT NULL, " +
                        "`endpoint` TEXT NOT NULL, " +
                        "`status` INTEGER, " +
                        "`requestCount` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`runId`) REFERENCES `sync_runs`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX `index_request_breakdowns_runId` ON `request_breakdowns` (`runId`)")
                db.execSQL(
                    "CREATE TABLE `presence_decisions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`at` INTEGER NOT NULL, " +
                        "`trigger` TEXT NOT NULL, " +
                        "`outcome` TEXT NOT NULL, " +
                        "`appId` INTEGER, " +
                        "`retainedPriorState` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX `index_presence_decisions_at` ON `presence_decisions` (`at`)")
                db.execSQL(
                    "CREATE TABLE `collections` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`mode` TEXT NOT NULL, " +
                        "`sort` TEXT NOT NULL, " +
                        "`targetDate` TEXT, " +
                        "`accent` TEXT, " +
                        "`timeBasis` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`description` TEXT, " +
                        "`displayOrder` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE `collection_members` (" +
                        "`collectionId` INTEGER NOT NULL, " +
                        "`appId` INTEGER NOT NULL, " +
                        "`orderIndex` INTEGER NOT NULL, " +
                        "`done` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`collectionId`, `appId`), " +
                        "FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX `index_collection_members_collectionId` " +
                        "ON `collection_members` (`collectionId`)",
                )
                db.execSQL(
                    "CREATE TABLE `game_genre_cache` (" +
                        "`appId` INTEGER NOT NULL, " +
                        "`genresJson` TEXT NOT NULL, " +
                        "`checkedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`appId`), " +
                        "FOREIGN KEY(`appId`) REFERENCES `games`(`appId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX `index_sessions_appId` ON `sessions` (`appId`)")
                db.execSQL(
                    "CREATE INDEX `index_sessions_appId_startAt_endAt` " +
                        "ON `sessions` (`appId`, `startAt`, `endAt`)",
                )
                db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                db.execSQL(
                    "INSERT INTO room_master_table (id, identity_hash) VALUES(42, 'raw-v13-fixture')",
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration(context, databaseName, callback),
        )
    }

    private fun SupportSQLiteDatabase.seedRepresentativeData() {
        execSQL(
            "INSERT INTO games " +
                "(appId, name, iconUrl, playtimeForever, playtime2Weeks, lastPlaytime, " +
                "isGoal, targetMinutes, lastSyncedAt, backfillMinutes) VALUES " +
                "(440, 'Team Fortress 2', 'icon-440', 12345, 67, 12340, 1, 24000, " +
                "1700000000000, 42), " +
                "(441, 'Sentinel Game', 'icon-441', 10, 0, 10, 0, NULL, 1700000000001, 0)",
        )
        execSQL(
            "INSERT INTO sessions (id, appId, startAt, endAt, minutes, open) VALUES " +
                "(7, 440, 1700000010000, 1700005410000, 90, 0)",
        )
        execSQL(
            "INSERT INTO daily_progress " +
                "(date, minutesPlayed, goalMinutesPlayed, questMet) VALUES " +
                "('2026-08-13', 90, 60, 1)",
        )
        execSQL(
            "INSERT INTO sync_runs " +
                "(id, startedAt, durationMs, trigger, requestCount, requestMillis, " +
                "gamesExamined, gamesUpdated, outcome, errorMessage) VALUES " +
                "(23, 1700000060000, 4567, 'SCHEDULED', 9, 3210, 440, 2, " +
                "'SUCCESS', 'legacy warning')",
        )
        execSQL(
            "INSERT INTO request_breakdowns " +
                "(id, runId, endpoint, status, requestCount, durationMs) VALUES " +
                "(31, 23, 'player-summaries', 200, 4, 1234)",
        )
        execSQL(
            "INSERT INTO achievements " +
                "(appId, apiName, displayName, iconUrl, unlocked, unlockedAt, globalPercent, " +
                "snapshotPercent, description, hidden, fetchedAt) VALUES " +
                "(440, 'ACH_WIN', 'Win one match', 'achievement-440', 1, 1700000020000, " +
                "12.5, 13.75, 'Win a match', 0, 1700000030000)",
        )
        execSQL(
            "INSERT INTO achievements " +
                "(appId, apiName, displayName, iconUrl, unlocked, unlockedAt, globalPercent, " +
                "snapshotPercent, description, hidden, fetchedAt) VALUES " +
                "(441, '__no_achievements__', NULL, NULL, 0, NULL, NULL, NULL, NULL, 0, " +
                "1700000040000)",
        )
        execSQL(
            "INSERT INTO player_profile " +
                "(id, steamId, steamLevel, totalXp, level, currentStreak, longestStreak, " +
                "lastSyncAt, lastSyncError, playtimeBackfilled, personaName, avatarUrl) VALUES " +
                "(0, '76561198000000000', 42, 9876, 8, 3, 12, 1700000050000, " +
                "'transient', 1, 'Player One', 'avatar-url')",
        )
    }

    private fun SupportSQLiteDatabase.assertRepresentativeData() {
        query(
            "SELECT appId, name, iconUrl, playtimeForever, playtime2Weeks, lastPlaytime, " +
                "isGoal, targetMinutes, lastSyncedAt, backfillMinutes FROM games WHERE appId = 440",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(440L, cursor.getLong(0))
            assertEquals("Team Fortress 2", cursor.getString(1))
            assertEquals("icon-440", cursor.getString(2))
            assertEquals(12345, cursor.getInt(3))
            assertEquals(67, cursor.getInt(4))
            assertEquals(12340, cursor.getInt(5))
            assertEquals(1, cursor.getInt(6))
            assertEquals(24000, cursor.getInt(7))
            assertEquals(1700000000000L, cursor.getLong(8))
            assertEquals(42, cursor.getInt(9))
            assertFalse(cursor.moveToNext())
        }

        query(
            "SELECT id, appId, startAt, endAt, minutes, open FROM sessions WHERE id = 7",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(7L, cursor.getLong(0))
            assertEquals(440L, cursor.getLong(1))
            assertEquals(1700000010000L, cursor.getLong(2))
            assertEquals(1700005410000L, cursor.getLong(3))
            assertEquals(90, cursor.getInt(4))
            assertEquals(0, cursor.getInt(5))
        }

        query(
            "SELECT date, minutesPlayed, goalMinutesPlayed, questMet " +
                "FROM daily_progress WHERE date = '2026-08-13'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("2026-08-13", cursor.getString(0))
            assertEquals(90, cursor.getInt(1))
            assertEquals(60, cursor.getInt(2))
            assertEquals(1, cursor.getInt(3))
        }

        query(
            "SELECT id, startedAt, durationMs, trigger, requestCount, requestMillis, " +
                "gamesExamined, gamesUpdated, outcome, errorMessage, hotCount, warmCount, " +
                "coldCount, neverCount FROM sync_runs WHERE id = 23",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(23L, cursor.getLong(0))
            assertEquals(1700000060000L, cursor.getLong(1))
            assertEquals(4567L, cursor.getLong(2))
            assertEquals("SCHEDULED", cursor.getString(3))
            assertEquals(9, cursor.getInt(4))
            assertEquals(3210L, cursor.getLong(5))
            assertEquals(440, cursor.getInt(6))
            assertEquals(2, cursor.getInt(7))
            assertEquals("SUCCESS", cursor.getString(8))
            assertEquals("legacy warning", cursor.getString(9))
            assertEquals(0, cursor.getInt(10))
            assertEquals(0, cursor.getInt(11))
            assertEquals(0, cursor.getInt(12))
            assertEquals(0, cursor.getInt(13))
            assertFalse(cursor.moveToNext())
        }

        query("PRAGMA table_info(`sync_runs`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            val tierDefaults = mutableMapOf<String, String?>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                if (name in setOf("hotCount", "warmCount", "coldCount", "neverCount")) {
                    tierDefaults[name] = cursor.getString(defaultIndex)
                }
            }
            assertEquals(
                mapOf(
                    "hotCount" to "0",
                    "warmCount" to "0",
                    "coldCount" to "0",
                    "neverCount" to "0",
                ),
                tierDefaults,
            )
        }

        query(
            "SELECT id, runId, endpoint, status, requestCount, durationMs " +
                "FROM request_breakdowns WHERE id = 31",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(31L, cursor.getLong(0))
            assertEquals(23L, cursor.getLong(1))
            assertEquals("player-summaries", cursor.getString(2))
            assertEquals(200, cursor.getInt(3))
            assertEquals(4, cursor.getInt(4))
            assertEquals(1234L, cursor.getLong(5))
            assertFalse(cursor.moveToNext())
        }

        query(
            "SELECT COUNT(*) FROM request_breakdowns b " +
                "INNER JOIN sync_runs r ON r.id = b.runId " +
                "WHERE b.id = 31 AND r.id = 23",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        query("PRAGMA foreign_key_list(`request_breakdowns`)").use { cursor ->
            val tableIndex = cursor.getColumnIndexOrThrow("table")
            val fromIndex = cursor.getColumnIndexOrThrow("from")
            val toIndex = cursor.getColumnIndexOrThrow("to")
            assertTrue(cursor.moveToFirst())
            assertEquals("sync_runs", cursor.getString(tableIndex))
            assertEquals("runId", cursor.getString(fromIndex))
            assertEquals("id", cursor.getString(toIndex))
        }

        query(
            "SELECT appId, apiName, displayName, iconUrl, unlocked, unlockedAt, globalPercent, " +
                "snapshotPercent, description, hidden, fetchedAt FROM achievements " +
                "WHERE appId = 440 AND apiName = 'ACH_WIN'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(440L, cursor.getLong(0))
            assertEquals("ACH_WIN", cursor.getString(1))
            assertEquals("Win one match", cursor.getString(2))
            assertEquals("achievement-440", cursor.getString(3))
            assertEquals(1, cursor.getInt(4))
            assertEquals(1700000020000L, cursor.getLong(5))
            assertEquals(12.5, cursor.getDouble(6), 0.0)
            assertEquals(13.75, cursor.getDouble(7), 0.0)
            assertEquals("Win a match", cursor.getString(8))
            assertEquals(0, cursor.getInt(9))
            assertEquals(1700000030000L, cursor.getLong(10))
        }

        query(
            "SELECT id, steamId, steamLevel, totalXp, level, currentStreak, longestStreak, " +
                "lastSyncAt, lastSyncError, playtimeBackfilled, personaName, avatarUrl " +
                "FROM player_profile WHERE id = 0",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertEquals("76561198000000000", cursor.getString(1))
            assertEquals(42, cursor.getInt(2))
            assertEquals(9876, cursor.getInt(3))
            assertEquals(8, cursor.getInt(4))
            assertEquals(3, cursor.getInt(5))
            assertEquals(12, cursor.getInt(6))
            assertEquals(1700000050000L, cursor.getLong(7))
            assertEquals("transient", cursor.getString(8))
            assertEquals(1, cursor.getInt(9))
            assertEquals("Player One", cursor.getString(10))
            assertEquals("avatar-url", cursor.getString(11))
        }

        query(
            "SELECT appId, playerStateFetchedAt, schemaFetchedAt, hasAchievements, checkedAt " +
                "FROM game_achievement_sync WHERE appId = 441",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(441L, cursor.getLong(0))
            assertEquals(1700000040000L, cursor.getLong(1))
            assertTrue(cursor.isNull(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals(1700000040000L, cursor.getLong(4))
        }

        query(
            "SELECT COUNT(*) FROM achievements WHERE apiName = '__no_achievements__'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }
}
