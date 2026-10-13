package com.example.backlogium.domain

import com.example.backlogium.data.local.dao.PlaytimeRecoveryDao
import com.example.backlogium.data.local.entity.PlaytimeRecoveryJournal
import com.example.backlogium.data.local.entity.RecoveryJournalStatus
import com.example.backlogium.gamification.QuestMode
import com.example.backlogium.gamification.RuleConfig
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Which stages a recovery operation's derived finalization requires (recover-missed-playtime
 * design.md §8, tasks 5.5/5.6). Explicit flags, not inference: an earned-only operation must never
 * run a corrective baseline (that would swallow its fresh event), and a corrective-only operation
 * must never run an earned pass (that would celebrate recovered history).
 */
enum class RecoveryFinalizeKind {
    CORRECTIVE_ONLY,
    EARNED_ONLY,
    MIXED,
}

val RecoveryFinalizeKind.requiresCorrective: Boolean get() = this != RecoveryFinalizeKind.EARNED_ONLY
val RecoveryFinalizeKind.requiresEarned: Boolean get() = this != RecoveryFinalizeKind.CORRECTIVE_ONLY

/**
 * The durable identity of one fresh **earned contribution** — the exact cumulative range, baseline
 * epoch, revision and authorized minutes that the corrective pass must subtract (and the earned pass
 * must include). Keyed by its canonical contribution id, never by "this whole game": existing
 * historical, backfill and manual minutes for the same game stay in the corrective baseline.
 */
@Serializable
data class RecoveryContributionSnapshot(
    val account: String,
    val appId: Long,
    val baselineEpoch: Long,
    val fromMinutes: Long,
    val toMinutes: Long,
    val revision: Long,
    val authorizedMinutes: Long,
) {
    /** The canonical stable identity (account:appId:epoch:from:to). */
    fun identity(): String =
        "$account:$appId:$baselineEpoch:$fromMinutes:$toMinutes"
}

/**
 * One fresh earned achievement claim: identified by (app id, api name) — never a globally unique
 * api name alone, which is ambiguous across games — with its durable snapshot version and
 * pre-operation state so the corrective baseline can drop exactly this claim's XP.
 */
@Serializable
data class RecoveryAchievementSnapshot(
    val appId: Long,
    val apiName: String,
    /** Snapshot version of the achievement row at the raw commit, when the ledger keeps one. */
    val version: Long? = null,
    /** Whether the achievement was already unlocked before this operation. */
    val unlockedBefore: Boolean? = null,
    /** Rarity snapshot percent at the raw commit, for XP tier reconstruction. */
    val snapshotPercentBefore: Double? = null,
)

/**
 * The fresh earned inputs of one recovery operation: exact contribution identities (range + version
 * + quantity) and exact achievement claims (app id + api name + prior state). This is what the
 * corrective stage excludes and the earned stage includes — durable input identity, never a
 * re-derived list.
 */
@Serializable
data class RecoveryEarnedInputs(
    val contributions: Set<RecoveryContributionSnapshot> = emptySet(),
    val achievements: Set<RecoveryAchievementSnapshot> = emptySet(),
    /** Raw dated play/quest input earned by this operation, excluded only from its corrective pass. */
    val datedCredits: Set<RecoveryDatedCreditSnapshot> = emptySet(),
) {
    val isEmpty: Boolean get() = contributions.isEmpty() && achievements.isEmpty() && datedCredits.isEmpty()
    val contributionCount: Int get() = contributions.size
    val achievementCount: Int get() = achievements.size

    /** Exact per-game subtraction amounts, keyed by contribution identity. */
    val contributionMinutesByIdentity: Map<String, Long>
        get() = contributions.associate { it.identity() to it.authorizedMinutes }

    /** Per-game totals of the excluded contribution minutes (for the compute adapter). */
    val contributionMinutesByGame: Map<Long, Long>
        get() = contributions.groupBy { it.appId }
            .mapValues { (_, snaps) -> snaps.sumOf { it.authorizedMinutes } }

    /** The (appId, apiName) keys whose achievement XP the corrective pass must exclude. */
    val achievementKeys: Set<RecoveryAchievementKey>
        get() = achievements.mapTo(mutableSetOf()) { RecoveryAchievementKey(it.appId, it.apiName) }
}

/** Exact raw DailyProgress delta contributed by one fresh earned contribution on one frozen date. */
@Serializable
data class RecoveryDatedCreditSnapshot(
    val account: String,
    val appId: Long,
    val contributionId: String,
    val revision: Long,
    val date: String,
    val minutes: Int,
    val goalMinutes: Int,
) {
    init {
        require(account.isNotBlank() && appId > 0L && contributionId.isNotBlank() && revision > 0L)
        require(minutes >= 0 && goalMinutes in 0..minutes)
        require(runCatching { LocalDate.parse(date) }.isSuccess) { "dated credit requires ISO date '$date'" }
    }
}

