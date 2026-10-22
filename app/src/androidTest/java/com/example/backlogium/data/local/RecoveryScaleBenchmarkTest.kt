package com.example.backlogium.data.local

import android.content.ContentValues
import android.database.Cursor
import android.database.CursorWrapper
import android.database.sqlite.SQLiteTransactionListener
import android.os.CancellationSignal
import android.os.Debug
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.data.backup.BackupExportMapper
import com.example.backlogium.data.backup.BackupFile
import com.example.backlogium.data.backup.BackupMergeEngine
import com.example.backlogium.data.backup.BackupRepository
import com.example.backlogium.data.backup.DaoRecoveryBackupStore
import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryEvidenceQuality
import com.example.backlogium.data.local.entity.RecoveryProvenance
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.DataStoreSettingsRepository
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitAdapter
import com.example.backlogium.data.repo.SessionActionWriter
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.PlaytimeObservationCommitter
import com.example.backlogium.domain.ProgressMarks
import com.example.backlogium.domain.ProgressTransitionCoordinator
import com.example.backlogium.domain.RecoveryDailyCreditCoordinator
import com.example.backlogium.domain.RecoveryPlacementReconcileUseCase
import com.example.backlogium.domain.RecoveryPlacementReconciler.RecoveryReconcileOperation
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.UnknownTimeMaterializer
import com.example.backlogium.domain.PlaytimeEvidenceSource
import com.example.backlogium.domain.PlaytimeTimingEvidence
import com.example.backlogium.gamification.RuleConfig
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic non-personal Room workloads for OpenSpec task 6.1. */
@RunWith(AndroidJUnit4::class)
@androidx.test.filters.LargeTest
class RecoveryScaleBenchmarkTest {

    @Test fun realistic250Games5000Contributions() = runSelected("S", Scale("S", 250, 5_000))
    @Test fun large1000Games25000Contributions() = runSelected("M", Scale("M", 1_000, 25_000))
    @Test fun stress3000Games100000Contributions() = runSelected("L", Scale("L", 3_000, 100_000))

