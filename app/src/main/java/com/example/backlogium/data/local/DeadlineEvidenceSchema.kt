package com.example.backlogium.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.backlogium.data.local.dao.HLTB_DATA_WITH_DATASET_QUERY

/** Observe raw writes, including backup merges, within the transaction that changes the evidence. */
internal object DeadlineEvidenceSchema : RoomDatabase.Callback() {
    private const val NOW = "(strftime('%s','now') * 1000 + substr(strftime('%f','now'),4,3))"

    private const val GOAL_COLUMNS = "(collectionId,effectiveAt,name,targetDate,zone,basis)"
    private const val MEMBER_COLUMNS =
        "(collectionId,effectiveAt,appId,name,hidden,present,mainStory,mainExtra,completionist,allStyles)"

    override fun onCreate(db: SupportSQLiteDatabase) = install(db)

    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE collections ADD COLUMN deadlineZone TEXT")
        db.execSQL("ALTER TABLE collections ADD COLUMN originId INTEGER")
        db.execSQL("CREATE TABLE deadline_revisions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, collectionId INTEGER NOT NULL, effectiveAt INTEGER NOT NULL, appId INTEGER, name TEXT, targetDate TEXT, zone TEXT, basis TEXT, hidden INTEGER, present INTEGER, mainStory INTEGER, mainExtra INTEGER, completionist INTEGER, allStyles INTEGER, FOREIGN KEY(collectionId) REFERENCES collections(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_deadline_revisions_collectionId_effectiveAt ON deadline_revisions(collectionId,effectiveAt)")
        db.execSQL("CREATE TABLE deadline_archives (originId INTEGER NOT NULL, createdAt INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(originId,createdAt))")
        // The original device zone was never stored. Start observation at migration, using today's
        // device zone for the existing date. A cutoff before migration has no membership baseline.
        db.execSQL(
            "UPDATE collections SET deadlineZone = ? WHERE mode = 'DEADLINE_GOAL'",
            arrayOf(java.time.ZoneId.systemDefault().id),
        )
        db.execSQL("INSERT INTO deadline_revisions $GOAL_COLUMNS " +
            "SELECT id,$NOW,name,targetDate,deadlineZone,timeBasis FROM collections")
        db.execSQL(members("1"))
        install(db)
    }

    private fun members(where: String): String = """
        INSERT INTO deadline_revisions $MEMBER_COLUMNS
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

    private fun install(db: SupportSQLiteDatabase) {
        fun trigger(name: String, clause: String, statement: String) =
            db.execSQL("CREATE TRIGGER IF NOT EXISTS deadline_$name $clause BEGIN $statement; END")
        val goal = "INSERT INTO deadline_revisions $GOAL_COLUMNS " +
            "VALUES (NEW.id,$NOW,NEW.name,NEW.targetDate,NEW.deadlineZone,NEW.timeBasis)"
        trigger("goal_insert", "AFTER INSERT ON collections", goal)
        trigger(
            "goal_update", "AFTER UPDATE ON collections WHEN " +
                "OLD.name IS NOT NEW.name OR OLD.targetDate IS NOT NEW.targetDate OR " +
                "OLD.deadlineZone IS NOT NEW.deadlineZone OR OLD.timeBasis IS NOT NEW.timeBasis OR " +
                "OLD.mode IS NOT NEW.mode", goal,
        )
        trigger("member_insert", "AFTER INSERT ON collection_members",
            members("m.collectionId = NEW.collectionId AND m.appId = NEW.appId"))
        trigger("member_delete", "AFTER DELETE ON collection_members " +
            "WHEN EXISTS(SELECT 1 FROM collections WHERE id = OLD.collectionId)",
            "INSERT INTO deadline_revisions (collectionId,effectiveAt,appId,present) " +
                "VALUES (OLD.collectionId,$NOW,OLD.appId,0)")
        trigger("hide", "AFTER INSERT ON hidden_games", members("m.appId = NEW.appId"))
        trigger("unhide", "AFTER DELETE ON hidden_games", members("m.appId = OLD.appId"))
        trigger("estimate_insert", "AFTER INSERT ON hltb_data", members("m.appId = NEW.appId"))
        trigger("estimate_delete", "AFTER DELETE ON hltb_data", members("m.appId = OLD.appId"))
        trigger("estimate_update", "AFTER UPDATE ON hltb_data WHEN " +
            "OLD.matchStatus IS NOT NEW.matchStatus OR OLD.hltbId IS NOT NEW.hltbId OR " +
            "OLD.mainStoryMinutes IS NOT NEW.mainStoryMinutes OR OLD.mainExtraMinutes IS NOT NEW.mainExtraMinutes OR " +
            "OLD.completionistMinutes IS NOT NEW.completionistMinutes OR OLD.allStylesMinutes IS NOT NEW.allStylesMinutes",
            members("m.appId = NEW.appId"))
        // Dataset publication writes this marker last, after the mapping/length replacement.
        trigger("dataset_insert", "AFTER INSERT ON hltb_dataset_state", members("1"))
        trigger("dataset_update", "AFTER UPDATE ON hltb_dataset_state", members("1"))
    }
}