/** (appId, apiName) pair — the only unambiguous achievement identity. */
data class RecoveryAchievementKey(val appId: Long, val apiName: String)

/**
 * Everything the staged derived finalization needs to resume after process death. Persisted in the
 * replacement journal ([PlaytimeRecoveryJournal.payloadJson]) as strict structured JSON at the raw
 * commit, so the raw operation and its derived finalization can never disagree about what was
 * fresh. [config] is the **captured** rule config that produced the raw commit — a later settings
 * change between stages must not silently re-base the corrective pass.
 */
data class RecoveryDerivedFinalizeInputs(
    val kind: RecoveryFinalizeKind,
    val operationId: String,
    val account: String,
    val evaluationDate: LocalDate,
    val configVersion: Long,
    val config: RuleConfig,
    val correctiveContributions: Set<RecoveryContributionSnapshot> = emptySet(),
    val earned: RecoveryEarnedInputs = RecoveryEarnedInputs(),
) {
    init {
        correctiveContributions.forEach { snapshot -> snapshot.requireCoherent("corrective") }
        earned.contributions.forEach { snapshot -> snapshot.requireCoherent("earned") }
        earned.achievements.forEach { snapshot ->
            require(snapshot.appId > 0L && snapshot.apiName.isNotBlank()) {
                "earned achievement identity must include a positive appId and nonblank apiName"
            }
            require(snapshot.snapshotPercentBefore == null ||
                snapshot.snapshotPercentBefore.isFinite() && snapshot.snapshotPercentBefore in 0.0..100.0
            ) { "achievement rarity snapshot must be finite and within 0..100" }
        }
        val earnedByIdentity = earned.contributions.associateBy { it.identity() }
        val datedByContribution = mutableMapOf<String, Long>()
        earned.datedCredits.forEach { snapshot ->
            require(snapshot.account == account) { "earned dated credit belongs to another account" }
            val owner = earnedByIdentity[snapshot.contributionId]
                ?: error("earned dated credit has no fresh contribution ${snapshot.contributionId}")
            require(owner.appId == snapshot.appId && owner.revision == snapshot.revision) {
                "earned dated credit identity/revision does not match its contribution"
            }
            datedByContribution[snapshot.contributionId] = Math.addExact(
                datedByContribution[snapshot.contributionId] ?: 0L,
                snapshot.minutes.toLong(),
            )
        }
        datedByContribution.forEach { (id, minutes) ->
            val authorized = requireNotNull(earnedByIdentity[id]).authorizedMinutes
            require(minutes <= authorized) { "earned dated credit $minutes exceeds contribution $id ($authorized)" }
        }
        require(operationId.isNotBlank() && account.isNotBlank()) {
            "finalization operation and account identities must be nonblank"
        }
        require(configVersion >= 0L) { "captured config version must not be negative" }
        // Structural consistency: a corrective-only operation has no earned inputs, an earned-only
        // operation has no corrective contributions, and the payload's operation identity is the
        // journal's (enforced again on decode).
        when (kind) {
            RecoveryFinalizeKind.CORRECTIVE_ONLY -> require(earned.isEmpty) {
                "corrective-only operation carries earned inputs: $earned"
            }
            RecoveryFinalizeKind.EARNED_ONLY -> require(correctiveContributions.isEmpty()) {
                "earned-only operation carries corrective contributions: $correctiveContributions"
            }
            RecoveryFinalizeKind.MIXED -> {
                require(correctiveContributions.isNotEmpty() && !earned.isEmpty) {
                    "mixed operation needs both corrective contributions and earned inputs"
                }
            }
        }
    }

    companion object {
        private val json: Json = Json { ignoreUnknownKeys = false }

        /**
         * Encode as strict structured JSON with an explicit schema version, so an unknown future
         * schema is rejected rather than silently mis-decoded.
         */
        fun encode(inputs: RecoveryDerivedFinalizeInputs): String =
            json.encodeToString(
                RecoveryFinalizePayloadDto(
                    version = PAYLOAD_VERSION,
                    kind = inputs.kind,
                    operationId = inputs.operationId,
                    account = inputs.account,
                    evaluationDate = inputs.evaluationDate.toString(),
                    configVersion = inputs.configVersion,
                    config = inputs.config.toDto(),
                    corrective = inputs.correctiveContributions.toList(),
                    earned = inputs.earned,
                ),
            )

        /**
         * Decode [payload] only when it is a finalization description for [expectedOperationId].
         * Raw journals that merely commit raw state without a finalize payload, or a payload
         * bearing another operation's identity, are rejected rather than blindly driving stages.
         */
        fun fromJson(payload: String, expectedOperationId: String): RecoveryDerivedFinalizeInputs {
            val dto = json.decodeFromString<RecoveryFinalizePayloadDto>(payload)
            require(dto.version == PAYLOAD_VERSION) {
                "unsupported finalize payload version ${dto.version}; expected $PAYLOAD_VERSION"
            }
            require(dto.operationId == expectedOperationId) {
                "finalize payload for '${dto.operationId}' does not match journal '$expectedOperationId'"
            }
            require(dto.kind == RecoveryFinalizeKind.CORRECTIVE_ONLY ||
                dto.kind == RecoveryFinalizeKind.EARNED_ONLY ||
                dto.kind == RecoveryFinalizeKind.MIXED) {
                "unknown finalize kind ${dto.kind}"
            }
            return RecoveryDerivedFinalizeInputs(
                kind = dto.kind,
                operationId = dto.operationId,
                account = dto.account,
                evaluationDate = LocalDate.parse(dto.evaluationDate),
                configVersion = dto.configVersion,
                config = dto.config.toRuleConfig(),
                correctiveContributions = dto.corrective.toSet(),
                earned = dto.earned,
            )
        }

        /**
         * Decode a journal's own finalize payload, with the journal's operation identity enforced.
         * A raw journal without a finalize payload is rejected, never blindly resumed.
         */
        fun fromJournal(journal: PlaytimeRecoveryJournal): RecoveryDerivedFinalizeInputs =
            fromJson(
                payload = journal.payloadJson ?: error(
                    "journal ${journal.operationId} at ${journal.derivedStatus} has no finalize payload",
                ),
                expectedOperationId = journal.operationId,
            )

        private const val PAYLOAD_VERSION = 1
    }
}