    private fun runSelected(expectedTier: String, scale: Scale) = runBlocking {
        // Keep the multi-hour stress workload opt-in rather than surprising every connected test
        // run. The command line passes -Pandroid.testInstrumentationRunnerArguments.recoveryScale=S|M|L.
        val selected = InstrumentationRegistry.getArguments().getString("recoveryScale")
        assumeTrue("Set instrumentation argument recoveryScale=$expectedTier to run this seeded benchmark", selected == expectedTier)
        runScale(scale)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun runScale(scale: Scale) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stopAfter = InstrumentationRegistry.getArguments().getString("recoveryStage")
        val metrics = SqlMetrics()
        val name = "rb${scale.games}${System.nanoTime()}.db"
        val database = openDatabase(context, name, metrics)
        try {
            metrics.enabled = false
            val seedStart = System.nanoTime()
            val seed = seedDatabase(database, scale)
            val seedNanos = System.nanoTime() - seedStart
            verifySeed(database, scale, seed)
            val seedPssKb = pssKb()
            Log.i(
                TAG,
                "SEED,${scale.label},fixtureSeed=$FIXTURE_SEED,games=${seed.games},contributions=${seed.contributions}," +
                    "quietGames=${seed.quietGameCount}," +
                    "authorized=${seed.authorizedMinutes},sessions=${seed.sessions}," +
                    "timed=${seed.timedPlacements},allocations=${seed.allocations}," +
                    "pending=${seed.pendingAllocations},superseded=${seed.supersededAllocations}," +
                    "recipes=${seed.recipes},completeRecipes=${COMPLETE_RECIPE_COUNT}," +
                    "seedNs=$seedNanos,pssKb=$seedPssKb",
            )

            val settings = SettingsDataStore(context)
            val time = BenchmarkTime
            val materializer = UnknownTimeMaterializer(
                database = database,
                recoveryDao = database.playtimeRecoveryDao(),
                dailyCredit = RecoveryDailyCreditCoordinator(database.dailyProgressDao()),
            ).apply { transactionDurationObserver = metrics::recordScopedTransactionDuration }
            drainRecipe(materializer, seed.completeSparseRecipeId)
            drainRecipe(materializer, seed.completeDenseRecipeId)
            val sparseComplete = requireNotNull(database.playtimeRecoveryDao().recipe(seed.completeSparseRecipeId))
            val denseComplete = requireNotNull(database.playtimeRecoveryDao().recipe(seed.completeDenseRecipeId))
            assertEquals(SPARSE_RECIPE_MINUTES, sparseComplete.materializedMinutes)
            assertEquals(DENSE_RECIPE_MINUTES, denseComplete.materializedMinutes)
            val sparseCompleteRows = database.playtimeRecoveryDao()
                .activeAllocations(seed.completeSparseRecipeId).size
            val denseCompleteRows = database.playtimeRecoveryDao()
                .activeAllocations(seed.completeDenseRecipeId).size
            assertEquals(1, sparseCompleteRows)
            assertEquals(TEN_YEAR_DAYS, denseCompleteRows.toLong())
            assertTrue(database.playtimeRecoveryDao().validateGraph(seed.completeSparseRecipeId))
            assertTrue(database.playtimeRecoveryDao().validateGraph(seed.completeDenseRecipeId))
            Log.i(
                TAG,
                "RECIPE_COMPLETION,${scale.label},sparseRows=$sparseCompleteRows,denseRows=$denseCompleteRows," +
                    "denseMinutes=${denseComplete.materializedMinutes}",
            )
            val marks = com.example.backlogium.domain.InMemoryProgressMarksStore(
                ProgressMarks(lastCelebratedLevel = 1, initialized = true),
            )
            val transition = ProgressTransitionCoordinator()
            val derivedWrites = DerivedStateWriteCoordinator()
            val updater = GamificationUpdater(
                sessionDao = database.sessionDao(),
                dailyProgressDao = database.dailyProgressDao(),
                playerProfileDao = database.playerProfileDao(),
                hltbDataDao = database.hltbDataDao(),
                achievementDao = database.achievementDao(),
                gameDao = database.gameDao(),
                hiddenGameDao = database.hiddenGameDao(),
                progressMarksStore = marks,
                transitionCoordinator = transition,
            )
            val committer = observationCommitter(database, time, metrics)
            val observedGames = database.gameDao().getAll().map {
                PlaytimeObservationCommitter.ObservedGame(
                    appId = it.appId,
                    name = it.name,
                    iconUrl = it.iconUrl,
                    playtimeForever = it.playtimeForever,
                    playtime2Weeks = it.playtime2Weeks,
                )
            }

            measureFive("unchanged-library-sync", scale, database, metrics) { sample ->
                metrics.measureScopedTransaction {
                    database.withTransaction {
                        val at = BASELINE_AT + sample * 1_000L
                        val result = committer.commit(
                            observed = observedGames,
                            observedPlayAt = at,
                            syncedAt = at,
                            expectedAccount = ACCOUNT,
                            ruleConfig = CONFIG,
                            ruleConfigVersion = CONFIG_VERSION,
                        )
                        database.playerProfileDao().updateSyncStatus(at, null)
                        assertTrue(result.playedDeltaByAppId.values.none { it > 0 })
                        observedGames.size.toLong()
                    }
                }
            }
            if (stopAfter == "sync") return

            val increaseApps = seed.growthApps
            measureFive("one-game-increase-25", scale, database, metrics) { sample ->
                val appId = increaseApps[sample]
                val before = seed.highWaterByApp.getValue(appId)
                metrics.measureScopedTransaction {
                    database.withTransaction {
                        val at = BASELINE_AT + (10 + sample) * 1_000L
                        val result = committer.commit(
                            observed = listOf(observedGame(database, appId, before + 25)),
                            observedPlayAt = at,
                            syncedAt = at,
                            expectedAccount = ACCOUNT,
                            ruleConfig = CONFIG,
                            ruleConfigVersion = CONFIG_VERSION,
                        )
                        database.playerProfileDao().updateSyncStatus(at, null)
                        assertEquals(25, result.playedDeltaByAppId.getValue(appId))
                        database.playtimeRecoveryDao().contributionsForGame(ACCOUNT, appId).size.toLong()
                    }
                }
            }
            if (stopAfter == "increase") return

            val settingsRepository = DataStoreSettingsRepository(settings)
            val recoveryDao = database.playtimeRecoveryDao()
            val reconciler = RecoveryPlacementReconcileUseCase(
                recoveryDao = recoveryDao,
                sessionDao = database.sessionDao(),
                dailyCredit = RecoveryDailyCreditCoordinator(database.dailyProgressDao()),
                settings = settingsRepository,
                gamificationUpdater = updater,
                time = time,
                syncCoordinator = com.example.backlogium.work.SteamSyncCoordinator(),
                derivedStateWrites = derivedWrites,
                transaction = CountingTransactionScope(RoomDatabaseTransactionScope(database), metrics),
                materializer = null,
            )
            val evidenceByApp = seed.reconcileSamples.associate { sample ->
                sample.appId to PlaytimeTimingEvidence(
                    evidenceId = "benchmark-local-${sample.appId}",
                    appId = sample.appId,
                    account = ACCOUNT,
                    source = PlaytimeEvidenceSource.LOCAL,
                    startAt = sample.windowStartAt + 10 * MINUTE_MS,
                    endAt = sample.windowStartAt + 45 * MINUTE_MS,
                )
            }
            measureFive("repeat-delayed-evidence-upgrade-and-replay", scale, database, metrics) { sample ->
                val item = seed.reconcileSamples[sample]
                val contribution = database.playtimeRecoveryDao().contribution(item.contributionId)!!
                val evidence = evidenceByApp.getValue(item.appId)
                val first = reconciler.reconcileContribution(contribution, listOf(evidence))
                val replay = reconciler.reconcileContribution(contribution, listOf(evidence))
                assertEquals(RecoveryReconcileOperation.UPGRADE, first.operation)
                assertEquals(RecoveryReconcileOperation.NO_CHANGE, replay.operation)
                first.affectedDates.size.toLong() + replay.affectedDates.size
            }
            if (stopAfter == "reconcile") return

            // Prefix one page in each recipe as the warm-up; measured calls resume from a durable cursor.
            seed.sparseRecipeIds.forEach { id -> materializer.materialize(id, NOW, pageSize = RECIPE_PAGE) }
            seed.denseRecipeIds.forEach { id -> materializer.materialize(id, NOW, pageSize = RECIPE_PAGE) }
            measureFive("sparse-10-year-recipe-resume", scale, database, metrics) { sample ->
                val slice = materializer.materialize(seed.sparseRecipeIds[sample], NOW, pageSize = RECIPE_PAGE)
                slice?.dates?.size?.toLong() ?: 0L
            }
            measureFive("dense-10-year-recipe-resume", scale, database, metrics) { sample ->
                val slice = materializer.materialize(seed.denseRecipeIds[sample], NOW, pageSize = RECIPE_PAGE)
                slice?.dates?.size?.toLong() ?: 0L
            }
            if (stopAfter == "recipes") return

            val mapper = exportMapper(database, settings, time, metrics)
            var exportFile: BackupFile? = null
            measureFive("v3-backup-export", scale, database, metrics) {
                val file = mapper.buildExport()
                exportFile = file
                fileRowCount(file)
            }
            val backup = requireNotNull(exportFile)
            val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
            val encodedBackup = File.createTempFile(
                "recovery-scale-${scale.label.lowercase()}-",
                ".json",
                context.cacheDir,
            )
            try {
                encodedBackup.outputStream().use { output ->
                    json.encodeToStream(BackupFile.serializer(), backup, output)
                }
                Log.i(TAG, "BACKUP_BYTES,${scale.label},${encodedBackup.length()}")
                assertTrue("exported v3 object must pass full-file preflight",
                    com.example.backlogium.data.backup.BackupValidator.validate(backup) is
                        com.example.backlogium.data.backup.BackupValidationResult.Valid)
                if (stopAfter == "export") return
                if (stopAfter != "window") benchmarkImports(context, scale, encodedBackup, json)
                if (stopAfter == "import") return

                val hidden = HiddenGamesRepository(
                    hiddenGameDao = database.hiddenGameDao(),
                    gameDao = database.gameDao(),
                    storeCacheDao = database.gameGenreCacheDao(),
                    time = time,
                )
                val sessionRepository = SessionRepository(
                    sessionDao = database.sessionDao(),
                    hiddenGamesRepository = hidden,
                    time = time,
                    playerProfileDao = database.playerProfileDao(),
                    database = database,
                ).apply { transactionDurationObserver = metrics::recordScopedTransactionDuration }
                val windowStart = TODAY.minusDays(29)
                val windowEndExclusive = TODAY.plusDays(1)
                measureFive("history-analytics-30-day-window", scale, database, metrics) {
                    val activity = sessionRepository.activityBetween(ZoneOffset.UTC, windowStart, windowEndExclusive).first()
                    val allocations = sessionRepository.datedAllocationsBetween(windowStart, TODAY).first()
                    val totals = sessionRepository.datedCreditedTotalsBetween(ZoneOffset.UTC, windowStart, windowEndExclusive)
                        .first()
                    activity.size.toLong() + allocations.size + totals.size
                }
                if (stopAfter == "window") return

                Log.i(TAG, "SCALE_DONE,${scale.label},games=${seed.games},contributions=${seed.contributions}")
            } finally {
                encodedBackup.delete()
            }
        } finally {
            metrics.enabled = false
            database.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun measureFive(
        workload: String,
        scale: Scale,
        database: BacklogiumDatabase,
        metrics: SqlMetrics,
        operation: suspend (sampleIndex: Int) -> Long,
    ) {
        // One unmeasured invocation warms Room/JIT and resolves lazy flows before five measured runs.
        measureOnce("$workload-warmup", scale, 0, database, metrics) { operation(0) }
        val records = (1..MEASURED_RUNS).map { index ->
            measureOnce(workload, scale, index, database, metrics) { operation(index) }
        }
        val elapsed = records.map { it.elapsedNanos }.sorted()
        val queryCounts = records.map { it.sqlQueries }.sorted()
        val readQueryCounts = records.map { it.readQueries }.sorted()
        val writeStatements = records.map { it.writeStatements }.sorted()
        val readRows = records.map { it.cursorRowsReturned }.sorted()
        val writeRows = records.map { it.rowsWritten }.sorted()
        val transactionNanos = records.map { it.transactionNanos }.sorted()
        val transactionCounts = records.map { it.transactionCount }.sorted()
        val scopedTransactionNanos = records.map { it.scopedTransactionNanos }.sorted()
        val scopedTransactionCounts = records.map { it.scopedTransactionCount }.sorted()
        val readOnlyTransactionCounts = records.map { it.readOnlyTransactionCount }.sorted()
        val readOnlyTransactionNanos = records.map { it.readOnlyTransactionNanos }.sorted()
        val pssDeltas = records.map { it.pssDeltaKb }.sorted()
        val heapDeltas = records.map { it.heapDeltaBytes }.sorted()
        val median = MEASURED_RUNS / 2
        Log.i(
            TAG,
            "SUMMARY,${scale.label},$workload,runs=$MEASURED_RUNS," +
                "elapsedMedianNs=${elapsed[median]},elapsedRangeNs=${elapsed.first()}..${elapsed.last()}," +
                "databaseQueriesMedian=${queryCounts[median]}," +
                "databaseQueriesRange=${queryCounts.first()}..${queryCounts.last()}," +
                "readQueriesMedian=${readQueryCounts[median]}," +
                "readQueriesRange=${readQueryCounts.first()}..${readQueryCounts.last()}," +
                "cursorRowsMedian=${readRows[median]},cursorRowsRange=${readRows.first()}..${readRows.last()}," +
                "writeStatementsMedian=${writeStatements[median]}," +
                "writeStatementsRange=${writeStatements.first()}..${writeStatements.last()}," +
                "writeRowsMedian=${writeRows[median]},writeRowsRange=${writeRows.first()}..${writeRows.last()}," +
                "supportSQLiteTransactionsMedian=${transactionCounts[median]}," +
                "supportSQLiteTransactionsRange=${transactionCounts.first()}..${transactionCounts.last()}," +
                "supportSQLiteTransactionNsMedian=${transactionNanos[median]}," +
                "supportSQLiteTransactionNsRange=${transactionNanos.first()}..${transactionNanos.last()}," +
                "scopedTransactionsMedian=${scopedTransactionCounts[median]}," +
                "scopedTransactionsRange=${scopedTransactionCounts.first()}..${scopedTransactionCounts.last()}," +
                "scopedTransactionNsMedian=${scopedTransactionNanos[median]}," +
                "scopedTransactionNsRange=${scopedTransactionNanos.first()}..${scopedTransactionNanos.last()}," +
                "supportSQLiteReadOnlyTransactionsMedian=${readOnlyTransactionCounts[median]}," +
                "supportSQLiteReadOnlyTransactionsRange=${readOnlyTransactionCounts.first()}..${readOnlyTransactionCounts.last()}," +
                "supportSQLiteReadOnlyTransactionNsMedian=${readOnlyTransactionNanos[median]}," +
                "supportSQLiteReadOnlyTransactionNsRange=${readOnlyTransactionNanos.first()}..${readOnlyTransactionNanos.last()}," +
                "pssDeltaKbMedian=${pssDeltas[median]},pssDeltaKbRange=${pssDeltas.first()}..${pssDeltas.last()}," +
                "heapDeltaBytesMedian=${heapDeltas[median]}," +
                "heapDeltaBytesRange=${heapDeltas.first()}..${heapDeltas.last()}," +
                "sqlFingerprints=${records[0].sqlStatementCounts}",
        )
    }

    private suspend fun measureOnce(
        workload: String,
        scale: Scale,
        sample: Int,
        database: BacklogiumDatabase,
        metrics: SqlMetrics,
        operation: suspend () -> Long,
    ): MetricRecord {
        metrics.enabled = false
        val pssBefore = pssKb()
        val heapBefore = usedHeapBytes()
        metrics.reset()
        metrics.enabled = true
        val start = System.nanoTime()
        var elapsed = 0L
        val resultRows = try {
            operation().also { elapsed = System.nanoTime() - start }
        } finally {
            metrics.enabled = false
        }
        val pssAfter = pssKb()
        val heapAfter = usedHeapBytes()
        val snapshot = metrics.snapshot()
        val record = MetricRecord(
            elapsedNanos = elapsed,
            sqlQueries = snapshot.queryCount,
            cursorRowsReturned = snapshot.rowsReturned,
            readQueries = snapshot.readQueries,
            rowsReportedAtApi = resultRows,
            writeStatements = snapshot.writeStatements,
            rowsWritten = snapshot.rowsWritten,
            transactionCount = snapshot.transactionCount,
            transactionNanos = snapshot.transactionNanos,
            scopedTransactionCount = snapshot.scopedTransactionCount,
            scopedTransactionNanos = snapshot.scopedTransactionNanos,
            readOnlyTransactionCount = snapshot.readOnlyTransactionCount,
            readOnlyTransactionNanos = snapshot.readOnlyTransactionNanos,
            pssDeltaKb = pssAfter - pssBefore,
            heapDeltaBytes = heapAfter - heapBefore,
            sqlStatementCounts = snapshot.statementCounts,
        )
        Log.i(
            TAG,
            "SAMPLE,${scale.label},$workload,index=$sample,elapsedNs=${record.elapsedNanos}," +
                "databaseQueries=${record.sqlQueries},cursorRows=${record.cursorRowsReturned}," +
                "readQueries=${record.readQueries}," +
                "apiRows=${record.rowsReportedAtApi},writeStatements=${record.writeStatements}," +
                "writeRows=${record.rowsWritten},supportSQLiteTransactions=${record.transactionCount}," +
                "supportSQLiteTransactionNs=${record.transactionNanos}," +
                "scopedTransactions=${record.scopedTransactionCount}," +
                "scopedTransactionNs=${record.scopedTransactionNanos}," +
                "supportSQLiteReadOnlyTransactions=${record.readOnlyTransactionCount}," +
                "supportSQLiteReadOnlyTransactionNs=${record.readOnlyTransactionNanos}," +
                "pssDeltaKb=${record.pssDeltaKb}," +
                "heapDeltaBytes=${record.heapDeltaBytes},sqlFingerprints=${record.sqlStatementCounts}",
        )
        return record
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun benchmarkImports(
        context: android.content.Context,
        scale: Scale,
        encodedBackup: File,
        json: Json,
    ) {
        if (encodedBackup.length() > BackupRepository.MAX_IMPORT_BYTES) {
            Log.i(
                TAG,
                "IMPORT_SKIPPED,${scale.label},bytes=${encodedBackup.length()}," +
                    "maxBytes=${BackupRepository.MAX_IMPORT_BYTES},reason=production_byte_limit",
            )
            return
        }
        // Warm the import path once, then use a fresh empty receiving Room database for each sample
        // so every measured import performs the same full graph write rather than an idempotent replay.
        val runId = System.nanoTime()
        val warm = newTarget(context, scale, 0, runId)
        try {
            val parsed = encodedBackup.inputStream().use {
                json.decodeFromStream(BackupFile.serializer(), it)
            }
            assertTrue(
                com.example.backlogium.data.backup.BackupValidator.validate(parsed) is
                    com.example.backlogium.data.backup.BackupValidationResult.Valid,
            )
            warm.engine.merge(parsed, CONFIG, CONFIG_VERSION)
        } finally {
            warm.metrics.enabled = false
            warm.database.close()
            context.deleteDatabase(warm.databaseName)
        }

        val records = (1..MEASURED_RUNS).map { sample ->
            val target = newTarget(context, scale, sample, runId)
            try {
                measureOnce("v3-backup-import", scale, sample, target.database, target.metrics) {
                    val parsed = encodedBackup.inputStream().use {
                        json.decodeFromStream(BackupFile.serializer(), it)
                    }
                    val result = com.example.backlogium.data.backup.BackupValidator.validate(parsed)
                    assertTrue(result is com.example.backlogium.data.backup.BackupValidationResult.Valid)
                    target.engine.merge(parsed, CONFIG, CONFIG_VERSION)
                    fileRowCount(parsed)
                }
            } finally {
                target.metrics.enabled = false
                target.database.close()
                context.deleteDatabase(target.databaseName)
            }
        }
        val elapsed = records.map { it.elapsedNanos }.sorted()
        val queries = records.map { it.sqlQueries }.sorted()
        val readQueries = records.map { it.readQueries }.sorted()
        val writeStatements = records.map { it.writeStatements }.sorted()
        val rows = records.map { it.cursorRowsReturned }.sorted()
        val writes = records.map { it.rowsWritten }.sorted()
        val txNanos = records.map { it.transactionNanos }.sorted()
        val transactions = records.map { it.transactionCount }.sorted()
        val scopedTxNanos = records.map { it.scopedTransactionNanos }.sorted()
        val scopedTransactions = records.map { it.scopedTransactionCount }.sorted()
        val readOnlyTransactions = records.map { it.readOnlyTransactionCount }.sorted()
        val readOnlyTxNanos = records.map { it.readOnlyTransactionNanos }.sorted()
        val pssDeltas = records.map { it.pssDeltaKb }.sorted()
        val heapDeltas = records.map { it.heapDeltaBytes }.sorted()
        val median = MEASURED_RUNS / 2
        Log.i(
            TAG,
            "SUMMARY,${scale.label},v3-backup-import,runs=$MEASURED_RUNS," +
                "elapsedMedianNs=${elapsed[median]},elapsedRangeNs=${elapsed.first()}..${elapsed.last()}," +
                "databaseQueriesMedian=${queries[median]},databaseQueriesRange=${queries.first()}..${queries.last()}," +
                "readQueriesMedian=${readQueries[median]},readQueriesRange=${readQueries.first()}..${readQueries.last()}," +
                "cursorRowsMedian=${rows[median]},cursorRowsRange=${rows.first()}..${rows.last()}," +
                "writeStatementsMedian=${writeStatements[median]}," +
                "writeStatementsRange=${writeStatements.first()}..${writeStatements.last()}," +
                "writeRowsMedian=${writes[median]},writeRowsRange=${writes.first()}..${writes.last()}," +
                "supportSQLiteTransactionsMedian=${transactions[median]}," +
                "supportSQLiteTransactionsRange=${transactions.first()}..${transactions.last()}," +
                "supportSQLiteTransactionNsMedian=${txNanos[median]}," +
                "supportSQLiteTransactionNsRange=${txNanos.first()}..${txNanos.last()}," +
                "scopedTransactionsMedian=${scopedTransactions[median]}," +
                "scopedTransactionsRange=${scopedTransactions.first()}..${scopedTransactions.last()}," +
                "scopedTransactionNsMedian=${scopedTxNanos[median]}," +
                "scopedTransactionNsRange=${scopedTxNanos.first()}..${scopedTxNanos.last()}," +
                "supportSQLiteReadOnlyTransactionsMedian=${readOnlyTransactions[median]}," +
                "supportSQLiteReadOnlyTransactionsRange=${readOnlyTransactions.first()}..${readOnlyTransactions.last()}," +
                "supportSQLiteReadOnlyTransactionNsMedian=${readOnlyTxNanos[median]}," +
                "supportSQLiteReadOnlyTransactionNsRange=${readOnlyTxNanos.first()}..${readOnlyTxNanos.last()}," +
                "pssDeltaKbMedian=${pssDeltas[median]},pssDeltaKbRange=${pssDeltas.first()}..${pssDeltas.last()}," +
                "heapDeltaBytesMedian=${heapDeltas[median]}," +
                "heapDeltaBytesRange=${heapDeltas.first()}..${heapDeltas.last()}," +
                "sqlFingerprints=${records[0].sqlStatementCounts}",
        )
    }

    private fun newTarget(
        context: android.content.Context,
        scale: Scale,
        sample: Int,
        runId: Long,
    ): ImportTarget {
        val metrics = SqlMetrics()
        metrics.enabled = false
        val databaseName = "ri${scale.games}${scale.contributions}${runId}_$sample.db"
        val database = Room.databaseBuilder(
            context,
            BacklogiumDatabase::class.java,
            databaseName,
        ).openHelperFactory(CountingOpenHelperFactory(metrics)).allowMainThreadQueries().build()
        runBlocking {
            database.playerProfileDao().upsert(PlayerProfile(steamId = ACCOUNT, level = 1))
        }
        val marks = com.example.backlogium.domain.InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 1, initialized = true),
        )
        val transition = ProgressTransitionCoordinator()
        val derived = DerivedStateWriteCoordinator()
        val updater = GamificationUpdater(
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            hltbDataDao = database.hltbDataDao(),
            achievementDao = database.achievementDao(),
            gameDao = database.gameDao(),
            hiddenGameDao = database.hiddenGameDao(),
            progressMarksStore = marks,
            transitionCoordinator = transition,
        )
        val recoveryDao = database.playtimeRecoveryDao()
        val materializer = UnknownTimeMaterializer(
            database,
            recoveryDao,
            RecoveryDailyCreditCoordinator(database.dailyProgressDao()),
        ).apply { transactionDurationObserver = metrics::recordScopedTransactionDuration }
        val engine = BackupMergeEngine(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            hltbDataDao = database.hltbDataDao(),
            achievementDao = database.achievementDao(),
            playerProfileDao = database.playerProfileDao(),
            collectionDao = database.collectionDao(),
            excludedSharedGameDao = database.excludedSharedGameDao(),
            hiddenGameDao = database.hiddenGameDao(),
            gamificationUpdater = updater,
            time = BenchmarkTime,
            derivedStateWrites = derived,
            transaction = CountingTransactionScope(RoomDatabaseTransactionScope(database), metrics),
            recoveryStore = DaoRecoveryBackupStore(recoveryDao, database.sessionDao()),
            credentials = BenchmarkCredentials,
            syncCoordinator = com.example.backlogium.work.SteamSyncCoordinator(),
            unknownTimeMaterializer = materializer,
        )
        return ImportTarget(databaseName, database, metrics, engine)
    }

    private fun observationCommitter(
        database: BacklogiumDatabase,
        time: TimeProvider,
        metrics: SqlMetrics,
    ) = PlaytimeObservationCommitter(
        gameDao = database.gameDao(),
        sessionDao = database.sessionDao(),
        dailyProgressDao = database.dailyProgressDao(),
        profileDao = database.playerProfileDao(),
        hiddenGameDao = database.hiddenGameDao(),
        differ = SessionDiffer(),
        time = time,
        sessionActionWriter = SessionActionWriter(
            database.sessionDao(), database.dailyProgressDao(), database.hiddenGameDao(), time,
        ),
        recoveryAdapter = PlaytimeRecoveryCommitAdapter(database.playtimeRecoveryDao(), database.sessionDao()),
    )

    private fun exportMapper(
        database: BacklogiumDatabase,
        settings: SettingsDataStore,
        time: TimeProvider,
        metrics: SqlMetrics,
    ) = BackupExportMapper(
        gameDao = database.gameDao(),
        achievementDao = database.achievementDao(),
        sessionDao = database.sessionDao(),
        dailyProgressDao = database.dailyProgressDao(),
        hltbDataDao = database.hltbDataDao(),
        playerProfileDao = database.playerProfileDao(),
        collectionDao = database.collectionDao(),
        hiddenGameDao = database.hiddenGameDao(),
        excludedSharedGameDao = database.excludedSharedGameDao(),
        recoveryStore = DaoRecoveryBackupStore(database.playtimeRecoveryDao(), database.sessionDao()),
        settings = settings,
        credentials = BenchmarkCredentials,
        time = time,
        transaction = CountingTransactionScope(RoomDatabaseTransactionScope(database), metrics),
    )

    private suspend fun seedDatabase(database: BacklogiumDatabase, scale: Scale): ScaleSeed {
        require(scale.games > SPECIAL_APP_COUNT + scale.games / QUIET_GAME_DIVISOR) {
            "tier must leave room for special, quiet, and active games"
        }
        require(scale.contributions > SPECIAL_CONTRIBUTION_COUNT)
        val highWater = LongArray(scale.games) { INITIAL_HIGH_WATER }
        var authorized = 0L
        forEachPlan(scale) { plan ->
            highWater[plan.gameIndex] = plan.toMinutes
            authorized += plan.toMinutes - plan.fromMinutes
        }

        val games = (0 until scale.games).map { index ->
            val backfill = if (index % 25 == 0) BACKFILL_MINUTES else 0
            Game(
                appId = APP_BASE + index,
                name = "Seeded benchmark game $index",
                iconUrl = "",
                playtimeForever = highWater[index].toInt(),
                playtime2Weeks = 0,
                lastPlaytime = highWater[index].toInt(),
                isGoal = index % 5 == 0,
                lastSyncedAt = BASELINE_AT,
                backfillMinutes = backfill,
            )
        }
        database.gameDao().upsertAll(games)
        database.playerProfileDao().upsert(
            PlayerProfile(
                steamId = ACCOUNT,
                lastSyncAt = BASELINE_AT,
                playtimeBackfilled = true,
                level = 1,
            ),
        )

        val stats = SeedRowStats()
        val daily = mutableMapOf<String, LongArray>()
        val sql = database.openHelper.writableDatabase
        database.withTransaction {
            val contributionInsert = sql.compileStatement(
                "INSERT INTO playtime_contributions " +
                    "(contributionId,account,appId,baselineEpoch,fromMinutes,toMinutes,windowStartAt," +
                    "windowEndAt,authorizedMinutes,revision,provenance,zone,observedZone,createdAt) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            )
            val stateInsert = sql.compileStatement(
                "INSERT INTO game_recovery_state " +
                    "(account,appId,baselineEpoch,highWaterMinutes,lastObservationAt,lastObservationDate," +
                    "lastObservationZone,clockState,updatedAt) VALUES (?,?,?,?,?,?,?,?,?)",
            )
            val allocationInsert = sql.compileStatement(
                "INSERT INTO recovery_unknown_allocations " +
                    "(placementId,contributionId,account,appId,revision,date,minutes,method,zone," +
                    "windowStartAt,windowEndAt,pendingDate,hiddenAtCredit,goalAtCredit,superseded," +
                    "protected,createdAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            )
            val timedInsert = sql.compileStatement(
                "INSERT INTO recovery_timed_placements " +
                    "(placementId,contributionId,account,appId,revision,sessionId,minutes,creditedDate," +
                    "zone,evidenceIdentity,evidenceQuality,hiddenAtCredit,goalAtCredit,superseded," +
                    "protected,createdAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            )
            val recipeInsert = sql.compileStatement(
                "INSERT INTO recovery_allocation_recipes " +
                    "(contributionId,account,appId,revision,method,quotient,remainderCount,dateStart," +
                    "dateEnd,eligibleCount,zone,windowStartAt,windowEndAt,excludedDatesCsv,pending," +
                    "materializedMinutes,materializedThroughDate,hiddenAtCredit,goalAtCredit,updatedAt) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            )
            val sessionInsert = sql.compileStatement(
                "INSERT INTO sessions (appId,startAt,endAt,minutes,open,recoveredSharedPlay," +
                    "timingInformedSteamPlay,openAppId) VALUES (?,?,?,?,?,?,?,?)",
            )
            try {
                forEachPlan(scale) { plan ->
                    val appId = APP_BASE + plan.gameIndex
                    val contributionId = PlaytimeRecoveryIdentity.contributionId(
                        ACCOUNT, appId, 1L, plan.fromMinutes, plan.toMinutes,
                    )
                    val revision = if (plan.kind == SeedKind.SUPERSEDED) 2L else 1L
                    insertContribution(
                        contributionInsert,
                        contributionId = contributionId,
                        appId = appId,
                        plan = plan,
                        revision = revision,
                    )
                    stats.contributions++
                    when (plan.kind) {
                        SeedKind.SPARSE_RECIPE,
                        SeedKind.DENSE_RECIPE,
                        SeedKind.SPARSE_COMPLETE_RECIPE,
                        SeedKind.DENSE_COMPLETE_RECIPE -> {
                            insertRecipe(recipeInsert, contributionId, appId, plan, revision)
                            stats.recipes++
                            if (plan.kind == SeedKind.SPARSE_RECIPE) stats.sparseRecipeIds += contributionId
                            else if (plan.kind == SeedKind.DENSE_RECIPE) stats.denseRecipeIds += contributionId
                            if (plan.kind == SeedKind.SPARSE_COMPLETE_RECIPE) {
                                stats.completeSparseRecipeId = contributionId
                            } else if (plan.kind == SeedKind.DENSE_COMPLETE_RECIPE) {
                                stats.completeDenseRecipeId = contributionId
                            }
                            stats.validationIds["${plan.kind}"] = contributionId
                        }
                        SeedKind.RECONCILE_SAMPLE -> {
                            insertAllocation(
                                allocationInsert,
                                placementId = "$contributionId:$revision:u:${plan.day}",
                                contributionId = contributionId,
                                appId = appId,
                                revision = revision,
                                date = plan.day,
                                minutes = plan.toMinutes - plan.fromMinutes,
                                method = RecoveryAllocationMethods.SINGLE_DAY,
                                zone = "UTC",
                                windowStartAt = plan.windowStartAt,
                                windowEndAt = plan.windowEndAt,
                                pending = false,
                                goal = plan.goal,
                                superseded = false,
                                createdAt = plan.windowEndAt,
                            )
                            addDaily(daily, plan.day, plan.toMinutes - plan.fromMinutes, plan.goal)
                            stats.allocations++
                            stats.reconcileSamples += ReconcileSample(appId, contributionId, plan.windowStartAt!!)
                            stats.validationIds["${plan.kind}"] = contributionId
                        }
                        SeedKind.PENDING -> {
                            insertAllocation(
                                allocationInsert,
                                placementId = "$contributionId:$revision:u:pending",
                                contributionId = contributionId,
                                appId = appId,
                                revision = revision,
                                date = null,
                                minutes = plan.toMinutes - plan.fromMinutes,
                                method = RecoveryAllocationMethods.PENDING_DATE_UNKNOWN,
                                zone = null,
                                windowStartAt = null,
                                windowEndAt = plan.windowEndAt,
                                pending = true,
                                goal = plan.goal,
                                superseded = false,
                                createdAt = plan.windowEndAt,
                            )
                            stats.allocations++
                            stats.pendingAllocations++
                            stats.validationIds.putIfAbsent("${plan.kind}", contributionId)
                        }
                        SeedKind.TIMED -> {
                            val startAt = plan.windowStartAt!! + HOUR_MS + (plan.perAppOrdinal % 40) * TEN_MINUTES_MS
                            val endAt = startAt + TIMED_MINUTES * MINUTE_MS
                            val sessionId = insertSession(sessionInsert, appId, startAt, endAt, SESSION_MINUTES)
                            insertTimed(
                                timedInsert,
                                placementId = "$contributionId:1:bench:${plan.ordinal}",
                                contributionId = contributionId,
                                appId = appId,
                                sessionId = sessionId,
                                minutes = TIMED_MINUTES.toLong(),
                                date = plan.day,
                                evidenceIdentity = "benchmark|${plan.fromMinutes}|${plan.toMinutes}|$startAt|$endAt",
                                goal = plan.goal,
                                createdAt = plan.windowEndAt,
                            )
                            insertAllocation(
                                allocationInsert,
                                placementId = "$contributionId:1:u:${plan.day}",
                                contributionId = contributionId,
                                appId = appId,
                                revision = 1L,
                                date = plan.day,
                                minutes = plan.toMinutes - plan.fromMinutes - TIMED_MINUTES,
                                method = RecoveryAllocationMethods.SINGLE_DAY,
                                zone = "UTC",
                                windowStartAt = plan.windowStartAt,
                                windowEndAt = plan.windowEndAt,
                                pending = false,
                                goal = plan.goal,
                                superseded = false,
                                createdAt = plan.windowEndAt,
                            )
                            addDaily(daily, plan.day, SESSION_MINUTES + plan.toMinutes - plan.fromMinutes - TIMED_MINUTES,
                                plan.goal)
                            stats.timedPlacements++
                            stats.sessions++
                            stats.allocations++
                            stats.validationIds.putIfAbsent("${plan.kind}", contributionId)
                        }
                        SeedKind.SUPERSEDED -> {
                            insertAllocation(
                                allocationInsert,
                                placementId = "$contributionId:1:u:superseded",
                                contributionId = contributionId,
                                appId = appId,
                                revision = 1L,
                                date = plan.day,
                                minutes = plan.toMinutes - plan.fromMinutes,
                                method = RecoveryAllocationMethods.SINGLE_DAY,
                                zone = "UTC",
                                windowStartAt = plan.windowStartAt,
                                windowEndAt = plan.windowEndAt,
                                pending = false,
                                goal = plan.goal,
                                superseded = true,
                                createdAt = plan.windowEndAt,
                            )
                            insertAllocation(
                                allocationInsert,
                                placementId = "$contributionId:$revision:u:${plan.day}",
                                contributionId = contributionId,
                                appId = appId,
                                revision = revision,
                                date = plan.day,
                                minutes = plan.toMinutes - plan.fromMinutes,
                                method = RecoveryAllocationMethods.SINGLE_DAY,
                                zone = "UTC",
                                windowStartAt = plan.windowStartAt,
                                windowEndAt = plan.windowEndAt,
                                pending = false,
                                goal = plan.goal,
                                superseded = false,
                                createdAt = plan.windowEndAt,
                            )
                            addDaily(daily, plan.day, plan.toMinutes - plan.fromMinutes, plan.goal)
                            stats.allocations += 2
                            stats.supersededAllocations++
                            stats.validationIds.putIfAbsent("${plan.kind}", contributionId)
                        }
                        SeedKind.ESTIMATE -> {
                            insertAllocation(
                                allocationInsert,
                                placementId = "$contributionId:$revision:u:${plan.day}",
                                contributionId = contributionId,
                                appId = appId,
                                revision = revision,
                                date = plan.day,
                                minutes = plan.toMinutes - plan.fromMinutes,
                                method = RecoveryAllocationMethods.SINGLE_DAY,
                                zone = "UTC",
                                windowStartAt = plan.windowStartAt,
                                windowEndAt = plan.windowEndAt,
                                pending = false,
                                goal = plan.goal,
                                superseded = false,
                                createdAt = plan.windowEndAt,
                            )
                            addDaily(daily, plan.day, plan.toMinutes - plan.fromMinutes, plan.goal)
                            stats.allocations++
                            stats.validationIds.putIfAbsent("${plan.kind}", contributionId)
                        }
                    }
                }

                highWater.forEachIndexed { index, absolute ->
                    insertState(stateInsert, APP_BASE + index, absolute)
                }
            } finally {
                listOf(contributionInsert, stateInsert, allocationInsert, timedInsert, recipeInsert, sessionInsert)
                    .forEach { it.close() }
            }
        }

        for (daysAgo in 0 until WINDOW_DAYS) {
            val date = TODAY.minusDays(daysAgo.toLong()).toString()
            val totals = daily[date] ?: longArrayOf(0L, 0L)
            database.dailyProgressDao().upsert(
                DailyProgress(
                    date = date,
                    minutesPlayed = totals[0].toInt(),
                    goalMinutesPlayed = totals[1].toInt(),
                    questMet = false,
                ),
            )
        }
        stats.games = scale.games
        stats.quietGameCount = scale.games / QUIET_GAME_DIVISOR
        stats.authorizedMinutes = authorized
        stats.growthApps = (scale.games - GROWTH_SAMPLES until scale.games)
            .map { APP_BASE + it }
        return stats.toSeedInfo(highWater)
    }

    private fun forEachPlan(scale: Scale, action: (ContributionPlan) -> Unit) {
        val nextFrom = LongArray(scale.games) { INITIAL_HIGH_WATER }
        fun emit(ordinal: Int, gameIndex: Int, kind: SeedKind, width: Long, day: String,
                 windowStartAt: Long?, windowEndAt: Long) {
            val from = nextFrom[gameIndex]
            val to = from + width
            nextFrom[gameIndex] = to
            action(
                ContributionPlan(
                    ordinal = ordinal,
                    gameIndex = gameIndex,
                    perAppOrdinal = ((from - INITIAL_HIGH_WATER) / STANDARD_WIDTH).toInt(),
                    kind = kind,
                    fromMinutes = from,
                    toMinutes = to,
                    day = day,
                    windowStartAt = windowStartAt,
                    windowEndAt = windowEndAt,
                    goal = gameIndex % 5 == 0,
                ),
            )
        }

        for (index in 0 until RECIPE_SAMPLES * 2) {
            val sparse = index % 2 == 0
            emit(
                ordinal = index,
                gameIndex = index,
                kind = if (sparse) SeedKind.SPARSE_RECIPE else SeedKind.DENSE_RECIPE,
                width = if (sparse) SPARSE_RECIPE_MINUTES else DENSE_RECIPE_MINUTES,
                day = TODAY.toString(),
                windowStartAt = TEN_YEAR_START,
                windowEndAt = TEN_YEAR_END,
            )
        }
        emit(
            ordinal = RECIPE_SAMPLES * 2,
            gameIndex = RECIPE_SAMPLES * 2,
            kind = SeedKind.SPARSE_COMPLETE_RECIPE,
            width = SPARSE_RECIPE_MINUTES,
            day = TODAY.toString(),
            windowStartAt = TEN_YEAR_START,
            windowEndAt = TEN_YEAR_END,
        )
        emit(
            ordinal = RECIPE_SAMPLES * 2 + 1,
            gameIndex = RECIPE_SAMPLES * 2 + 1,
            kind = SeedKind.DENSE_COMPLETE_RECIPE,
            width = DENSE_RECIPE_MINUTES,
            day = TODAY.toString(),
            windowStartAt = TEN_YEAR_START,
            windowEndAt = TEN_YEAR_END,
        )
        for (sample in 0 until RECONCILE_SAMPLES) {
            val index = RECIPE_SAMPLES * 2 + COMPLETE_RECIPE_COUNT + sample
            val day = TODAY.minusDays(RECONCILE_DAYS_AGO.toLong())
            val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            emit(
                ordinal = index,
                gameIndex = RECIPE_SAMPLES * 2 + COMPLETE_RECIPE_COUNT + sample,
                kind = SeedKind.RECONCILE_SAMPLE,
                width = RECONCILE_SAMPLE_MINUTES,
                day = day.toString(),
                windowStartAt = start,
                windowEndAt = start + DAY_MS - 1L,
            )
        }
        val quietGameCount = scale.games / QUIET_GAME_DIVISOR
        val genericGames = scale.games - SPECIAL_APP_COUNT - quietGameCount
        val genericCount = scale.contributions - SPECIAL_CONTRIBUTION_COUNT
        for (genericIndex in 0 until genericCount) {
            val index = SPECIAL_CONTRIBUTION_COUNT + genericIndex
            val gameIndex = SPECIAL_APP_COUNT + quietGameCount + genericIndex % genericGames
            val day = TODAY.minusDays((genericIndex % WINDOW_DAYS).toLong())
            val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val kind = when (genericIndex % MIX_PERIOD) {
                0 -> SeedKind.PENDING
                in 1..TIMED_PERIOD_COUNT -> SeedKind.TIMED
                SUPERSEDED_SLOT -> SeedKind.SUPERSEDED
                else -> SeedKind.ESTIMATE
            }
            emit(
                ordinal = index,
                gameIndex = gameIndex,
                kind = kind,
                width = STANDARD_WIDTH,
                day = day.toString(),
                windowStartAt = if (kind == SeedKind.PENDING) null else dayStart + TEN_HOUR_MS,
                windowEndAt = dayStart + DAY_MS - 1L,
            )
        }
    }

    private fun openDatabase(context: android.content.Context, name: String, metrics: SqlMetrics) =
        Room.databaseBuilder(context, BacklogiumDatabase::class.java, name)
            .openHelperFactory(CountingOpenHelperFactory(metrics))
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries()
            .build()

    private suspend fun observedGame(database: BacklogiumDatabase, appId: Long, playtime: Int) =
        PlaytimeObservationCommitter.ObservedGame(
            appId = appId,
            name = database.gameDao().getById(appId)?.name.orEmpty(),
            iconUrl = "",
            playtimeForever = playtime,
            playtime2Weeks = 0,
        )

    private suspend fun drainRecipe(materializer: UnknownTimeMaterializer, contributionId: String) {
        repeat(TEN_YEAR_PAGE_COUNT + 1) {
            val slice = materializer.materialize(contributionId, NOW, pageSize = RECIPE_PAGE)
                ?: error("missing/ineligible benchmark recipe $contributionId")
            if (slice.complete) return
        }
        error("benchmark recipe did not drain within $TEN_YEAR_PAGE_COUNT pages: $contributionId")
    }

    private fun insertContribution(
        statement: SupportSQLiteStatement,
        contributionId: String,
        appId: Long,
        plan: ContributionPlan,
        revision: Long,
    ) {
        statement.clearBindings()
        statement.bindString(1, contributionId)
        statement.bindString(2, ACCOUNT)
        statement.bindLong(3, appId)
        statement.bindLong(4, 1L)
        statement.bindLong(5, plan.fromMinutes)
        statement.bindLong(6, plan.toMinutes)
        bindNullableLong(statement, 7, plan.windowStartAt)
        statement.bindLong(8, plan.windowEndAt)
        statement.bindLong(9, plan.toMinutes - plan.fromMinutes)
        statement.bindLong(10, revision)
        statement.bindString(11, RecoveryProvenance.RETROACTIVE_PLAY)
        if (plan.kind == SeedKind.PENDING) statement.bindNull(12) else statement.bindString(12, "UTC")
        if (plan.kind == SeedKind.PENDING) statement.bindNull(13) else statement.bindString(13, "UTC")
        statement.bindLong(14, plan.windowEndAt)
        statement.executeInsert()
    }

    private fun insertState(statement: SupportSQLiteStatement, appId: Long, highWater: Long) {
        statement.clearBindings()
        statement.bindString(1, ACCOUNT)
        statement.bindLong(2, appId)
        statement.bindLong(3, 1L)
        statement.bindLong(4, highWater)
        statement.bindLong(5, BASELINE_AT)
        statement.bindString(6, TODAY.toString())
        statement.bindString(7, "UTC")
        statement.bindString(8, "VALID")
        statement.bindLong(9, BASELINE_AT)
        statement.executeInsert()
    }

    private fun insertAllocation(
        statement: SupportSQLiteStatement,
        placementId: String,
        contributionId: String,
        appId: Long,
        revision: Long,
        date: String?,
        minutes: Long,
        method: String,
        zone: String?,
        windowStartAt: Long?,
        windowEndAt: Long?,
        pending: Boolean,
        goal: Boolean = false,
        superseded: Boolean = false,
        createdAt: Long = NOW,
    ) {
        statement.clearBindings()
        statement.bindString(1, placementId)
        statement.bindString(2, contributionId)
        statement.bindString(3, ACCOUNT)
        statement.bindLong(4, appId)
        statement.bindLong(5, revision)
        if (date == null) statement.bindNull(6) else statement.bindString(6, date)
        statement.bindLong(7, minutes)
        statement.bindString(8, method)
        if (zone == null) statement.bindNull(9) else statement.bindString(9, zone)
        bindNullableLong(statement, 10, windowStartAt)
        bindNullableLong(statement, 11, windowEndAt)
        statement.bindLong(12, if (pending) 1L else 0L)
        statement.bindLong(13, 0L)
        statement.bindLong(14, if (goal) 1L else 0L)
        statement.bindLong(15, if (superseded) 1L else 0L)
        statement.bindLong(16, 0L)
        statement.bindLong(17, createdAt)
        statement.executeInsert()
    }

    private fun insertTimed(
        statement: SupportSQLiteStatement,
        placementId: String,
        contributionId: String,
        appId: Long,
        sessionId: Long,
        minutes: Long,
        date: String,
        evidenceIdentity: String,
        goal: Boolean,
        createdAt: Long,
    ) {
        statement.clearBindings()
        statement.bindString(1, placementId)
        statement.bindString(2, contributionId)
        statement.bindString(3, ACCOUNT)
        statement.bindLong(4, appId)
        statement.bindLong(5, 1L)
        statement.bindLong(6, sessionId)
        statement.bindLong(7, minutes)
        statement.bindString(8, date)
        statement.bindString(9, "UTC")
        statement.bindString(10, evidenceIdentity)
        statement.bindString(11, RecoveryEvidenceQuality.LOCAL)
        statement.bindLong(12, 0L)
        statement.bindLong(13, if (goal) 1L else 0L)
        statement.bindLong(14, 0L)
        statement.bindLong(15, 0L)
        statement.bindLong(16, createdAt)
        statement.executeInsert()
    }

    private fun insertRecipe(
        statement: SupportSQLiteStatement,
        contributionId: String,
        appId: Long,
        plan: ContributionPlan,
        revision: Long,
    ) {
        val sparse = plan.kind == SeedKind.SPARSE_RECIPE || plan.kind == SeedKind.SPARSE_COMPLETE_RECIPE
        statement.clearBindings()
        statement.bindString(1, contributionId)
        statement.bindString(2, ACCOUNT)
        statement.bindLong(3, appId)
        statement.bindLong(4, revision)
        statement.bindString(5, RecoveryAllocationMethods.MULTI_DAY_ESTIMATE)
        statement.bindLong(6, if (sparse) 0L else RECIPE_DENSE_QUOTIENT)
        statement.bindLong(7, if (sparse) 1L else 0L)
        statement.bindString(8, TEN_YEAR_START_DATE.toString())
        statement.bindString(9, TEN_YEAR_END_DATE.toString())
        statement.bindLong(10, TEN_YEAR_DAYS.toLong())
        statement.bindString(11, "UTC")
        statement.bindLong(12, TEN_YEAR_START)
        statement.bindLong(13, TEN_YEAR_END)
        statement.bindNull(14)
        statement.bindLong(15, 0L)
        statement.bindLong(16, 0L)
        statement.bindNull(17)
        statement.bindLong(18, 0L)
        statement.bindLong(19, if (plan.goal) 1L else 0L)
        statement.bindLong(20, TEN_YEAR_END)
        statement.executeInsert()
    }

    private fun insertSession(
        statement: SupportSQLiteStatement,
        appId: Long,
        startAt: Long,
        endAt: Long,
        minutes: Int,
    ): Long {
        statement.clearBindings()
        statement.bindLong(1, appId)
        statement.bindLong(2, startAt)
        statement.bindLong(3, endAt)
        statement.bindLong(4, minutes.toLong())
        statement.bindLong(5, 0L)
        statement.bindNull(6)
        statement.bindNull(7)
        statement.bindNull(8)
        return statement.executeInsert()
    }

    private fun bindNullableLong(statement: SupportSQLiteStatement, index: Int, value: Long?) {
        if (value == null) statement.bindNull(index) else statement.bindLong(index, value)
    }

    private fun addDaily(daily: MutableMap<String, LongArray>, date: String, minutes: Long, goal: Boolean) {
        val total = daily.getOrPut(date) { longArrayOf(0L, 0L) }
        total[0] += minutes
        if (goal) total[1] += minutes
    }

    private suspend fun verifySeed(database: BacklogiumDatabase, scale: Scale, seed: ScaleSeed) {
        val sql = database.openHelper.readableDatabase
        val gameCount = scalarLong(sql, "SELECT COUNT(*) FROM games")
        val stateCount = scalarLong(sql, "SELECT COUNT(*) FROM game_recovery_state WHERE account='$ACCOUNT'")
        val contributionCount = scalarLong(sql, "SELECT COUNT(*) FROM playtime_contributions WHERE account='$ACCOUNT'")
        val quietGames = scalarLong(
            sql,
            "SELECT COUNT(*) FROM games g WHERE NOT EXISTS " +
                "(SELECT 1 FROM playtime_contributions c WHERE c.account='$ACCOUNT' AND c.appId=g.appId)",
        )
        val sessionCount = scalarLong(sql, "SELECT COUNT(*) FROM sessions")
        val timedCount = scalarLong(sql, "SELECT COUNT(*) FROM recovery_timed_placements WHERE account='$ACCOUNT'")
        val allocationCount = scalarLong(sql, "SELECT COUNT(*) FROM recovery_unknown_allocations WHERE account='$ACCOUNT'")
        val pendingCount = scalarLong(
            sql,
            "SELECT COUNT(*) FROM recovery_unknown_allocations WHERE account='$ACCOUNT' AND pendingDate=1 AND superseded=0",
        )
        val supersededCount = scalarLong(
            sql,
            "SELECT COUNT(*) FROM recovery_unknown_allocations WHERE account='$ACCOUNT' AND superseded=1",
        )
        val authorized = scalarLong(
            sql,
            "SELECT COALESCE(SUM(authorizedMinutes),0) FROM playtime_contributions WHERE account='$ACCOUNT'",
        )
        val timedMinutes = scalarLong(
            sql,
            "SELECT COALESCE(SUM(minutes),0) FROM recovery_timed_placements WHERE account='$ACCOUNT' AND superseded=0",
        )
        val allocationMinutes = scalarLong(
            sql,
            "SELECT COALESCE(SUM(a.minutes),0) FROM recovery_unknown_allocations a " +
                "WHERE a.account='$ACCOUNT' AND a.superseded=0 AND NOT EXISTS " +
                "(SELECT 1 FROM recovery_allocation_recipes r WHERE r.contributionId=a.contributionId)",
        )
        val recipeMinutes = scalarLong(
            sql,
            "SELECT COALESCE(SUM(quotient*eligibleCount+remainderCount),0) " +
                "FROM recovery_allocation_recipes WHERE account='$ACCOUNT' AND pending=0",
        )
        val recipeCount = scalarLong(sql, "SELECT COUNT(*) FROM recovery_allocation_recipes")
        assertEquals(scale.games.toLong(), gameCount)
        assertEquals(scale.games.toLong(), stateCount)
        assertEquals(scale.contributions.toLong(), contributionCount)
        assertEquals((scale.games / QUIET_GAME_DIVISOR).toLong(), quietGames)
        assertEquals(seed.sessions.toLong(), sessionCount)
        assertEquals(seed.timedPlacements.toLong(), timedCount)
        assertEquals(seed.allocations.toLong(), allocationCount)
        assertEquals(seed.pendingAllocations.toLong(), pendingCount)
        assertEquals(seed.supersededAllocations.toLong(), supersededCount)
        assertEquals(seed.authorizedMinutes, authorized)
        assertEquals("active shares + pending + recipe total conserves funded quantity", authorized,
            timedMinutes + allocationMinutes + recipeMinutes)
        assertEquals((RECIPE_SAMPLES * 2 + COMPLETE_RECIPE_COUNT).toLong(), recipeCount)
        seed.validationIds.values.forEach { id ->
            assertTrue("invalid seeded graph $id", database.playtimeRecoveryDao().validateGraph(id))
        }
    }

    private fun scalarLong(database: SupportSQLiteDatabase, query: String): Long {
        val cursor = database.query(query)
        return cursor.use {
            check(it.moveToFirst()) { "scalar query returned no row: $query" }
            it.getLong(0)
        }
    }

    private fun pssKb(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong()
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun fileRowCount(file: BackupFile): Long {
        val recovery = file.recovery
        return (file.games.size + file.achievements.size + file.sessions.size + file.dailyProgress.size +
            file.collections.size + file.collectionMembers.size + file.excludedSharedGames.size + file.hiddenGames.size +
            file.computed.xpPerGame.size + file.computed.xpTimeline.size +
            (recovery?.observations?.size ?: 0) + (recovery?.contributions?.size ?: 0) +
            (recovery?.timedPlacements?.size ?: 0) + (recovery?.unknownAllocations?.size ?: 0) +
            (recovery?.recipes?.size ?: 0) + (recovery?.replacements?.size ?: 0)).toLong()
    }

    private enum class SeedKind {
        SPARSE_RECIPE,
        DENSE_RECIPE,
        SPARSE_COMPLETE_RECIPE,
        DENSE_COMPLETE_RECIPE,
        RECONCILE_SAMPLE,
        PENDING,
        TIMED,
        SUPERSEDED,
        ESTIMATE,
    }

    private data class Scale(val label: String, val games: Int, val contributions: Int)

    private data class ContributionPlan(
        val ordinal: Int,
        val gameIndex: Int,
        val perAppOrdinal: Int,
        val kind: SeedKind,
        val fromMinutes: Long,
        val toMinutes: Long,
        val day: String,
        val windowStartAt: Long?,
        val windowEndAt: Long,
        val goal: Boolean,
    )

    private data class ReconcileSample(val appId: Long, val contributionId: String, val windowStartAt: Long)

    private data class ScaleSeed(
        val games: Int,
        val quietGameCount: Int,
        val contributions: Int,
        val authorizedMinutes: Long,
        val sessions: Int,
        val timedPlacements: Int,
        val allocations: Int,
        val pendingAllocations: Int,
        val supersededAllocations: Int,
        val recipes: Int,
        val highWaterByApp: Map<Long, Int>,
        val growthApps: List<Long>,
        val reconcileSamples: List<ReconcileSample>,
        val sparseRecipeIds: List<String>,
        val denseRecipeIds: List<String>,
        val completeSparseRecipeId: String,
        val completeDenseRecipeId: String,
        val validationIds: Map<String, String>,
    )

    private class SeedRowStats {
        var games = 0
        var quietGameCount = 0
        var contributions = 0
        var authorizedMinutes = 0L
        var sessions = 0
        var timedPlacements = 0
        var allocations = 0
        var pendingAllocations = 0
        var supersededAllocations = 0
        var recipes = 0
        val reconcileSamples = mutableListOf<ReconcileSample>()
        val sparseRecipeIds = mutableListOf<String>()
        val denseRecipeIds = mutableListOf<String>()
        val validationIds = linkedMapOf<String, String>()
        var growthApps = emptyList<Long>()
        var completeSparseRecipeId: String? = null
        var completeDenseRecipeId: String? = null

        fun toSeedInfo(highWater: LongArray): ScaleSeed = ScaleSeed(
            games = games,
            quietGameCount = quietGameCount,
            contributions = contributions,
            authorizedMinutes = authorizedMinutes,
            sessions = sessions,
            timedPlacements = timedPlacements,
            allocations = allocations,
            pendingAllocations = pendingAllocations,
            supersededAllocations = supersededAllocations,
            recipes = recipes,
            highWaterByApp = highWater.mapIndexed { index, value -> APP_BASE + index to value.toInt() }.toMap(),
            growthApps = growthApps,
            reconcileSamples = reconcileSamples.toList(),
            sparseRecipeIds = sparseRecipeIds.toList(),
            denseRecipeIds = denseRecipeIds.toList(),
            completeSparseRecipeId = requireNotNull(completeSparseRecipeId),
            completeDenseRecipeId = requireNotNull(completeDenseRecipeId),
            validationIds = validationIds.toMap(),
        )
    }

    private data class ImportTarget(
        val databaseName: String,
        val database: BacklogiumDatabase,
        val metrics: SqlMetrics,
        val engine: BackupMergeEngine,
    )

    private data class MetricRecord(
        val elapsedNanos: Long,
        val sqlQueries: Long,
        val readQueries: Long,
        val cursorRowsReturned: Long,
        val rowsReportedAtApi: Long,
        val writeStatements: Long,
        val rowsWritten: Long,
        val transactionCount: Long,
        val transactionNanos: Long,
        val scopedTransactionCount: Long,
        val scopedTransactionNanos: Long,
        val readOnlyTransactionCount: Long,
        val readOnlyTransactionNanos: Long,
        val pssDeltaKb: Long,
        val heapDeltaBytes: Long,
        val sqlStatementCounts: Map<String, Long>,
    )

    private data class SqlMetricSnapshot(
        val queryCount: Long,
        val readQueries: Long,
        val writeStatements: Long,
        val rowsWritten: Long,
        val rowsReturned: Long,
        val transactionCount: Long,
        val transactionNanos: Long,
        val scopedTransactionCount: Long,
        val scopedTransactionNanos: Long,
        val readOnlyTransactionCount: Long,
        val readOnlyTransactionNanos: Long,
        val statementCounts: Map<String, Long>,
    )

    private class SqlMetrics {
        @Volatile var enabled = false
        private val queryCount = AtomicLong()
        private val readQueries = AtomicLong()
        private val writeStatements = AtomicLong()
        private val rowsWritten = AtomicLong()
        private val rowsReturned = AtomicLong()
        private val transactionCount = AtomicLong()
        private val transactionNanos = AtomicLong()
        private val scopedTransactionCount = AtomicLong()
        private val scopedTransactionNanos = AtomicLong()
        private val readOnlyTransactionCount = AtomicLong()
        private val readOnlyTransactionNanos = AtomicLong()
        private val statementCounts = ConcurrentHashMap<String, AtomicLong>()
        private val transactionStarts = ThreadLocal<ArrayDeque<TransactionFrame>>()

        fun reset() {
            queryCount.set(0L)
            readQueries.set(0L)
            writeStatements.set(0L)
            rowsWritten.set(0L)
            rowsReturned.set(0L)
            transactionCount.set(0L)
            transactionNanos.set(0L)
            scopedTransactionCount.set(0L)
            scopedTransactionNanos.set(0L)
            readOnlyTransactionCount.set(0L)
            readOnlyTransactionNanos.set(0L)
            statementCounts.clear()
            transactionStarts.remove()
        }

        fun recordQuery(sql: String) {
            if (!enabled) return
            queryCount.incrementAndGet()
            if (sql.trimStart().startsWith("SELECT", ignoreCase = true) ||
                sql.trimStart().startsWith("WITH", ignoreCase = true)
            ) {
                readQueries.incrementAndGet()
            }
            statementCounts.getOrPut(fingerprint(sql)) { AtomicLong() }.incrementAndGet()
        }

        fun recordWrite(sql: String, affectedRows: Long = 0L) {
            if (!enabled) return
            val head = sql.trimStart().takeWhile { !it.isWhitespace() }.uppercase()
            if (head in DML) {
                writeStatements.incrementAndGet()
                rowsWritten.addAndGet(affectedRows.coerceAtLeast(0L))
                statementCounts.getOrPut(fingerprint(sql)) { AtomicLong() }.incrementAndGet()
            }
        }

        fun addRowsReturned(rows: Int) {
            if (enabled) rowsReturned.addAndGet(rows.toLong())
        }

        fun addVisitedRow() {
            if (enabled) rowsReturned.incrementAndGet()
        }

        fun addWrittenRows(count: Int) {
            if (enabled && count > 0) rowsWritten.addAndGet(count.toLong())
        }

        suspend fun <R> measureScopedTransaction(block: suspend () -> R): R {
            if (!enabled) return block()
            val startedAtNanos = System.nanoTime()
            return try {
                block()
            } finally {
                recordScopedTransactionDuration(System.nanoTime() - startedAtNanos)
            }
        }

        fun recordScopedTransactionDuration(durationNanos: Long) {
            if (!enabled) return
            scopedTransactionCount.incrementAndGet()
            scopedTransactionNanos.addAndGet(durationNanos.coerceAtLeast(0L))
        }

        fun beginTransaction(readOnly: Boolean = false) {
            if (!enabled) return
            val stack = transactionStarts.get() ?: ArrayDeque<TransactionFrame>().also(transactionStarts::set)
            if (stack.isEmpty()) {
                transactionCount.incrementAndGet()
                if (readOnly) readOnlyTransactionCount.incrementAndGet()
            }
            stack.addLast(TransactionFrame(System.nanoTime(), readOnly))
        }

        fun endTransaction() {
            if (!enabled) return
            val stack = transactionStarts.get() ?: return
            if (stack.isNotEmpty()) {
                val frame = stack.removeLast()
                if (stack.isEmpty()) {
                    val elapsed = System.nanoTime() - frame.startedAtNanos
                    transactionNanos.addAndGet(elapsed)
                    if (frame.readOnly) readOnlyTransactionNanos.addAndGet(elapsed)
                }
            }
        }

        fun snapshot() = SqlMetricSnapshot(
            queryCount = queryCount.get(),
            readQueries = readQueries.get(),
            writeStatements = writeStatements.get(),
            rowsWritten = rowsWritten.get(),
            rowsReturned = rowsReturned.get(),
            transactionCount = transactionCount.get(),
            transactionNanos = transactionNanos.get(),
            scopedTransactionCount = scopedTransactionCount.get(),
            scopedTransactionNanos = scopedTransactionNanos.get(),
            readOnlyTransactionCount = readOnlyTransactionCount.get(),
            readOnlyTransactionNanos = readOnlyTransactionNanos.get(),
            statementCounts = statementCounts.entries
                .sortedByDescending { it.value.get() }
                .take(12)
                .associate { it.key to it.value.get() },
        )

        private fun fingerprint(sql: String): String = sql.trim().replace(WHITESPACE, " ").take(160)

        companion object {
            private val DML = setOf("INSERT", "UPDATE", "DELETE", "REPLACE")
            private val WHITESPACE = Regex("\\s+")

            fun isDml(sql: String): Boolean =
                sql.trimStart().takeWhile { !it.isWhitespace() }.uppercase() in DML
        }

        private data class TransactionFrame(val startedAtNanos: Long, val readOnly: Boolean)
    }

    private class CountingTransactionScope(
        private val delegate: DatabaseTransactionScope,
        private val metrics: SqlMetrics,
    ) : DatabaseTransactionScope {
        override suspend fun <R> run(block: suspend () -> R): R =
            metrics.measureScopedTransaction { delegate.run(block) }
    }

    private class CountingOpenHelperFactory(private val metrics: SqlMetrics) : SupportSQLiteOpenHelper.Factory {
        private val delegate = FrameworkSQLiteOpenHelperFactory()

        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val helper = delegate.create(configuration)
            return object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase: SupportSQLiteDatabase
                    get() = CountingSupportSQLiteDatabase(helper.writableDatabase, metrics)
                override val readableDatabase: SupportSQLiteDatabase
                    get() = CountingSupportSQLiteDatabase(helper.readableDatabase, metrics)
            }
        }
    }

    private class CountingSupportSQLiteDatabase(
        private val delegate: SupportSQLiteDatabase,
        private val metrics: SqlMetrics,
    ) : SupportSQLiteDatabase by delegate {
        override fun compileStatement(sql: String): SupportSQLiteStatement =
            CountingSupportSQLiteStatement(delegate.compileStatement(sql), sql, metrics, ::changes)

        override fun query(query: String): Cursor = counted(query) { delegate.query(query) }

        override fun query(query: String, bindArgs: Array<out Any?>): Cursor =
            counted(query) { delegate.query(query, bindArgs) }

        override fun query(query: SupportSQLiteQuery): Cursor = counted(query.sql) { delegate.query(query) }

        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
            counted(query.sql) { delegate.query(query, cancellationSignal) }

        override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long {
            val rowId = delegate.insert(table, conflictAlgorithm, values)
            metrics.recordWrite("INSERT $table", if (rowId >= 0L) 1L else 0L)
            return rowId
        }

        override fun update(
            table: String,
            conflictAlgorithm: Int,
            values: ContentValues,
            whereClause: String?,
            whereArgs: Array<out Any?>?,
        ): Int {
            val affected = delegate.update(table, conflictAlgorithm, values, whereClause, whereArgs)
            metrics.recordWrite("UPDATE $table", affected.toLong())
            return affected
        }

        override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int {
            val affected = delegate.delete(table, whereClause, whereArgs)
            metrics.recordWrite("DELETE $table", affected.toLong())
            return affected
        }

        override fun execSQL(sql: String) {
            metrics.recordWrite(sql)
            delegate.execSQL(sql)
            if (SqlMetrics.isDml(sql)) metrics.addWrittenRows(changes())
        }

        override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
            metrics.recordWrite(sql)
            delegate.execSQL(sql, bindArgs)
            if (SqlMetrics.isDml(sql)) metrics.addWrittenRows(changes())
        }

        override fun beginTransaction() {
            delegate.beginTransaction()
            metrics.beginTransaction()
        }

        override fun beginTransactionNonExclusive() {
            delegate.beginTransactionNonExclusive()
            metrics.beginTransaction()
        }

        override fun beginTransactionReadOnly() {
            delegate.beginTransactionReadOnly()
            metrics.beginTransaction(readOnly = true)
        }

        override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) {
            delegate.beginTransactionWithListener(transactionListener)
            metrics.beginTransaction()
        }

        override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) {
            delegate.beginTransactionWithListenerNonExclusive(transactionListener)
            metrics.beginTransaction()
        }

        override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) {
            delegate.beginTransactionWithListenerReadOnly(transactionListener)
            metrics.beginTransaction(readOnly = true)
        }

        override fun endTransaction() {
            try {
                delegate.endTransaction()
            } finally {
                metrics.endTransaction()
            }
        }

        private inline fun counted(sql: String, query: () -> Cursor): Cursor {
            metrics.recordQuery(sql)
            return CountingCursor(query(), metrics)
        }

        private fun changes(): Int = delegate.query("SELECT changes()").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private class CountingSupportSQLiteStatement(
        private val delegate: SupportSQLiteStatement,
        private val sql: String,
        private val metrics: SqlMetrics,
        private val readChanges: () -> Int,
    ) : SupportSQLiteStatement by delegate {
        override fun execute() {
            metrics.recordWrite(sql)
            delegate.execute()
            if (SqlMetrics.isDml(sql)) metrics.addWrittenRows(readChanges())
        }

        override fun executeInsert(): Long {
            val rowId = delegate.executeInsert()
            metrics.recordWrite(sql, if (rowId >= 0L) 1L else 0L)
            return rowId
        }

        override fun executeUpdateDelete(): Int {
            val affected = delegate.executeUpdateDelete()
            metrics.recordWrite(sql, affected.toLong())
            return affected
        }
    }

    private class CountingCursor(delegate: Cursor, private val metrics: SqlMetrics) : CursorWrapper(delegate) {
        private var allRowsCounted = false
        private var rowsVisitedBeforeCount = 0

        override fun getCount(): Int {
            val count = super.getCount()
            if (!allRowsCounted) {
                metrics.addRowsReturned((count - rowsVisitedBeforeCount).coerceAtLeast(0))
                allRowsCounted = true
            }
            return count
        }

        override fun moveToFirst(): Boolean = super.moveToFirst().also(::countVisitedRow)

        override fun moveToPosition(position: Int): Boolean =
            super.moveToPosition(position).also(::countVisitedRow)

        override fun moveToLast(): Boolean = super.moveToLast().also(::countVisitedRow)

        override fun moveToNext(): Boolean {
            return super.moveToNext().also(::countVisitedRow)
        }

        private fun countVisitedRow(moved: Boolean) {
            if (moved && !allRowsCounted) {
                rowsVisitedBeforeCount++
                metrics.addVisitedRow()
            }
        }
    }

    private object BenchmarkCredentials : CredentialsProvider {
        override suspend fun currentCredentials(): CredentialsState.Configured? =
            CredentialsState.Configured(apiKey = "benchmark-only", steamId = ACCOUNT)
    }

    private object BenchmarkTime : TimeProvider {
        override fun nowMillis(): Long = NOW
        override fun zone(): ZoneId = ZoneOffset.UTC
        override fun today(): LocalDate = TODAY
    }

    private companion object {
        const val TAG = "RecoveryScaleBenchmark"
        const val FIXTURE_SEED = 1_830_601
        const val ACCOUNT = "76561198000000000"
        const val APP_BASE = 100_000L
        const val RECIPE_SAMPLES = 6
        const val COMPLETE_RECIPE_COUNT = 2
        const val RECONCILE_SAMPLES = 6
        const val SPECIAL_APP_COUNT = RECIPE_SAMPLES * 2 + COMPLETE_RECIPE_COUNT + RECONCILE_SAMPLES
        const val SPECIAL_CONTRIBUTION_COUNT = SPECIAL_APP_COUNT
        const val GROWTH_SAMPLES = 6
        const val MEASURED_RUNS = 5
        const val RECIPE_PAGE = 366
        const val TEN_YEAR_PAGE_COUNT = 10
        const val WINDOW_DAYS = 30
        const val QUIET_GAME_DIVISOR = 20
        const val RECONCILE_DAYS_AGO = 7
        const val INITIAL_HIGH_WATER = 500L
        const val RECONCILE_SAMPLE_MINUTES = 105L
        const val MIX_PERIOD = 20
        const val TIMED_PERIOD_COUNT = 3
        const val SUPERSEDED_SLOT = 4
        const val STANDARD_WIDTH = 10L
        const val SPARSE_RECIPE_MINUTES = 1L
        const val DENSE_RECIPE_MINUTES = 36_530L
        const val RECIPE_DENSE_QUOTIENT = 10L
        const val BACKFILL_MINUTES = 60
        const val TIMED_MINUTES = 4
        const val SESSION_MINUTES = 7
        const val MINUTE_MS = 60_000L
        const val TEN_MINUTES_MS = 10 * MINUTE_MS
        const val HOUR_MS = 60 * MINUTE_MS
        const val DAY_MS = 24 * HOUR_MS
        const val TEN_HOUR_MS = 10 * HOUR_MS
        const val CONFIG_VERSION = 7L
        val TODAY = LocalDate.parse("2026-09-10")
        val BASELINE_AT = Instant.parse("2026-08-11T12:00:00Z").toEpochMilli()
        val NOW = Instant.parse("2026-09-10T12:00:00Z").toEpochMilli()
        val TEN_YEAR_START_DATE = LocalDate.parse("2016-01-01")
        val TEN_YEAR_END_DATE = LocalDate.parse("2025-12-31")
        val TEN_YEAR_DAYS = java.time.temporal.ChronoUnit.DAYS.between(TEN_YEAR_START_DATE, TEN_YEAR_END_DATE) + 1
        val TEN_YEAR_START = TEN_YEAR_START_DATE.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val TEN_YEAR_END = TEN_YEAR_END_DATE.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1
        val CONFIG = RuleConfig(questThresholdMin = 30)
    }
}
