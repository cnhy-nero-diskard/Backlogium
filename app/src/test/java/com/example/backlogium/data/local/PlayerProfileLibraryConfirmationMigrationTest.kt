package com.example.backlogium.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Host-side (Robolectric) v42 -> v44 migration coverage for the confirmed-library-baseline fields,
 * the attributable library-poll evidence table, and the pending-recompute provenance triple
 * (stabilize-first-run-setup). Both v43/v44 additions landed on an unreleased branch, so the
 * host test drives the single `MIGRATION_42_43` + combined `MIGRATION_43_44` pair to the final
 * v44 schema.
 *
 * `androidx.room:room-testing` is an instrumentation-only dependency in this project, so this test
 * re-implements the host half of what [MigrationTest]'s `MigrationTestHelper` provides: it builds
 * the genuine v42 database by executing the `createSql`/`indices` lifted from the exported
 * `schemas/.../42.json` through a real [FrameworkSQLiteOpenHelperFactory] (Robolectric's native
 * SQLite — no new library), seeds representative legacy data, and then exercises the migrations in
 * the two directions that matter:
 *
 * 1. A real Room `open()` driven upgrade, from a v42 database that already carries the
 *    `room_master_table` identity (exactly what an on-device Room v42 install looks like), with
 *    `MIGRATION_42_43` and `MIGRATION_43_44` registered — proving Room's own upgrade path can run
 *    and accept both hops.
 * 2. The migrations' SQL applied directly on real SQLite followed by a fresh Room `open()` at v44
 *    with **no** `room_master_table` — which makes `RoomOpenHelper.checkIdentity` fall into its
 *    deep `onValidateSchema` path and compare every table against the compiled v44 entity schemas.
 *    If any migration produced a wrong column, type, nullability, index, or foreign key anywhere,
 *    that open throws instead of silently passing (Room 2.8.4 `RoomOpenHelper`).
 *
 * The data assertions are deliberately conservative: they prove the migrations never inferred
 * baseline confirmation from `lastSyncAt`, a nonempty `games` table, or `playtimeBackfilled`, never
 * invented an import request identity from restored rows, and that the imported flag, game backfill
 * offsets, sessions, and cloud historical operation/journal receipt rows (with their v42-only
 * fields) all survive.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerProfileLibraryConfirmationMigrationTest {

    // Robolectric embeds the method name in its temporary directory. Keep test names short so
    // database paths, including SQLite's journal/WAL suffixes, fit Windows native path limits.

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    // ---- Exported-schema model (subset the test actually needs) -----------------------------

    @Serializable
    private data class V42Schema(val database: V42Database)

    @Serializable
    private data class V42Database(
        val version: Int,
        val entities: List<V42Entity>,
        val setupQueries: List<String> = emptyList(),
    )

    @Serializable
    private data class V42Entity(
        val tableName: String,
        val createSql: String,
        val indices: List<V42Index> = emptyList(),
    )

    @Serializable
    private data class V42Index(val createSql: String)

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadV42Schema(): V42Schema {
        val relative = "com.example.backlogium.data.local.BacklogiumDatabase"
        val candidates = listOf(
            File("schemas/$relative/42.json"),
            File("app/schemas/$relative/42.json"),
            File("../app/schemas/$relative/42.json"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error(
                "Cannot locate exported Room schema 42.json " +
                    "(cwd = ${File(".").absolutePath}; tried ${candidates.joinToString()})",
            )
        return json.decodeFromString(file.readText())
    }

    // ---- v42 builder + representative legacy data -------------------------------------------

    private fun SupportSQLiteOpenHelper.Factory.createV42Helper(name: String): SupportSQLiteOpenHelper =
        create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )

    /**
     * Builds a real on-disk v42 database by executing the exported schema, seeds representative
     * legacy data, and — when requested — the schema's own `setupQueries` (the Room identity
     * table + v42 hash), so the file is byte-for-byte shaped like an install that shipped v42.
     * Sets `user_version` to 42 and closes the raw helper.
     */
    private fun seedV42Database(name: String, includeRoomIdentity: Boolean) {
        val schema = loadV42Schema()
        val helper = FrameworkSQLiteOpenHelperFactory().createV42Helper(name)
        try {
            val db = helper.writableDatabase
            schema.database.entities.forEach { entity ->
                db.execSQL(entity.createSql.replace("\${TABLE_NAME}", entity.tableName))
                entity.indices.forEach { index ->
                    db.execSQL(index.createSql.replace("\${TABLE_NAME}", entity.tableName))
                }
            }
            if (includeRoomIdentity) {
                schema.database.setupQueries.forEach { db.execSQL(it) }
            }
            db.seedRepresentativeV42Data()
            db.version = 42
        } finally {
            helper.close()
        }
    }

    private fun SupportSQLiteDatabase.seedRepresentativeV42Data() {
        // Nonempty library with frozen backfill offsets that an import produced.
        execSQL(
            "INSERT INTO games (appId, name, iconUrl, playtimeForever, playtime2Weeks, " +
                "lastPlaytime, isGoal, targetMinutes, lastSyncedAt, backfillMinutes, source, " +
                "firstSeenAt, lastPlayedAt, returnedToPlayAt, manualSharedMinutes) VALUES " +
                "(440, 'Team Fortress 2', '', 5000, 0, 5000, 1, 1200, 1700000000000, 42, " +
                "'STEAM_OWNED', 1700000000000, NULL, NULL, 0)",
        )
        execSQL(
            "INSERT INTO games (appId, name, iconUrl, playtimeForever, playtime2Weeks, " +
                "lastPlaytime, isGoal, targetMinutes, lastSyncedAt, backfillMinutes, source, " +
                "firstSeenAt, lastPlayedAt, returnedToPlayAt, manualSharedMinutes) VALUES " +
                "(441, 'Shared Game', '', 30, 0, 30, 0, NULL, 1700000000001, 17, " +
                "'FAMILY_SHARED', 1700000000001, NULL, NULL, 0)",
        )
        // A tracked dated session with full provenance columns.
        execSQL(
            "INSERT INTO sessions (id, appId, startAt, endAt, minutes, open, " +
                "recoveredSharedPlay, timingInformedSteamPlay, openAppId) VALUES " +
                "(7, 440, 1700000010000, 1700005410000, 90, 0, 'FULL', 'FULL', NULL)",
        )
        // A profile that has synced, imported, and carried a pending recompute: every plausible
        // "readiness" hint is set, so the assertion that confirmation stays NULL proves no
        // inference from lastSyncAt / nonempty games / playtimeBackfilled / scheduler success.
        execSQL(
            "INSERT INTO player_profile (id, steamId, steamLevel, totalXp, level, currentStreak, " +
                "longestStreak, gamificationConfigVersion, lastSyncAt, lastSyncError, " +
                "playtimeBackfilled, personaName, avatarUrl, storeRegion, pendingImportRecompute, " +
                "lastSuccessfulWishlistReadAt, pendingXpIntegrityCorrection) VALUES " +
                "(0, '76561198000000000', 42, 9876, 8, 3, 12, 5, 1700000050000, NULL, 1, " +
                "'Player One', 'avatar-url', 'PH', 1, 1700000060000, 1)",
        )
        // Cloud historical operation receipt carrying every v42-only field.
        execSQL(
            "INSERT INTO cloud_historical_operations (operationId, account, readerGeneration, " +
                "endpointIdentity, startChoice, zoneId, selectedStartAt, fromAt, throughAt, " +
                "confirmedCutoffAt, frozenCurrentObservedAt, frozenCurrentAppId, " +
                "frozenCurrentGameName, frozenCurrentPersonastate, frozenCurrentSince, " +
                "frozenCurrentCoverageLapseFrom, frozenCurrentCoverageLapseRecoveredAt, " +
                "frozenCurrentSchemaVersion, lastPositionAt, pagesFetched, lastIngestedPageNumber, " +
                "transitionsFetched, coveredStartAt, coveredEndAt, acquisitionComplete, state, " +
                "createdAt, updatedAt) VALUES " +
                "('receipt-v42', '76561198000000001', 2, " +
                "'https://presence.example.test/readPresence', 'CUSTOM_RANGE', 'Asia/Manila', " +
                "10, 10, 20, 15, 18, 440, 'Team Fortress 2', 1, 17, NULL, NULL, 2, 16, " +
                "1, 3, 4, 10, 20, 1, 'COMPLETE', 1, 2)",
        )
        execSQL(
            "INSERT INTO cloud_historical_intervals (operationId, account, readerGeneration, " +
                "endpointIdentity, appId, startAt, endAt, ongoing, coverage, observedUntil, " +
                "coverageLapseFrom, coverageLapseRecoveredAt, mayHaveStartedBefore, gameName, " +
                "windowStart) VALUES " +
                "('receipt-v42', '76561198000000001', 2, " +
                "'https://presence.example.test/readPresence', 440, 10, 20, 0, 'FULL', 20, " +
                "NULL, NULL, 0, 'Team Fortress 2', 10)",
        )
        execSQL(
            "INSERT INTO cloud_historical_boundaries (operationId, account, readerGeneration, " +
                "endpointIdentity, kind, at, appId, gameName, personastate, " +
                "previousLastObservedAt, previousCoverageLapseFrom, previousCoverageLapseRecoveredAt, " +
                "schemaVersion) VALUES " +
                "('receipt-v42', '76561198000000001', 2, " +
                "'https://presence.example.test/readPresence', 'OPEN', 15, 440, 'Team Fortress 2', " +
                "1, NULL, NULL, NULL, 2)",
        )
        // Journal receipt — the durable apply evidence the setup path must not disturb.
        execSQL(
            "INSERT INTO cloud_historical_journals (operationId, account, readerGeneration, " +
                "endpointIdentity, state, payloadVersion, payloadJson, updatedAt) VALUES " +
                "('receipt-v42', '76561198000000001', 2, " +
                "'https://presence.example.test/readPresence', 'APPLIED', 1, " +
                "'{\"appliedAt\":1700000070000}', 1700000070000)",
        )
    }

    // ---- Assertions -------------------------------------------------------------------------

    private data class Column(val name: String, val type: String, val notNull: Boolean)

    private fun assertPlayerProfileV44Schema(raw: SupportSQLiteDatabase) {
        val columns = mutableListOf<Column>()
        raw.query("PRAGMA table_info(`player_profile`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            val type = cursor.getColumnIndexOrThrow("type")
            val notNull = cursor.getColumnIndexOrThrow("notnull")
            while (cursor.moveToNext()) {
                columns += Column(cursor.getString(name), cursor.getString(type), cursor.getInt(notNull) != 0)
            }
        }
        assertEquals(
            "v44 player_profile must append the two confirmation columns and the three provenance " +
                "columns in order",
            listOf(
                "id", "steamId", "steamLevel", "totalXp", "level", "currentStreak",
                "longestStreak", "gamificationConfigVersion", "lastSyncAt", "lastSyncError",
                "playtimeBackfilled", "personaName", "avatarUrl", "storeRegion",
                "pendingImportRecompute", "lastSuccessfulWishlistReadAt",
                "pendingXpIntegrityCorrection", "confirmedLibrarySteamId", "confirmedLibraryAt",
                "pendingImportRecomputeSource", "pendingImportRecomputeSteamId",
                "pendingImportRecomputeRequestId",
            ),
            columns.map(Column::name),
        )
        val appended = columns.filter {
            it.name in setOf(
                "confirmedLibrarySteamId", "confirmedLibraryAt",
                "pendingImportRecomputeSource", "pendingImportRecomputeSteamId",
                "pendingImportRecomputeRequestId",
            )
        }
        assertEquals(
            mapOf(
                "confirmedLibrarySteamId" to "TEXT",
                "confirmedLibraryAt" to "INTEGER",
                "pendingImportRecomputeSource" to "TEXT",
                "pendingImportRecomputeSteamId" to "TEXT",
                "pendingImportRecomputeRequestId" to "TEXT",
            ),
            appended.associate { it.name to it.type },
        )
        assertTrue("all appended migration columns must be nullable", appended.all { !it.notNull })
    }

    /**
     * The provenance triple arrives NULL on every migrated install: a legacy pending marker keeps
     * its backup-merge meaning and no explicit request identity is invented from restored rows.
     */
    private fun assertProvenanceStaysNull(raw: SupportSQLiteDatabase) {
        raw.query(
            "SELECT pendingImportRecompute, pendingImportRecomputeSource, " +
                "pendingImportRecomputeSteamId, pendingImportRecomputeRequestId " +
                "FROM player_profile WHERE id = 0",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertTrue("legacy marker must carry no invented source", cursor.isNull(1))
            assertTrue("legacy marker must carry no invented account", cursor.isNull(2))
            assertTrue("legacy marker must carry no invented request", cursor.isNull(3))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertV42DataSurvives(raw: SupportSQLiteDatabase) {
        raw.query("SELECT appId, backfillMinutes, manualSharedMinutes FROM games ORDER BY appId").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(440L, cursor.getLong(0))
            assertEquals(42, cursor.getInt(1))
            assertEquals(0, cursor.getInt(2))
            assertTrue(cursor.moveToNext())
            assertEquals(441L, cursor.getLong(0))
            assertEquals(17, cursor.getInt(1))
            assertEquals(0, cursor.getInt(2))
            assertFalse(cursor.moveToNext())
        }

        raw.query(
            "SELECT minutes, open, recoveredSharedPlay, timingInformedSteamPlay " +
                "FROM sessions WHERE id = 7",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(90, cursor.getInt(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals("FULL", cursor.getString(2))
            assertEquals("FULL", cursor.getString(3))
            assertFalse(cursor.moveToNext())
        }

        raw.query(
            "SELECT operationId, startChoice, zoneId, lastIngestedPageNumber, confirmedCutoffAt, " +
                "frozenCurrentAppId, frozenCurrentGameName, pagesFetched, acquisitionComplete, " +
                "state FROM cloud_historical_operations",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("receipt-v42", cursor.getString(0))
            assertEquals("CUSTOM_RANGE", cursor.getString(1))
            assertEquals("Asia/Manila", cursor.getString(2))
            assertEquals(3, cursor.getInt(3))
            assertEquals(15, cursor.getInt(4))
            assertEquals(440, cursor.getInt(5))
            assertEquals("Team Fortress 2", cursor.getString(6))
            assertEquals(1, cursor.getInt(7))
            assertEquals(1, cursor.getInt(8))
            assertEquals("COMPLETE", cursor.getString(9))
            assertFalse(cursor.moveToNext())
        }

        raw.query(
            "SELECT operationId, state, payloadVersion, payloadJson, updatedAt " +
                "FROM cloud_historical_journals",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("receipt-v42", cursor.getString(0))
            assertEquals("APPLIED", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals("{\"appliedAt\":1700000070000}", cursor.getString(3))
            assertEquals(1_700_000_070_000L, cursor.getLong(4))
            assertFalse(cursor.moveToNext())
        }

        raw.query("SELECT COUNT(*) FROM cloud_historical_intervals").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        raw.query("SELECT COUNT(*) FROM cloud_historical_boundaries").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    private fun assertProfileStaysUnconfirmed(raw: SupportSQLiteDatabase) {
        raw.query(
            "SELECT steamId, totalXp, playtimeBackfilled, pendingImportRecompute, lastSyncAt, " +
                "confirmedLibrarySteamId, confirmedLibraryAt FROM player_profile WHERE id = 0",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("76561198000000000", cursor.getString(0))
            assertEquals(9876L, cursor.getLong(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals(1, cursor.getInt(3))
            assertEquals(1_700_000_050_000L, cursor.getLong(4))
            // The whole point of the conservative default: a synced, imported, nonempty library
            // gets NULL confirmation, not an inferred baseline.
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.isNull(6))
            assertFalse(cursor.moveToNext())
        }
    }

    // ---- Tests ------------------------------------------------------------------------------

    /**
     * The genuine on-device upgrade path: a v42 file carrying Room's own identity row, opened with
     * `MIGRATION_42_43` and the combined `MIGRATION_43_44` registered. Room runs the migrations
     * themselves.
     */
    @Test
    fun v42RoomUpgradePreservesLegacyState() = runBlocking {
        val name = "migration-v42-room-${System.nanoTime()}"
        seedV42Database(name, includeRoomIdentity = true)

        val db = Room.databaseBuilder(context, BacklogiumDatabase::class.java, name)
            .addMigrations(
                BacklogiumDatabase.MIGRATION_42_43,
                BacklogiumDatabase.MIGRATION_43_44,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val profile = checkNotNull(db.playerProfileDao().get()) {
                "upgrade must keep the seeded profile"
            }
            assertEquals("76561198000000000", profile.steamId)
            assertEquals(9876L, profile.totalXp)
            assertTrue(profile.playtimeBackfilled)
            assertTrue(profile.pendingImportRecompute)
            assertNull(profile.confirmedLibrarySteamId)
            assertNull(profile.confirmedLibraryAt)
            assertNull(profile.pendingImportRecomputeSource)
            assertNull(profile.pendingImportRecomputeSteamId)
            assertNull(profile.pendingImportRecomputeRequestId)

            val raw = db.openHelper.writableDatabase
            assertPlayerProfileV44Schema(raw)
            assertV42DataSurvives(raw)
            assertProfileStaysUnconfirmed(raw)
            assertProvenanceStaysNull(raw)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * The migrations' SQL applied directly on real SQLite, followed by Room opening the stamped
     * v44 file with no identity row — the path where Room runs its deep `onValidateSchema`
     * comparison of every table against the compiled v44 entity schemas. Any schema drift the
     * migrations introduced fails the open.
     */
    @Test
    fun v42SchemaValidationPreservesReceipts() {
        val name = "migration-v42-validate-${System.nanoTime()}"
        val schema = loadV42Schema()
        val helper = FrameworkSQLiteOpenHelperFactory().createV42Helper(name)
        try {
            val db = helper.writableDatabase
            schema.database.entities.forEach { entity ->
                db.execSQL(entity.createSql.replace("\${TABLE_NAME}", entity.tableName))
                entity.indices.forEach { index ->
                    db.execSQL(index.createSql.replace("\${TABLE_NAME}", entity.tableName))
                }
            }
            db.seedRepresentativeV42Data()
            // No room_master_table on purpose: with none present, RoomOpenHelper.checkIdentity
            // delegates to onValidateSchema and performs the full TableInfo comparison.
            BacklogiumDatabase.MIGRATION_42_43.migrate(db)
            BacklogiumDatabase.MIGRATION_43_44.migrate(db)
            db.version = 44
        } finally {
            helper.close()
        }

        val db = Room.databaseBuilder(context, BacklogiumDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            val raw = db.openHelper.writableDatabase
            assertPlayerProfileV44Schema(raw)
            assertV42DataSurvives(raw)
            assertProfileStaysUnconfirmed(raw)
            assertProvenanceStaysNull(raw)
            runBlocking {
                val profile = checkNotNull(db.playerProfileDao().get()) {
                    "deep-validated v44 must still read the seeded profile"
                }
                assertNull(profile.confirmedLibrarySteamId)
                assertNull(profile.confirmedLibraryAt)
                assertNull(profile.pendingImportRecomputeSource)
                assertNull(profile.pendingImportRecomputeSteamId)
                assertNull(profile.pendingImportRecomputeRequestId)
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