private fun RecoveryContributionSnapshot.requireCoherent(label: String) {
    require(account.isNotBlank() && appId > 0L && baselineEpoch > 0L) {
        "$label contribution has invalid account/app/epoch identity"
    }
    require(fromMinutes >= 0L && toMinutes > fromMinutes) { "$label contribution has invalid cumulative range" }
    require(toMinutes - fromMinutes == authorizedMinutes && authorizedMinutes > 0L) {
        "$label contribution authorized quantity must equal its positive range width"
    }
    require(revision > 0L) { "$label contribution revision must be positive" }
}

/**
 * The staged corrective-then-earned derived finalization for a recovery commit
 * (recover-missed-playtime, design.md §8, tasks 5.5/5.6).
 *
 * The kind of the journal's operation decides which stages run: corrective-only reseeds the marks
 * under [RecomputeSource.RETROACTIVE_PLAY] and stops; earned-only is an ordinary `SYNC` persist;
 * mixed runs corrective first (excluding the operation's exact earned inputs) and then the earned
 * pass. Under the existing derived-write lock and the existing persist/recovery protocol, with
 * every stage sequenced from the journal's durable state and the write-ahead record attributed to
 * the operation id, a process death between any two writes resumes at exactly the next unfinished
 * stage — never re-persisting a finished stage, never skipping an unfinished one, and never
 * re-deriving an already-acknowledged transition into a duplicate.
 *
 * The operation's rule config, contribution revisions, quantity and achievement identities are
 * captured in the payload; staged recomputes use those captured inputs rather than reading
 * "whatever is current" (a settings change or an unrelated new sync between stages cannot silently
 * re-base the corrective pass). This class never recomputes XP itself — every stage delegates to
 * [RecoveryDerivedCompute], the single author on device.
 */
