package com.example.backlogium.domain.gapplan

import androidx.room.Room
import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameGenreCache
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.SteamReviewCache
import com.example.backlogium.data.repo.CollectionRepository
import com.example.backlogium.data.repo.GameCategory
import com.example.backlogium.data.repo.GameCategoryCodec
import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.GameGenreCodec
import com.example.backlogium.data.repo.GameGenreRepository
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.data.repo.HltbDatasetLookup
import com.example.backlogium.data.repo.HltbRepository
import com.example.backlogium.data.repo.OfflineHltbSource
import com.example.backlogium.data.repo.OfflineSteamApiDouble
import com.example.backlogium.data.repo.OfflineStoreApi
import com.example.backlogium.data.repo.PersonalPaceRepository
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.data.repo.SteamReviewRepository
import com.example.backlogium.data.repo.SteamStoreGenreDataSource
import com.example.backlogium.data.repo.SteamStoreReviewDataSource
import com.example.backlogium.domain.CurrentDateProvider
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.TimeProvider
import kotlinx.serialization.json.Json
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId

/**
 * A real gap-plan stack over an in-memory database, with every network path broken.
 *
 * Shared rather than duplicated because the feed and the ViewModel have to be tested against the
 * *same* wiring: a divergence between two hand-built stacks would let one of them pass while the
 * behaviour the other depends on was already broken. Every data source here fails on contact, so
 * anything these tests observe came from local state — which is the property the whole feature
 * claims.
 *
 * [today] is mutable so a local-midnight rollover is a one-line change rather than a rebuild.
 *
 * [transaction] defaults to the real Room scope, so writes go through the same single-commit
 * boundary the app uses. Room resumes on its own executor, which a virtual test scheduler does not
 * control, so a caller must await the resulting state rather than assume `advanceUntilIdle` settled
 * it — `runTest` waits in real time for a coroutine suspended on something outside its scheduler.
 */
internal class GapPlanTestEnvironment(
    val zone: ZoneId = ZoneId.of("UTC"),
    var today: LocalDate = TODAY,
    transaction: DatabaseTransactionScope? = null,
) {
    val db: BacklogiumDatabase = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val time = object : TimeProvider {
        override fun nowMillis(): Long =
            today.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()

        override fun zone(): ZoneId = zone
        override fun today(): LocalDate = today
    }

    private val hidden = HiddenGamesRepository(
        hiddenGameDao = db.hiddenGameDao(),
        gameDao = db.gameDao(),
        storeCacheDao = db.gameGenreCacheDao(),
        time = time,
    )

    val sessions = SessionRepository(db.sessionDao(), hidden)

    private val genreRepository = GameGenreRepository(
        cacheDao = db.gameGenreCacheDao(),
        store = SteamStoreGenreDataSource(OfflineStoreApi),
        time = time,
    )

    val feed = GapPlanFeed(
        gameRepository = GameRepository(
            gameDao = db.gameDao(),
            hltbRepository = HltbRepository(
                dataSource = OfflineHltbSource,
                hltbDataDao = db.hltbDataDao(),
                datasetLookup = HltbDatasetLookup { null },
                hiddenGameDao = db.hiddenGameDao(),
                json = Json,
                time = time,
            ),
            gameGenreRepository = genreRepository,
            hiddenGamesRepository = hidden,
            steamApi = OfflineSteamApiDouble,
            sessionRepository = sessions,
            time = time,
        ),
        sessionRepository = sessions,
        paceRepository = PersonalPaceRepository(sessions, time),
        genreRepository = genreRepository,
        reviewRepository = SteamReviewRepository(
            cacheDao = db.steamReviewCacheDao(),
            store = SteamStoreReviewDataSource(OfflineStoreApi),
            time = time,
        ),
        currentDate = CurrentDateProvider(time),
    )

    val collectionRepository = CollectionRepository(
        collectionDao = db.collectionDao(),
        hiddenGamesRepository = hidden,
        time = time,
        transaction = transaction ?: RoomDatabaseTransactionScope(db),
    )

    val collectionCreator = GapPlanCollectionCreator(collectionRepository)

    fun close() = db.close()

    suspend fun addGame(
        appId: Long,
        name: String = "Game $appId",
        source: GameSource = GameSource.STEAM_OWNED,
        playtimeForever: Int = 0,
        manualSharedMinutes: Int = 0,
    ) = db.gameDao().upsert(
        Game(
            appId = appId,
            name = name,
            iconUrl = "icon-$appId",
            playtimeForever = playtimeForever,
            playtime2Weeks = 0,
            lastPlaytime = 0,
            source = source,
            manualSharedMinutes = manualSharedMinutes,
        ),
    )

    suspend fun addHltb(appId: Long, mainStory: Int?, completionist: Int? = mainStory) =
        db.hltbDataDao().upsert(
            HltbData(
                appId = appId,
                hltbId = appId,
                mainStoryMinutes = mainStory,
                mainExtraMinutes = null,
                completionistMinutes = completionist,
                allStylesMinutes = null,
                fetchedAt = 1,
                matchStatus = HltbMatchStatus.RESOLVED,
                candidatesJson = null,
            ),
        )

    suspend fun addStoreMetadata(
        appId: Long,
        genres: List<GameGenre> = emptyList(),
        categories: List<GameCategory>? = emptyList(),
    ) = db.gameGenreCacheDao().upsert(
        GameGenreCache(
            appId = appId,
            genresJson = GameGenreCodec.encode(genres),
            checkedAt = 1,
            categoriesJson = categories?.let(GameCategoryCodec::encode),
        ),
    )

    suspend fun addReview(appId: Long, description: String, positive: Int, negative: Int) =
        db.steamReviewCacheDao().upsert(
            SteamReviewCache(
                appId = appId,
                description = description,
                positive = positive,
                negative = negative,
                total = positive + negative,
                available = true,
                checkedAt = 1,
            ),
        )

    suspend fun addSession(appId: Long, daysAgo: Int, minutes: Int) {
        val startAt = today.minusDays(daysAgo.toLong())
            .atStartOfDay(zone)
            .plusHours(12)
            .toInstant()
            .toEpochMilli()
        db.sessionDao().insert(
            Session(appId = appId, startAt = startAt, endAt = startAt + 1, minutes = minutes, open = false),
        )
    }

    /** Enough tracked history for a reliable Personal Pace profile. */
    suspend fun seedReliablePace(appId: Long, days: Int = 40, minutesPerDay: Int = 120) =
        (1..days).forEach { addSession(appId, daysAgo = it, minutes = minutesPerDay) }
}
