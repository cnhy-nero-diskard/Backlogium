package com.example.backlogium.domain

import com.example.backlogium.data.local.AcquiredGamesAnnouncement
import com.example.backlogium.data.local.AutoSnapshotSettings
import com.example.backlogium.data.local.LiveSessionState
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.data.repo.CloudRoutinePolicy
import com.example.backlogium.data.repo.CloudRoutineState
import com.example.backlogium.data.repo.CloudRoutineAdmission
import com.example.backlogium.data.repo.CloudReadSummary
import com.example.backlogium.data.repo.CloudReadSummaryOutcome
import com.example.backlogium.data.repo.CloudReadTrigger
import com.example.backlogium.data.repo.CloudReadFailure
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory stand-in for the DataStore-backed implementation. */
internal class FakeSettingsRepository : SettingsRepository {
    private val versioned = MutableStateFlow(VersionedRuleConfig(RuleConfig(), 0L))
    val stored: RuleConfig get() = versioned.value.config
    val version: Long get() = versioned.value.version
    override suspend fun setRuleConfig(config: RuleConfig) {
        setRuleConfigAndGetVersion(config)
    }
    override val ruleConfig: Flow<RuleConfig> = versioned.map { it.config }
    override val ruleConfigWithVersion: Flow<VersionedRuleConfig> = versioned
    override suspend fun setRuleConfigAndGetVersion(config: RuleConfig): VersionedRuleConfig {
        val result = VersionedRuleConfig(config, versioned.value.version + 1L)
        versioned.value = result
        return result
    }

    // The Library sort selections share this store but are irrelevant to a rule change.
    private val sort = MutableStateFlow(LibrarySortPrefs())
    override val librarySort: Flow<LibrarySortPrefs> = sort
    override suspend fun setFocusSort(key: LibrarySortKey) {
        sort.value = sort.value.copy(focus = key)
    }

    override suspend fun setLibrarySort(key: LibrarySortKey) {
        sort.value = sort.value.copy(library = key)
    }

    override suspend fun setFocusSortDirection(direction: LibrarySortDirection) {
        sort.value = sort.value.copy(focusDirection = direction)
    }

    override suspend fun setLibrarySortDirection(direction: LibrarySortDirection) {
        sort.value = sort.value.copy(libraryDirection = direction)
    }

    override val libraryDensity: Flow<GameListDensity> = MutableStateFlow(GameListDensity.LIST)
    override suspend fun setLibraryDensity(density: GameListDensity) = Unit
    override val collectionDensity: Flow<GameListDensity> = MutableStateFlow(GameListDensity.LIST)
    override suspend fun setCollectionDensity(density: GameListDensity) = Unit

    // Auto-snapshot configuration (add-backup-restore) is irrelevant to a rule change.
    private val autoSnapshot = MutableStateFlow(AutoSnapshotSettings())
    override val autoSnapshotSettings: Flow<AutoSnapshotSettings> = autoSnapshot
    override suspend fun setAutoSnapshotEnabled(enabled: Boolean) {
        autoSnapshot.value = autoSnapshot.value.copy(enabled = enabled)
    }

    override suspend fun setSnapshotRetentionCount(count: Int) {
        autoSnapshot.value = autoSnapshot.value.copy(retentionCount = count)
    }

    override suspend fun setSnapshotIntervalHours(hours: Int) {
        autoSnapshot.value = autoSnapshot.value.copy(intervalHours = hours)
    }

    private val cloudPosition = MutableStateFlow<String?>(null)
    var failNextCloudReadPosition = false
    override val cloudReadPosition: Flow<String?> = cloudPosition
    override suspend fun setCloudReadPosition(position: String) {
        if (failNextCloudReadPosition) {
            failNextCloudReadPosition = false
            error("simulated cursor persistence failure")
        }
        cloudPosition.value = position
    }
    override suspend fun clearCloudReadPosition() {
        cloudPosition.value = null
    }