class RecoveryDerivedFinalizer @Inject constructor(
    private val recoveryDao: PlaytimeRecoveryDao,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
    private val compute: RecoveryDerivedCompute,
) {

    /** Resume every unfinished finalization for [account], oldest first. */
    suspend fun resumePending(account: String, now: Long) {
        recoveryDao.pendingFinalization(account)
            .sortedBy { it.updatedAt }
            .forEach { journal ->
                val inputs = decodeFor(journal)
                finalize(inputs, now)
            }
    }

    /** Run (or resume) [inputs]'s staged finalization. Idempotent at [RecoveryJournalStatus.FINALIZED]. */
    suspend fun finalize(inputs: RecoveryDerivedFinalizeInputs, now: Long) {
        derivedStateWrites.withLock {
            val journal = recoveryDao.journal(inputs.operationId) ?: return@withLock
            when (journal.derivedStatus) {
                RecoveryJournalStatus.FINALIZED -> Unit
                RecoveryJournalStatus.CORRECTIVE_DONE -> {
                    if (inputs.kind.requiresEarned) {
                        runEarnedStage(inputs, journal, now)
                    } else {
                        markFinalized(inputs.operationId, now)
                    }
                }
                RecoveryJournalStatus.EARNED_DONE -> markFinalized(inputs.operationId, now)
                else -> {
                    // RAW_COMMITTED (or an unknown state, treated as raw): run the needed stages in
                    // order.
                    if (inputs.kind.requiresCorrective) {
                        runCorrectiveStage(inputs, now)
                    }
                    if (inputs.kind.requiresEarned) {
                        val afterCorrective = recoveryDao.journal(inputs.operationId) ?: journal
                        runEarnedStage(inputs, afterCorrective, now)
                    } else {
                        markFinalized(inputs.operationId, now)
                    }
                }
            }
        }
    }

    /** The corrective baseline pass, excluding this operation's fresh earned inputs. */
    private suspend fun runCorrectiveStage(inputs: RecoveryDerivedFinalizeInputs, now: Long) {
        val corrective = compute.computeCorrective(inputs.evaluationDate, inputs.config, inputs.earned)
        compute.persist(
            corrective,
            RecomputeSource.RETROACTIVE_PLAY,
            inputs.configVersion,
            inputs.operationId,
        )
        recoveryDao.markJournalStatus(inputs.operationId, RecoveryJournalStatus.CORRECTIVE_DONE, now)
    }

    /** The earned pass including every fresh input, persisted under SYNC. */
    private suspend fun runEarnedStage(
        inputs: RecoveryDerivedFinalizeInputs,
        journal: PlaytimeRecoveryJournal,
        now: Long,
    ) {
        if (journal.derivedStatus != RecoveryJournalStatus.EARNED_DONE) {
            val earned = compute.computeEarned(inputs.evaluationDate, inputs.config)
            compute.persist(earned, RecomputeSource.SYNC, inputs.configVersion, inputs.operationId)
            recoveryDao.markJournalStatus(inputs.operationId, RecoveryJournalStatus.EARNED_DONE, now)
        }
        markFinalized(inputs.operationId, now)
    }

    private suspend fun markFinalized(operationId: String, now: Long) {
        recoveryDao.markJournalStatus(operationId, RecoveryJournalStatus.FINALIZED, now)
    }

    private fun decodeFor(journal: PlaytimeRecoveryJournal): RecoveryDerivedFinalizeInputs =
        RecoveryDerivedFinalizeInputs.fromJson(
            payload = journal.payloadJson ?: error(
                "journal ${journal.operationId} at ${journal.derivedStatus} has no finalize payload",
            ),
            expectedOperationId = journal.operationId,
        )
}

// ----------------------------------------------------------------------------- serializable DTOs

/** Wire format of [RecoveryDerivedFinalizeInputs]; schema-versioned, strict. */
@Serializable
internal data class RecoveryFinalizePayloadDto(
    val version: Int,
    val kind: RecoveryFinalizeKind,
    val operationId: String,
    val account: String,
    val evaluationDate: String,
    val configVersion: Long,
    val config: RecoveryRuleConfigDto,
    val corrective: List<RecoveryContributionSnapshot> = emptyList(),
    val earned: RecoveryEarnedInputs = RecoveryEarnedInputs(),
)

/** Durable mirror of the engine's [RuleConfig], captured at the raw commit. */
@Serializable
internal data class RecoveryRuleConfigDto(
    val xpPerMinute: Int,
    val levelBase: Int,
    val questThresholdMin: Int,
    val questMode: QuestMode,
    val streakGraceDays: Int,
    val commonAchievementXp: Int,
    val uncommonAchievementXp: Int,
    val rareAchievementXp: Int,
    val epicAchievementXp: Int,
    val legendaryAchievementXp: Int,
)

internal fun RuleConfig.toDto(): RecoveryRuleConfigDto = RecoveryRuleConfigDto(
    xpPerMinute = xpPerMinute,
    levelBase = levelBase,
    questThresholdMin = questThresholdMin,
    questMode = questMode,
    streakGraceDays = streakGraceDays,
    commonAchievementXp = commonAchievementXp,
    uncommonAchievementXp = uncommonAchievementXp,
    rareAchievementXp = rareAchievementXp,
    epicAchievementXp = epicAchievementXp,
    legendaryAchievementXp = legendaryAchievementXp,
)

internal fun RecoveryRuleConfigDto.toRuleConfig(): RuleConfig = RuleConfig(
    xpPerMinute = xpPerMinute,
    levelBase = levelBase,
    questThresholdMin = questThresholdMin,
    questMode = questMode,
    streakGraceDays = streakGraceDays,
    commonAchievementXp = commonAchievementXp,
    uncommonAchievementXp = uncommonAchievementXp,
    rareAchievementXp = rareAchievementXp,
    epicAchievementXp = epicAchievementXp,
    legendaryAchievementXp = legendaryAchievementXp,
)
