package com.example.backlogium.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.backlogium.data.local.dao.HLTB_DATA_WITH_DATASET_QUERY

/** Observe deadline-only writes, including backup merges, within the transaction that changes evidence. */
internal object DeadlineEvidenceSchema : RoomDatabase.Callback() {
    private const val NOW = "(strftime('%s','now') * 1000 + substr(strftime('%f','now'),4,3))"

    private const val GOAL_COLUMNS_V55 = "(collectionId,effectiveAt,name,targetDate,zone,basis)"
    private const val MEMBER_COLUMNS_V55 =
        "(collectionId,effectiveAt,appId,name,hidden,present,mainStory,mainExtra,completionist,allStyles)"
    private const val REVISION_COLUMNS_V56 =
        "(collectionId,effectiveAt,appId,name,targetDate,zone,basis,hidden,present," +
            "mainStory,mainExtra,completionist,allStyles,gameSource,batchId,batchStartedAt,batchCompletedAt)"
    private const val REVISION_COLUMNS =
        "(collectionId,effectiveAt,appId,name,targetDate,zone,basis,hidden,present," +
            "mainStory,mainExtra,completionist,allStyles,gameSource,batchId,batchStartedAt,batchCompletedAt,deadlineActive)"
    // Per-member trigger reads must stay keyed; the ordered union view materializes the full dataset.
    private val deadlineEstimateJoins = """
        LEFT JOIN hltb_data cached ON cached.appId = m.appId
        LEFT JOIN hltb_dataset_mappings mapping ON mapping.appId = m.appId AND cached.appId IS NULL
        LEFT JOIN hltb_dataset_lengths lengths ON lengths.hltbId = mapping.hltbId
        LEFT JOIN hltb_dataset_state state ON state.id = 0 AND cached.appId IS NULL
    """.trimIndent()

    override fun onCreate(db: SupportSQLiteDatabase) = install(db)