    private val readerGeneration = MutableStateFlow(0L)
    override val cloudReaderGeneration: Flow<Long> = readerGeneration

    private val promotionTarget = MutableStateFlow<Long?>(null)
    var failNextPromotionMark = false
    var failNextPromotionFinish = false
    var failNextPromotionAbandon = false
    override val cloudReaderPromotionTarget: Flow<Long?> = promotionTarget
    override suspend fun markCloudReaderPromotion(target: Long) {
        if (failNextPromotionMark) {
            failNextPromotionMark = false
            error("simulated promotion marker failure")
        }
        promotionTarget.value = target
    }
    override suspend fun finishCloudReaderPromotion(): Long? {
        if (failNextPromotionFinish) {
            failNextPromotionFinish = false
            error("simulated promotion finish failure")
        }
        val target = promotionTarget.value ?: return null
        readerGeneration.value = target
        // Mirrors SettingsDataStore: the marker survives the generation commit and is removed
        // by clearCloudReaderPromotion once the post-commit cleanup has run, so a death between
        // the two still leaves a recoverable promotion.
        return target
    }
    override suspend fun clearCloudReaderPromotion() {
        promotionTarget.value = null
    }
    override suspend fun abandonCloudReaderPromotion(): Long {
        if (failNextPromotionAbandon) {
            failNextPromotionAbandon = false
            error("simulated removal fence failure")
        }
        // Mirrors SettingsDataStore: one edit advances the generation past any marked target
        // and clears the marker, so a death cannot leave a marker behind a newer generation.
        val next = maxOf(readerGeneration.value + 1L, (promotionTarget.value ?: 0L) + 1L)
        readerGeneration.value = next
        promotionTarget.value = null
        return next
    }

    private val removalTarget = MutableStateFlow<Long?>(null)
    override val cloudReaderRemovalTarget: Flow<Long?> = removalTarget
    override suspend fun markCloudReaderRemoval(): Long {
        // Mirrors SettingsDataStore: records the same fence target abandon would compute, so
        // recovery can skip the fence when the interrupted removal already ran it.
        val target = maxOf(readerGeneration.value + 1L, (promotionTarget.value ?: 0L) + 1L)
        removalTarget.value = target
        return target
    }
    override suspend fun clearCloudReaderRemoval() {
        removalTarget.value = null
    }

    /** Simulates the pre-atomic fencing edit that advanced the generation without clearing the marker. */
    fun simulateLegacyGenerationAdvance(): Long = ++readerGeneration.value

    private val routine = MutableStateFlow(CloudRoutineState())
    override val cloudRoutineState: Flow<CloudRoutineState> = routine
    override suspend fun initializeCloudRoutinePolicy(): CloudRoutineState {
        if (routine.value.policy == null) {
            routine.value = CloudRoutineState(
                policy = CloudRoutinePolicy.AUTOMATIC,
                orderingWatermark = routine.value.orderingWatermark + 1,
                lastAdmissionWatermark = routine.value.orderingWatermark + 1,
            )
        }
        return routine.value
    }
    override suspend fun setCloudRoutinePolicy(policy: CloudRoutinePolicy) {
        if (routine.value.policy != null) routine.value = routine.value.copy(policy = policy)
    }
    override suspend fun clearCloudRoutinePolicy() { routine.value = CloudRoutineState() }
    override suspend fun recordCloudRoutineAdmission(at: Long): CloudRoutineState {
        check(routine.value.policy != null)
        val next = routine.value.orderingWatermark + 1
        routine.value = routine.value.copy(
            lastAdmittedAt = at, orderingWatermark = next, lastAdmissionWatermark = next,
        )
        return routine.value
    }
    override suspend fun recordCloudOtherRead(terminal: Boolean) {
        if (routine.value.policy == null) return
        val next = routine.value.orderingWatermark + 1
        routine.value = routine.value.copy(
            orderingWatermark = next, latestOtherReadWatermark = next,
            latestOtherReadTerminal = terminal,
        )
    }
    override suspend fun admitCloudRoutine(at: Long): CloudRoutineAdmission {
        val state = routine.value
        val policy = state.policy ?: return CloudRoutineAdmission.UNAVAILABLE
        if (state.lastAdmittedAt != null &&
            at - state.lastAdmittedAt < policy.minimumGapHours * 3_600_000L
        ) return CloudRoutineAdmission.COOLDOWN
        if (state.latestOtherReadTerminal &&
            state.latestOtherReadWatermark > state.lastAdmissionWatermark &&
            state.latestOtherReadWatermark > state.consumedOtherReadWatermark
        ) {
            routine.value = state.copy(consumedOtherReadWatermark = state.latestOtherReadWatermark)
            return CloudRoutineAdmission.SATISFIED_BY_READ
        }
        recordCloudRoutineAdmission(at)
        return CloudRoutineAdmission.ADMITTED
    }