    /** The 54→55 migration must retain its released table shape until 55→56 adds provenance. */
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE collections ADD COLUMN deadlineZone TEXT")
        db.execSQL("ALTER TABLE collections ADD COLUMN originId INTEGER")
        db.execSQL("CREATE TABLE deadline_revisions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, collectionId INTEGER NOT NULL, effectiveAt INTEGER NOT NULL, appId INTEGER, name TEXT, targetDate TEXT, zone TEXT, basis TEXT, hidden INTEGER, present INTEGER, mainStory INTEGER, mainExtra INTEGER, completionist INTEGER, allStyles INTEGER, FOREIGN KEY(collectionId) REFERENCES collections(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_deadline_revisions_collectionId_effectiveAt ON deadline_revisions(collectionId,effectiveAt)")
        db.execSQL("CREATE TABLE deadline_archives (originId INTEGER NOT NULL, createdAt INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(originId,createdAt))")
        db.execSQL(
            "UPDATE collections SET deadlineZone = ? WHERE mode = 'DEADLINE_GOAL'",
            arrayOf(java.time.ZoneId.systemDefault().id),
        )
        // One INSERT statement gives every migration baseline the same statement-time boundary.
        db.execSQL(legacyBaseline())
        installLegacy(db)
    }

    fun migrateTransactionBoundaries(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE deadline_revisions ADD COLUMN gameSource TEXT")
        db.execSQL("ALTER TABLE deadline_revisions ADD COLUMN batchId TEXT")
        db.execSQL("ALTER TABLE deadline_revisions ADD COLUMN batchStartedAt INTEGER")
        db.execSQL("ALTER TABLE deadline_revisions ADD COLUMN batchCompletedAt INTEGER")
        db.execSQL("CREATE INDEX index_deadline_revisions_batchId ON deadline_revisions(batchId)")
        db.execSQL(
            "CREATE TABLE deadline_revision_batch (id INTEGER NOT NULL PRIMARY KEY, " +
                "batchId TEXT NOT NULL, startedAt INTEGER NOT NULL, depth INTEGER NOT NULL)",
        )
        triggerNames.forEach { db.execSQL("DROP TRIGGER IF EXISTS deadline_$it") }
        install(db, modeBoundaries = false)
        // Legacy active deadlines gain a source baseline only from this migration boundary onward.
        db.execSQL(members("1", modeBoundaries = false))
    }

    fun migrateModeBoundaries(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE deadline_revisions ADD COLUMN deadlineActive INTEGER")
        triggerNames.forEach { db.execSQL("DROP TRIGGER IF EXISTS deadline_$it") }
        install(db)
        // Pre-v57 rows cannot prove that every mode exit was recorded; only the migration boundary is known.
        db.execSQL(currentGoalBaselines())
        db.execSQL(absentActiveMembers())
        db.execSQL(members("1"))
    }

    private fun legacyBaseline() = """
        INSERT INTO deadline_revisions
            (collectionId,effectiveAt,appId,name,targetDate,zone,basis,hidden,present,
             mainStory,mainExtra,completionist,allStyles)
        SELECT c.id,$NOW,NULL,c.name,c.targetDate,c.deadlineZone,c.timeBasis,
            NULL,NULL,NULL,NULL,NULL,NULL FROM collections c
        UNION ALL
        SELECT m.collectionId,$NOW,m.appId,g.name,NULL,NULL,NULL,h.appId IS NOT NULL,1,
            CASE WHEN e.matchStatus = 'RESOLVED' THEN e.mainStoryMinutes END,
            CASE WHEN e.matchStatus = 'RESOLVED' THEN e.mainExtraMinutes END,
            CASE WHEN e.matchStatus = 'RESOLVED' THEN e.completionistMinutes END,
            CASE WHEN e.matchStatus = 'RESOLVED' THEN e.allStylesMinutes END
        FROM collection_members m
        LEFT JOIN games g ON g.appId = m.appId
        LEFT JOIN hidden_games h ON h.appId = m.appId
        LEFT JOIN ($HLTB_DATA_WITH_DATASET_QUERY) e ON e.appId = m.appId
    """.trimIndent()

    private fun members(where: String, modeBoundaries: Boolean = true): String = """
        INSERT INTO deadline_revisions ${if (modeBoundaries) REVISION_COLUMNS else REVISION_COLUMNS_V56}
        SELECT m.collectionId,$NOW,m.appId,g.name,NULL,NULL,NULL,h.appId IS NOT NULL,1,
            ${currentEstimate("mainStoryMinutes")},
            ${currentEstimate("mainExtraMinutes")},
            ${currentEstimate("completionistMinutes")},
            ${currentEstimate("allStylesMinutes")},
            g.source,
            (SELECT batchId FROM deadline_revision_batch WHERE id = 0),
            (SELECT startedAt FROM deadline_revision_batch WHERE id = 0),NULL${if (modeBoundaries) ",NULL" else ""}
        FROM collection_members m
        LEFT JOIN games g ON g.appId = m.appId
        LEFT JOIN hidden_games h ON h.appId = m.appId
        $deadlineEstimateJoins
        WHERE $where AND EXISTS (
            SELECT 1 FROM collections c WHERE c.id = m.collectionId AND c.mode = 'DEADLINE_GOAL'
        )
    """.trimIndent()

    private fun currentEstimate(column: String) =
        "CASE WHEN cached.matchStatus = 'RESOLVED' THEN cached.$column " +
            "WHEN cached.appId IS NULL AND state.id IS NOT NULL THEN lengths.$column END"

    private fun installLegacy(db: SupportSQLiteDatabase) {
        fun trigger(name: String, clause: String, statement: String) =
            db.execSQL("CREATE TRIGGER IF NOT EXISTS deadline_$name $clause BEGIN $statement; END")
        val goal = "INSERT INTO deadline_revisions $GOAL_COLUMNS_V55 " +
            "VALUES (NEW.id,$NOW,NEW.name,NEW.targetDate,NEW.deadlineZone,NEW.timeBasis)"
        trigger("goal_insert", "AFTER INSERT ON collections", goal)
        trigger(
            "goal_update", "AFTER UPDATE ON collections WHEN " +
                "OLD.name IS NOT NEW.name OR OLD.targetDate IS NOT NEW.targetDate OR " +
                "OLD.deadlineZone IS NOT NEW.deadlineZone OR OLD.timeBasis IS NOT NEW.timeBasis OR " +
                "OLD.mode IS NOT NEW.mode", goal,
        )
        trigger("member_insert", "AFTER INSERT ON collection_members", legacyMembers("m.collectionId = NEW.collectionId AND m.appId = NEW.appId"))
        trigger(
            "member_delete", "AFTER DELETE ON collection_members " +
                "WHEN EXISTS(SELECT 1 FROM collections WHERE id = OLD.collectionId)",
            "INSERT INTO deadline_revisions (collectionId,effectiveAt,appId,present) " +
                "VALUES (OLD.collectionId,$NOW,OLD.appId,0)",
        )
        trigger("hide", "AFTER INSERT ON hidden_games", legacyMembers("m.appId = NEW.appId"))
        trigger("unhide", "AFTER DELETE ON hidden_games", legacyMembers("m.appId = OLD.appId"))
        trigger("estimate_insert", "AFTER INSERT ON hltb_data", legacyMembers("m.appId = NEW.appId"))
        trigger("estimate_delete", "AFTER DELETE ON hltb_data", legacyMembers("m.appId = OLD.appId"))
        trigger(
            "estimate_update", "AFTER UPDATE ON hltb_data WHEN " +
                "OLD.matchStatus IS NOT NEW.matchStatus OR OLD.hltbId IS NOT NEW.hltbId OR " +
                "OLD.mainStoryMinutes IS NOT NEW.mainStoryMinutes OR OLD.mainExtraMinutes IS NOT NEW.mainExtraMinutes OR " +
                "OLD.completionistMinutes IS NOT NEW.completionistMinutes OR OLD.allStylesMinutes IS NOT NEW.allStylesMinutes",
            legacyMembers("m.appId = NEW.appId"),
        )
        trigger("dataset_insert", "AFTER INSERT ON hltb_dataset_state", legacyMembers("1"))
        trigger("dataset_update", "AFTER UPDATE ON hltb_dataset_state", legacyMembers("1"))
    }

    private fun legacyMembers(where: String): String = """
        INSERT INTO deadline_revisions $MEMBER_COLUMNS_V55
        SELECT m.collectionId,$NOW,m.appId,g.name,h.appId IS NOT NULL,1,
            CASE WHEN e.matchStatus = 'RESOLVED' THEN e.mainStoryMinutes END,
            CASE WHEN e.matchStatus = 'RESOLVED' THEN e.mainExtraMinutes END,
            CASE WHEN e.matchStatus = 'RESOLVED' THEN e.completionistMinutes END,
            CASE WHEN e.matchStatus = 'RESOLVED' THEN e.allStylesMinutes END
        FROM collection_members m
        LEFT JOIN games g ON g.appId = m.appId
        LEFT JOIN hidden_games h ON h.appId = m.appId
        LEFT JOIN ($HLTB_DATA_WITH_DATASET_QUERY) e ON e.appId = m.appId
        WHERE $where
    """.trimIndent()

    private fun currentGoalBaselines() = """
        INSERT INTO deadline_revisions $REVISION_COLUMNS
        SELECT c.id,$NOW,NULL,c.name,c.targetDate,c.deadlineZone,c.timeBasis,
            NULL,NULL,NULL,NULL,NULL,NULL,NULL,
            (SELECT batchId FROM deadline_revision_batch WHERE id = 0),
            (SELECT startedAt FROM deadline_revision_batch WHERE id = 0),NULL,
            c.mode = 'DEADLINE_GOAL'
        FROM collections c
    """.trimIndent()

    private fun absentActiveMembers() = """
        INSERT INTO deadline_revisions $REVISION_COLUMNS
        SELECT r.collectionId,$NOW,r.appId,NULL,NULL,NULL,NULL,NULL,0,
            NULL,NULL,NULL,NULL,NULL,
            (SELECT batchId FROM deadline_revision_batch WHERE id = 0),
            (SELECT startedAt FROM deadline_revision_batch WHERE id = 0),NULL,NULL
        FROM (
            SELECT DISTINCT r.collectionId,r.appId
            FROM deadline_revisions r JOIN collections c ON c.id = r.collectionId
            WHERE r.appId IS NOT NULL AND c.mode = 'DEADLINE_GOAL'
        ) r
        WHERE NOT EXISTS (
            SELECT 1 FROM collection_members m
            WHERE m.collectionId = r.collectionId AND m.appId = r.appId
        )
    """.trimIndent()

    private fun install(db: SupportSQLiteDatabase, modeBoundaries: Boolean = true) {
        fun trigger(name: String, clause: String, statement: String) =
            db.execSQL("CREATE TRIGGER IF NOT EXISTS deadline_$name $clause BEGIN $statement; END")
        val transactionId = "(SELECT batchId FROM deadline_revision_batch WHERE id = 0)"
        val transactionStartedAt = "(SELECT startedAt FROM deadline_revision_batch WHERE id = 0)"
        val revisionColumns = if (modeBoundaries) REVISION_COLUMNS else REVISION_COLUMNS_V56
        val activeValue = if (modeBoundaries) ",1" else ""
        val goal = "INSERT INTO deadline_revisions $revisionColumns " +
            "VALUES (NEW.id,$NOW,NULL,NEW.name,NEW.targetDate,NEW.deadlineZone,NEW.timeBasis," +
            "NULL,NULL,NULL,NULL,NULL,NULL,NULL,$transactionId,$transactionStartedAt,NULL$activeValue)"
        trigger("goal_insert", "AFTER INSERT ON collections WHEN NEW.mode = 'DEADLINE_GOAL'", goal)
        trigger(
            "goal_update", "AFTER UPDATE ON collections WHEN NEW.mode = 'DEADLINE_GOAL' AND (" +
                "OLD.name IS NOT NEW.name OR OLD.targetDate IS NOT NEW.targetDate OR " +
                "OLD.deadlineZone IS NOT NEW.deadlineZone OR OLD.timeBasis IS NOT NEW.timeBasis OR " +
            "OLD.mode IS NOT NEW.mode)", goal,
        )
        if (modeBoundaries) {
            trigger(
                "mode_exit", "AFTER UPDATE OF mode ON collections " +
                    "WHEN OLD.mode = 'DEADLINE_GOAL' AND NEW.mode <> 'DEADLINE_GOAL'",
                "INSERT INTO deadline_revisions $REVISION_COLUMNS " +
                    "VALUES (NEW.id,$NOW,NULL,NEW.name,NULL,NULL,NEW.timeBasis," +
                    "NULL,NULL,NULL,NULL,NULL,NULL,NULL,$transactionId,$transactionStartedAt,NULL,0)",
            )
        }
        trigger(
            "mode_deadline", "AFTER UPDATE OF mode ON collections " +
                "WHEN OLD.mode IS NOT 'DEADLINE_GOAL' AND NEW.mode = 'DEADLINE_GOAL'",
            if (modeBoundaries) {
                "${absentMembers("NEW.id")}; ${members("m.collectionId = NEW.id")}"
            } else {
                members("m.collectionId = NEW.id", modeBoundaries = false)
            },
        )
        trigger(
            "member_insert", "AFTER INSERT ON collection_members " +
                "WHEN EXISTS(SELECT 1 FROM collections WHERE id = NEW.collectionId AND mode = 'DEADLINE_GOAL')",
            members("m.collectionId = NEW.collectionId AND m.appId = NEW.appId", modeBoundaries),
        )
        trigger(
            "member_delete", "AFTER DELETE ON collection_members " +
                "WHEN EXISTS(SELECT 1 FROM collections WHERE id = OLD.collectionId AND mode = 'DEADLINE_GOAL')",
            "INSERT INTO deadline_revisions (collectionId,effectiveAt,appId,present,gameSource,batchId,batchStartedAt) " +
                "VALUES (OLD.collectionId,$NOW,OLD.appId,0,NULL,$transactionId,$transactionStartedAt)",
        )
        trigger("hide", "AFTER INSERT ON hidden_games ${deadlineMemberExists("NEW.appId")}", members("m.appId = NEW.appId", modeBoundaries))
        trigger("unhide", "AFTER DELETE ON hidden_games ${deadlineMemberExists("OLD.appId")}", members("m.appId = OLD.appId", modeBoundaries))
        trigger(
            "game_source", "AFTER UPDATE OF source ON games WHEN OLD.source IS NOT NEW.source AND " +
                deadlineMemberCondition("NEW.appId"),
            members("m.appId = NEW.appId", modeBoundaries),
        )
        trigger(
            "game_insert", "AFTER INSERT ON games ${deadlineMemberExists("NEW.appId")}",
            members("m.appId = NEW.appId", modeBoundaries),
        )
        trigger("estimate_insert", "AFTER INSERT ON hltb_data ${deadlineMemberExists("NEW.appId")}", members("m.appId = NEW.appId", modeBoundaries))
        trigger("estimate_delete", "AFTER DELETE ON hltb_data ${deadlineMemberExists("OLD.appId")}", members("m.appId = OLD.appId", modeBoundaries))
        trigger(
            "estimate_update", "AFTER UPDATE ON hltb_data WHEN (" +
                "OLD.matchStatus IS NOT NEW.matchStatus OR OLD.hltbId IS NOT NEW.hltbId OR " +
                "OLD.mainStoryMinutes IS NOT NEW.mainStoryMinutes OR OLD.mainExtraMinutes IS NOT NEW.mainExtraMinutes OR " +
                "OLD.completionistMinutes IS NOT NEW.completionistMinutes OR OLD.allStylesMinutes IS NOT NEW.allStylesMinutes " +
                ") AND " + deadlineMemberCondition("NEW.appId"),
            members("m.appId = NEW.appId", modeBoundaries),
        )
        trigger(
            "dataset_insert", "AFTER INSERT ON hltb_dataset_state " +
                "WHEN EXISTS(SELECT 1 FROM collections WHERE mode = 'DEADLINE_GOAL')",
            members(datasetEstimateChanged(), modeBoundaries),
        )
        trigger(
            "dataset_update", "AFTER UPDATE ON hltb_dataset_state " +
                "WHEN EXISTS(SELECT 1 FROM collections WHERE mode = 'DEADLINE_GOAL')",
            members(datasetEstimateChanged(), modeBoundaries),
        )
    }

    private fun absentMembers(collectionId: String) = """
        INSERT INTO deadline_revisions $REVISION_COLUMNS
        SELECT r.collectionId,$NOW,r.appId,NULL,NULL,NULL,NULL,NULL,0,
            NULL,NULL,NULL,NULL,NULL,
            (SELECT batchId FROM deadline_revision_batch WHERE id = 0),
            (SELECT startedAt FROM deadline_revision_batch WHERE id = 0),NULL,NULL
        FROM (
            SELECT DISTINCT collectionId,appId FROM deadline_revisions
            WHERE collectionId = $collectionId AND appId IS NOT NULL
        ) r
        WHERE NOT EXISTS (
            SELECT 1 FROM collection_members m
            WHERE m.collectionId = r.collectionId AND m.appId = r.appId
        )
    """.trimIndent()

    private fun deadlineMemberExists(appId: String) = "WHEN ${deadlineMemberCondition(appId)}"

    private fun deadlineMemberCondition(appId: String) = "EXISTS (" +
        "SELECT 1 FROM collection_members m JOIN collections c ON c.id = m.collectionId " +
        "WHERE m.appId = $appId AND c.mode = 'DEADLINE_GOAL')"

    private fun datasetEstimateChanged() = """
        NOT EXISTS (
            SELECT 1 FROM deadline_revisions r
            WHERE r.collectionId = m.collectionId AND r.appId = m.appId
              AND r.id = (SELECT MAX(previous.id) FROM deadline_revisions previous
                          WHERE previous.collectionId = m.collectionId AND previous.appId = m.appId)
              AND r.mainStory IS ${currentEstimate("mainStoryMinutes")}
              AND r.mainExtra IS ${currentEstimate("mainExtraMinutes")}
              AND r.completionist IS ${currentEstimate("completionistMinutes")}
              AND r.allStyles IS ${currentEstimate("allStylesMinutes")}
        )
    """.trimIndent()

    private val triggerNames = listOf(
        "goal_insert", "goal_update", "mode_deadline", "mode_exit", "member_insert", "member_delete",
        "hide", "unhide", "game_source", "game_insert", "estimate_insert", "estimate_delete", "estimate_update",
        "dataset_insert", "dataset_update",
    )
}