    private val readSummaryState = MutableStateFlow(CloudReadSummary())
    override val cloudReadSummary: Flow<CloudReadSummary> = readSummaryState
    override suspend fun recordCloudReadSummary(
        at: Long, trigger: CloudReadTrigger, outcome: CloudReadSummaryOutcome,
        failure: CloudReadFailure?, observedAt: Long?, hasMore: Boolean?,
        windowStart: Long?, windowEnd: Long?,
    ) {
        val prior = readSummaryState.value
        readSummaryState.value = prior.copy(
            lastAttemptAt = at, lastTrigger = trigger, lastOutcome = outcome,
            lastFailure = failure,
            lastSuccessAt = if (outcome == CloudReadSummaryOutcome.FAILED) prior.lastSuccessAt else at,
            latestObservationAt = listOfNotNull(prior.latestObservationAt, observedAt).maxOrNull(),
            lastSuccessHasMore = if (outcome == CloudReadSummaryOutcome.FAILED) prior.lastSuccessHasMore else hasMore,
            lastSuccessWindowStart = if (outcome == CloudReadSummaryOutcome.FAILED) prior.lastSuccessWindowStart else windowStart,
            lastSuccessWindowEnd = if (outcome == CloudReadSummaryOutcome.FAILED) prior.lastSuccessWindowEnd else windowEnd,
        )
    }
    override suspend fun clearCloudReadSummary() { readSummaryState.value = CloudReadSummary() }

    private val cloudIngestCursor = MutableStateFlow<String?>(null)
    override val cloudIngestPosition: Flow<String?> = cloudIngestCursor
    override suspend fun setCloudIngestPosition(position: String) {
        cloudIngestCursor.value = position
    }
    override suspend fun clearCloudIngestPosition() {
        cloudIngestCursor.value = null
    }

    // Live now-playing session (enhance-now-playing) is irrelevant to a rule change.
    private val session = MutableStateFlow(LiveSessionState())
    override val liveSession: Flow<LiveSessionState> = session
    override suspend fun setLiveSession(appId: Long?, startedAt: Long) {
        session.value = LiveSessionState(appId, startedAt)
    }

    override val notificationPermissionRequested: Flow<Boolean> = MutableStateFlow(true)
    override suspend fun setNotificationPermissionRequested() = Unit

    private val liveMonitor = MutableStateFlow(false)
    override val liveMonitorEnabled: Flow<Boolean> = liveMonitor
    override val acquiredGames: Flow<AcquiredGamesAnnouncement> =
        MutableStateFlow(AcquiredGamesAnnouncement())
    override suspend fun setAcquiredGamesDismissed() = Unit
    override suspend fun setLiveMonitorEnabled(enabled: Boolean) {
        liveMonitor.value = enabled
    }

    override suspend fun clearLiveSession() {
        session.value = LiveSessionState()
    }
}
