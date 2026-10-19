package com.example.backlogium.data.backup

import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryClockStates
import com.example.backlogium.data.local.entity.RecoveryEvidenceQuality
import com.example.backlogium.data.local.entity.RecoveryJournalStatus
import com.example.backlogium.data.local.entity.RecoveryProvenance
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pure, database-free preflight for the version 3 recovery section (recover-missed-playtime,
 * design.md Decision 10 and `backup-restore` spec "Recovery backups preserve uncertainty and
 * account-safe baselines").
 *
 * It checks every category the merge would otherwise discover mid-write: malformed dates/zones,
 * implausible or overflowing quantities, inconsistent `authorized = width` arithmetic, duplicate
 * conflicting identities, dangling contribution references, revision links ahead of their
 * contribution, and — the load-bearing one — that each funded graph's placements conserve its
 * authorized quantity exactly. A violation is reported, never normalized, so the merge never has
 * to reason about a half-valid graph.
 */
internal object BackupRecoveryValidator {

    private val EARLIEST_MILLIS =
        BackupValidator.EARLIEST_PLAUSIBLE_DATE.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val LATEST_MILLIS =
        BackupValidator.LATEST_PLAUSIBLE_DATE.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun validate(file: BackupFile, problems: MutableList<BackupValidationProblem>) {
        val recovery = file.recovery
        if (recovery == null) {
            problems += BackupValidationProblem("recovery", 0, "v3 requires an explicit recovery section")
            return
        }
        section("observations", recovery.observations, problems)
        section("contributions", recovery.contributions, problems)
        section("timedPlacements", recovery.timedPlacements, problems)
        section("unknownAllocations", recovery.unknownAllocations, problems)
        section("recipes", recovery.recipes, problems)
        section("replacements", recovery.replacements, problems)

        val contributions = recovery.contributions.orEmpty()
        val contributionsById = contributions.associateBy { it.contributionId }
        val timed = recovery.timedPlacements.orEmpty()
        val allocations = recovery.unknownAllocations.orEmpty()
        val recipes = recovery.recipes.orEmpty()
        val recipesById = recipes.associateBy { it.contributionId }

        validateObservations(recovery.observations.orEmpty(), problems)
        validateContributions(contributions, problems)
        validateTimedPlacements(timed, contributionsById, problems)
        val replacedPlacementIds = recovery.replacements.orEmpty()
            .flatMap { it.replacedPlacementIdsCsv.orEmpty().split(',') }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        validateTimedSessionLinks(
            placements = timed.filterNot { it.superseded || it.placementId in replacedPlacementIds },
            sessions = file.sessions,
            problems = problems,
        )
        validateAllocations(allocations, contributionsById, problems)
        validateRecipes(recipes, contributionsById, problems)
        validateReplacements(recovery.replacements.orEmpty(), contributionsById, problems)
        validateConservation(contributions, timed, allocations, recipesById, problems)
    }

    private fun section(name: String, value: Any?, problems: MutableList<BackupValidationProblem>) {
        if (value == null) {
            problems += BackupValidationProblem("recovery", 0, "v3 requires the $name section")
        }
    }

    private fun validateObservations(
        observations: List<BackupRecoveryObservation>,
        problems: MutableList<BackupValidationProblem>,
    ) {
        observations.forEachIndexed { index, observation ->
            if (observation.appId <= 0L) {
                problems += problem("recoveryObservation", index, "malformed appId ${observation.appId}")
            }
            if (observation.account.isBlank()) {
                problems += problem("recoveryObservation", index, "blank account")
            }
            if (observation.baselineEpoch <= 0L) {
                problems += problem("recoveryObservation", index, "non-positive baselineEpoch")
            }
            if (observation.highWaterMinutes < 0L) {
                problems += problem("recoveryObservation", index, "negative highWaterMinutes")
            }
            if (observation.clockState !in CLOCK_STATES) {
                problems += problem("recoveryObservation", index, "unknown clockState ${observation.clockState}")
            }
            observation.lastObservationAt?.let { instant ->
                if (instant.epochMilliOrNull()?.inRange() != true) {
                    problems += problem("recoveryObservation", index, "unusable lastObservationAt '$instant'")
                }
            }
            observation.lastObservationDate?.let { date ->
                if (!isPlausibleDate(date)) {
                    problems += problem("recoveryObservation", index, "unusable lastObservationDate '$date'")
                }
            }
            observation.lastObservationZone?.let { zone ->
                if (!isZone(zone)) {
                    problems += problem("recoveryObservation", index, "invalid zone '$zone'")
                }
            }
        }
        observations.groupBy { it.account to it.appId }
            .filterValues { it.size > 1 }
            .forEach { (key, duplicates) ->
                problems += problem(
                    "recoveryObservation",
                    observations.indexOf(duplicates.first()),
                    "duplicate observation for account ${key.first} app ${key.second}",
                )
            }
    }

    private fun validateContributions(
        contributions: List<BackupContribution>,
        problems: MutableList<BackupValidationProblem>,
    ) {
        contributions.forEachIndexed { index, contribution ->
            if (contribution.contributionId.isBlank()) {
                problems += problem("recoveryContribution", index, "blank contributionId")
            }
            if (contribution.account.isBlank()) {
                problems += problem("recoveryContribution", index, "blank account")
            }
            if (contribution.appId <= 0L) {
                problems += problem("recoveryContribution", index, "malformed appId ${contribution.appId}")
            }
            if (contribution.baselineEpoch <= 0L) {
                problems += problem("recoveryContribution", index, "non-positive baselineEpoch")
            }
            if (contribution.fromMinutes < 0L || contribution.toMinutes <= contribution.fromMinutes) {
                problems += problem(
                    "recoveryContribution", index,
                    "invalid cumulative range (${contribution.fromMinutes}, ${contribution.toMinutes}]",
                )
            } else {
                val width = contribution.toMinutes - contribution.fromMinutes
                if (contribution.authorizedMinutes != width) {
                    problems += problem(
                        "recoveryContribution", index,
                        "authorizedMinutes ${contribution.authorizedMinutes} does not match range width $width",
                    )
                }
                if (contribution.toMinutes > MAX_PLAUSIBLE_CUMULATIVE_MINUTES) {
                    problems += problem(
                        "recoveryContribution", index,
                        "cumulative minutes ${contribution.toMinutes} exceed plausibility bound",
                    )
                }
            }
            if (contribution.revision < 1L) {
                problems += problem("recoveryContribution", index, "revision must be positive")
            }
            if (contribution.provenance !in PROVENANCES) {
                problems += problem("recoveryContribution", index, "unknown provenance ${contribution.provenance}")
            }
            val windowEndMillis = contribution.windowEndAt.epochMilliOrNull()
            if (windowEndMillis?.inRange() != true) {
                problems += problem(
                    "recoveryContribution", index,
                    "unusable windowEndAt '${contribution.windowEndAt}'",
                )
            }
            contribution.windowStartAt?.let { start ->
                val startMillis = start.epochMilliOrNull()
                val endMillis = contribution.windowEndAt.epochMilliOrNull()
                when {
                    startMillis?.inRange() != true ->
                        problems += problem("recoveryContribution", index, "unusable windowStartAt '$start'")
                    endMillis != null && startMillis != null && startMillis > endMillis ->
                        problems += problem("recoveryContribution", index, "windowStartAt precedes windowEndAt")
                }
            }
            contribution.zone?.let { zone ->
                if (!isZone(zone)) problems += problem("recoveryContribution", index, "invalid zone '$zone'")
            }
            contribution.observedZone?.let { zone ->
                if (!isZone(zone)) problems += problem("recoveryContribution", index, "invalid observedZone '$zone'")
            }
        }

        contributions.groupBy { it.contributionId }
            .filterValues { it.size > 1 }
            .forEach { (id, duplicates) ->
                if (duplicates.distinct().size > 1) {
                    problems += problem(
                        "recoveryContribution",
                        contributions.indexOf(duplicates.first()),
                        "conflicting duplicate contributionId $id",
                    )
                }
            }

        contributions.groupBy { Triple(it.account, it.appId, it.baselineEpoch) }
            .forEach { (key, group) ->
                val sorted = group.sortedBy { it.fromMinutes }
                sorted.zipWithNext().forEach { (a, b) ->
                    if (b.fromMinutes < a.toMinutes) {
                        problems += problem(
                            "recoveryContribution",
                            contributions.indexOf(b),
                            "overlapping funded ranges for ${key.first}:${key.second} epoch ${key.third}",
                        )
                    }
                }
            }
    }

    private fun validateTimedPlacements(
        placements: List<BackupTimedPlacement>,
        contributionsById: Map<String, BackupContribution>,
        problems: MutableList<BackupValidationProblem>,
    ) {
        placements.forEachIndexed { index, placement ->
            val contribution = contributionsById[placement.contributionId]
            when {
                placement.placementId.isBlank() ->
                    problems += problem("recoveryTimedPlacement", index, "blank placementId")
                contribution == null ->
                    problems += problem(
                        "recoveryTimedPlacement", index,
                        "references absent contribution ${placement.contributionId}",
                    )
                placement.account != contribution.account || placement.appId != contribution.appId ->
                    problems += problem(
                        "recoveryTimedPlacement", index,
                        "account/game does not match contribution ${placement.contributionId}",
                    )
            }
            if (placement.minutes <= 0L) {
                problems += problem("recoveryTimedPlacement", index, "minutes must be positive")
            }
            if (placement.revision < 1L) {
                problems += problem("recoveryTimedPlacement", index, "revision must be positive")
            }
            if (contribution != null && placement.revision != contribution.revision) {
                problems += problem(
                    "recoveryTimedPlacement", index,
                    "revision ${placement.revision} is not the contribution's ${contribution.revision}",
                )
            }
            if (!isPlausibleDate(placement.creditedDate)) {
                problems += problem("recoveryTimedPlacement", index, "unusable creditedDate '${placement.creditedDate}'")
            }
            if (!isZone(placement.zone)) {
                problems += problem("recoveryTimedPlacement", index, "invalid zone '${placement.zone}'")
            }
            if (placement.evidenceQuality !in EVIDENCE_QUALITIES) {
                problems += problem(
                    "recoveryTimedPlacement", index,
                    "unknown evidenceQuality ${placement.evidenceQuality}",
                )
            }
            placement.sessionStartAt?.let { instant ->
                if (instant.epochMilliOrNull()?.inRange() != true) {
                    problems += problem(
                        "recoveryTimedPlacement", index,
                        "unusable sessionStartAt '$instant'",
                    )
                }
            }
            placement.sessionEndAt?.let { instant ->
                val millis = instant.epochMilliOrNull()
                if (millis?.inRange() != true || placement.sessionStartAt != null &&
                    millis != null &&
                    placement.sessionStartAt.epochMilliOrNull()?.let { millis < it } == true
                ) {
                    problems += problem(
                        "recoveryTimedPlacement", index,
                        "unusable sessionEndAt '$instant'",
                    )
                }
            }
        }
        duplicateIds(placements.map { it.placementId }, "recoveryTimedPlacement", problems)
    }

    /** Portable hosted shares must resolve to exactly one exported natural-key session. */
    private fun validateTimedSessionLinks(
        placements: List<BackupTimedPlacement>,
        sessions: List<BackupSession>,
        problems: MutableList<BackupValidationProblem>,
    ) {
        placements.forEachIndexed { index, placement ->
            val startText = placement.sessionStartAt
            val endText = placement.sessionEndAt
            if (startText == null) {
                if (endText != null) {
                    problems += problem("recoveryTimedPlacement", index, "sessionEndAt without sessionStartAt")
                }
                return@forEachIndexed // detached timed share
            }
            val start = startText.epochMilliOrNull()
            val end = endText?.epochMilliOrNull()
            if (start == null || endText != null && end == null) return@forEachIndexed
            val matches = sessions.count { session ->
                session.appId == placement.appId &&
                    session.startAt.epochMilliOrNull() == start &&
                    session.endAt?.epochMilliOrNull() == end
            }
            if (matches != 1) {
                problems += problem(
                    "recoveryTimedPlacement", index,
                    if (matches == 0) "portable session link has no matching exported session"
                    else "portable session link is ambiguous across $matches natural-key sessions",
                )
            }
        }
    }

    private fun validateAllocations(
        allocations: List<BackupUnknownAllocation>,
        contributionsById: Map<String, BackupContribution>,
        problems: MutableList<BackupValidationProblem>,
    ) {
        allocations.forEachIndexed { index, allocation ->
            val contribution = contributionsById[allocation.contributionId]
            when {
                allocation.placementId.isBlank() ->
                    problems += problem("recoveryAllocation", index, "blank placementId")
                contribution == null ->
                    problems += problem(
                        "recoveryAllocation", index,
                        "references absent contribution ${allocation.contributionId}",
                    )
                allocation.account != contribution.account || allocation.appId != contribution.appId ->
                    problems += problem(
                        "recoveryAllocation", index,
                        "account/game does not match contribution ${allocation.contributionId}",
                    )
            }
            if (allocation.minutes <= 0L) {
                problems += problem("recoveryAllocation", index, "minutes must be positive")
            }
            if (allocation.revision < 1L) {
                problems += problem("recoveryAllocation", index, "revision must be positive")
            }
            if (contribution != null && allocation.revision != contribution.revision) {
                problems += problem(
                    "recoveryAllocation", index,
                    "revision ${allocation.revision} is not the contribution's ${contribution.revision}",
                )
            }
            if (allocation.method !in ALLOCATION_METHODS) {
                problems += problem("recoveryAllocation", index, "unknown method ${allocation.method}")
            }
            if (allocation.pendingDate) {
                if (allocation.date != null) {
                    problems += problem("recoveryAllocation", index, "pending allocation must not carry a date")
                }
            } else if (allocation.date == null || !isPlausibleDate(allocation.date)) {
                problems += problem("recoveryAllocation", index, "unusable allocation date '${allocation.date}'")
            }
            allocation.zone?.let { zone ->
                if (!isZone(zone)) problems += problem("recoveryAllocation", index, "invalid zone '$zone'")
            }
            listOf("windowStartAt" to allocation.windowStartAt, "windowEndAt" to allocation.windowEndAt)
                .forEach { (name, value) ->
                    value?.let {
                        if (it.epochMilliOrNull()?.inRange() != true) {
                            problems += problem("recoveryAllocation", index, "unusable $name '$it'")
                        }
                    }
                }
        }
        duplicateIds(allocations.map { it.placementId }, "recoveryAllocation", problems)
    }

    private fun validateRecipes(
        recipes: List<BackupAllocationRecipe>,
        contributionsById: Map<String, BackupContribution>,
        problems: MutableList<BackupValidationProblem>,
    ) {
        recipes.forEachIndexed { index, recipe ->
            val contribution = contributionsById[recipe.contributionId]
            if (contribution == null) {
                problems += problem(
                    "recoveryRecipe", index,
                    "references absent contribution ${recipe.contributionId}",
                )
            } else if (recipe.revision != contribution.revision) {
                problems += problem(
                    "recoveryRecipe", index,
                    "recipe revision ${recipe.revision} is not the contribution's ${contribution.revision}",
                )
            }
            if (recipe.eligibleCount < 0) {
                problems += problem("recoveryRecipe", index, "negative eligibleCount")
            }
            if ((recipe.quotient ?: 0L) < 0L) {
                problems += problem("recoveryRecipe", index, "negative quotient")
            }
            recipe.remainderCount?.let { remainder ->
                if (remainder < 0L || (recipe.eligibleCount > 0 && remainder >= recipe.eligibleCount)) {
                    problems += problem("recoveryRecipe", index, "remainder $remainder out of range")
                }
            }
            if (recipe.materializedMinutes < 0L) {
                problems += problem("recoveryRecipe", index, "negative materializedMinutes")
            }
            // Card consistency: q * eligibleCount + r must encode exactly, without overflowing the
            // Long arithmetic a consumer would use, and the materialization cursor must agree with
            // the shipped rows (validated against the allocations in validateConservation).
            val overflows = recipe.eligibleCount > 0 &&
                runCatching {
                    Math.addExact(
                        Math.multiplyExact(recipe.quotient ?: 0L, recipe.eligibleCount.toLong()),
                        recipe.remainderCount ?: 0L,
                    )
                }.isFailure
            if (overflows) {
                problems += problem("recoveryRecipe", index, "recipe arithmetic overflows Long")
            }
            if (!recipe.pending && recipe.eligibleCount == 0 && (recipe.quotient ?: 0L) > 0L) {
                problems += problem("recoveryRecipe", index, "quotient with zero eligible dates")
            }
            listOf("dateStart" to recipe.dateStart, "dateEnd" to recipe.dateEnd)
                .forEach { (name, value) ->
                    value?.let {
                        if (!isPlausibleDate(it)) {
                            problems += problem("recoveryRecipe", index, "unusable $name '$it'")
                        }
                    }
                }
            if (recipe.dateStart != null && recipe.dateEnd != null && recipe.dateStart > recipe.dateEnd) {
                problems += problem("recoveryRecipe", index, "dateStart after dateEnd")
            }
            recipe.zone?.let { zone ->
                if (!isZone(zone)) problems += problem("recoveryRecipe", index, "invalid zone '$zone'")
            }
            val exclusions = mutableListOf<ClosedRange<LocalDate>>()
            recipe.excludedDatesCsv?.splitToSequence(',')?.map { it.trim() }
                ?.filter { it.isNotEmpty() }?.forEach { token ->
                    val separator = token.indexOf("..")
                    val firstText = if (separator < 0) token else token.substring(0, separator)
                    val lastText = if (separator < 0) token else token.substring(separator + 2)
                    val first = firstText.takeIf(::isPlausibleDate)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    val last = lastText.takeIf(::isPlausibleDate)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    if (first == null || last == null || (separator >= 0 && token.indexOf("..", separator + 2) >= 0) || last.isBefore(first)) {
                        problems += problem("recoveryRecipe", index, "unusable excluded date/range '$token'")
                    } else {
                        exclusions += first..last
                    }
                }
            val orderedExclusions = exclusions.sortedBy { it.start }
            if (orderedExclusions.zipWithNext().any { (a, b) -> !b.start.isAfter(a.endInclusive) }) {
                problems += problem("recoveryRecipe", index, "overlapping excluded date ranges")
            }
            if (!recipe.pending && recipe.dateStart != null && recipe.dateEnd != null) {
                val start = runCatching { LocalDate.parse(recipe.dateStart) }.getOrNull()
                val end = runCatching { LocalDate.parse(recipe.dateEnd) }.getOrNull()
                if (start != null && end != null && !end.isBefore(start)) {
                    if (orderedExclusions.any { it.start.isBefore(start) || it.endInclusive.isAfter(end) }) {
                        problems += problem("recoveryRecipe", index, "excluded range lies outside its recipe window")
                    }
                    val excludedCount = orderedExclusions.sumOf {
                        java.time.temporal.ChronoUnit.DAYS.between(it.start, it.endInclusive) + 1L
                    }
                    val expectedEligible = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1L - excludedCount
                    if (expectedEligible != recipe.eligibleCount.toLong()) {
                        problems += problem(
                            "recoveryRecipe", index,
                            "eligibleCount ${recipe.eligibleCount} disagrees with compact exclusion-window count $expectedEligible",
                        )
                    }
                }
            }
        }
        recipes.groupBy { it.contributionId }
            .filterValues { it.size > 1 }
            .forEach { (id, duplicates) ->
                problems += problem(
                    "recoveryRecipe",
                    recipes.indexOf(duplicates.first()),
                    "duplicate recipe for contribution $id",
                )
            }
    }

    private fun validateReplacements(
        replacements: List<BackupReplacement>,
        contributionsById: Map<String, BackupContribution>,
        problems: MutableList<BackupValidationProblem>,
    ) {
        replacements.forEachIndexed { index, replacement ->
            val contribution = contributionsById[replacement.contributionId]
            if (contribution == null) {
                problems += problem(
                    "recoveryReplacement", index,
                    "dangling replacement link to absent contribution ${replacement.contributionId}",
                )
            } else if (replacement.currentRevision < 1L || replacement.currentRevision > contribution.revision) {
                problems += problem(
                    "recoveryReplacement", index,
                    "replacement revision ${replacement.currentRevision} is inconsistent with " +
                        "contribution revision ${contribution.revision}",
                )
            }
            if (replacement.derivedStatus !in JOURNAL_STATUSES) {
                problems += problem(
                    "recoveryReplacement", index,
                    "unknown derivedStatus ${replacement.derivedStatus}",
                )
            }
        }
        replacements.groupBy { it.operationId }
            .filterValues { it.size > 1 }
            .forEach { (id, duplicates) ->
                if (duplicates.distinct().size > 1) {
                    problems += problem(
                        "recoveryReplacement",
                        replacements.indexOf(duplicates.first()),
                        "conflicting duplicate operationId $id",
                    )
                }
            }
    }

    /**
     * The heart of the quantity check: each contribution's authorized minutes must equal the sum
     * of its active timed shares plus either its active unknown-time allocations or its compact
     * recipe's weighted total — and, when a recipe is present, its materialized rows must be a
     * consistent slice of it (rows == materializedMinutes <= recipe total), never a rebalanced
     * graph. Totals are summed with overflow-safe arithmetic.
     */
    private fun validateConservation(
        contributions: List<BackupContribution>,
        timed: List<BackupTimedPlacement>,
        allocations: List<BackupUnknownAllocation>,
        recipesById: Map<String, BackupAllocationRecipe>,
        problems: MutableList<BackupValidationProblem>,
    ) {
        contributions.forEach { contribution ->
            val id = contribution.contributionId
            val index = contributions.indexOf(contribution)
            val timedSum = wideSum(timed.filter { it.contributionId == id && !it.superseded }.map { it.minutes })
            val contributionAllocations = allocations.filter { it.contributionId == id && !it.superseded }
            val allocatedSum = wideSum(contributionAllocations.map { it.minutes })
            val recipe = recipesById[id]
            val allocated = if (recipe != null && !recipe.pending) {
                val recipeTotal = recipeWeightedTotal(recipe)
                if (allocatedSum != recipe.materializedMinutes) {
                    problems += problem(
                        "recoveryRecipe", index,
                        "recipe $id materializedMinutes ${recipe.materializedMinutes} does not " +
                            "match its ${allocatedSum} shipped allocation minutes",
                    )
                }
                if (allocatedSum > recipeTotal) {
                    problems += problem(
                        "recoveryRecipe", index,
                        "materialized allocations exceed recipe total for $id",
                    )
                }
                validateRecipeMaterializedPrefix(recipe, contributionAllocations, index, problems)
                recipeTotal
            } else {
                if (recipe != null && recipe.pending && contributionAllocations.any { !it.pendingDate }) {
                    problems += problem(
                        "recoveryRecipe", index,
                        "pending recipe $id coexists with dated allocations",
                    )
                }
                allocatedSum
            }
            val placed = timedSum.toBigInteger() + allocated.toBigInteger()
            if (placed != contribution.authorizedMinutes.toBigInteger()) {
                problems += problem(
                    "recoveryContribution", index,
                    "funded graph for $id does not conserve: authorized ${contribution.authorizedMinutes}, " +
                        "placed $placed",
                )
            }
        }
    }

    /** Validates compact recipe rows/cursor using calendar rank arithmetic, never full-window expansion. */
    private fun validateRecipeMaterializedPrefix(
        recipe: BackupAllocationRecipe,
        allocations: List<BackupUnknownAllocation>,
        index: Int,
        problems: MutableList<BackupValidationProblem>,
    ) {
        val id = recipe.contributionId
        val start = recipe.dateStart?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val end = recipe.dateEnd?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        if (end.isBefore(start)) return
        val exclusions = recipe.excludedDatesCsv.orEmpty().split(',').filter(String::isNotBlank)
            .mapNotNull { token ->
                val separator = token.indexOf("..")
                val first = runCatching { LocalDate.parse(if (separator < 0) token else token.substring(0, separator)) }.getOrNull()
                    ?: return@mapNotNull null
                val last = runCatching { LocalDate.parse(if (separator < 0) token else token.substring(separator + 2)) }.getOrNull()
                    ?: return@mapNotNull null
                first..last
            }.sortedBy { it.start }
        fun excludedThrough(date: LocalDate): Long = exclusions.sumOf { range ->
            when {
                range.start.isAfter(date) -> 0L
                range.endInclusive <= date -> java.time.temporal.ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1L
                else -> java.time.temporal.ChronoUnit.DAYS.between(range.start, date) + 1L
            }
        }
        fun rank(date: LocalDate): Long? {
            if (date.isBefore(start) || date.isAfter(end) || exclusions.any { date in it }) return null
            return java.time.temporal.ChronoUnit.DAYS.between(start, date) + 1L - excludedThrough(date)
        }
        val positiveCount = if ((recipe.quotient ?: 0L) > 0L) recipe.eligibleCount.toLong()
            else minOf(recipe.eligibleCount.toLong(), recipe.remainderCount ?: 0L)
        val rows = allocations.filter { !it.pendingDate && !it.superseded }
            .mapNotNull { row -> row.date?.let { date -> runCatching { LocalDate.parse(date) }.getOrNull() to row } }
            .filter { it.first != null }
            .map { it.first!! to it.second }
            .sortedBy { it.first }
        var expectedRank = 1L
        for ((date, row) in rows) {
            val actualRank = rank(date)
            val expectedShare = if (actualRank != null && actualRank <= (recipe.remainderCount ?: 0L))
                (recipe.quotient ?: 0L) + 1L else recipe.quotient ?: 0L
            if (actualRank != expectedRank || actualRank > positiveCount || row.minutes != expectedShare) {
                problems += problem(
                    "recoveryRecipe", index,
                    "recipe $id materialized rows are not the exact positive-share prefix",
                )
                break
            }
            expectedRank++
        }
        val cursor = recipe.materializedThroughDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (recipe.materializedThroughDate != null &&
            (cursor == null || cursor.isBefore(start) || cursor.isAfter(end) ||
                minOf(positiveCount, java.time.temporal.ChronoUnit.DAYS.between(start, cursor) + 1L - excludedThrough(cursor)) != rows.size.toLong())
        ) {
            problems += problem("recoveryRecipe", index, "recipe $id cursor skips or precedes positive materialized shares")
        }
        if (cursor == null && rows.size.toLong() == positiveCount) {
            problems += problem("recoveryRecipe", index, "completed recipe $id lacks a cursor proving its zero tail")
        }
    }

    /** `q * eligibleCount + remainder` without intermediate Long overflow. */
    private fun recipeWeightedTotal(recipe: BackupAllocationRecipe): Long {
        val quotient = recipe.quotient ?: 0L
        val remainder = recipe.remainderCount ?: 0L
        return runCatching {
            Math.addExact(Math.multiplyExact(quotient, recipe.eligibleCount.toLong()), remainder)
        }.getOrDefault(-1L)
    }

    private fun wideSum(values: List<Long>): Long =
        runCatching { values.fold(0L, Math::addExact) }.getOrDefault(Long.MAX_VALUE)

    // ------------------------------------------------------------------ helpers

    private fun duplicateIds(
        ids: List<String>,
        recordType: String,
        problems: MutableList<BackupValidationProblem>,
    ) {
        ids.groupBy { it }.filterValues { it.size > 1 }.forEach { (id, duplicates) ->
            problems += problem(
                recordType,
                ids.indexOf(duplicates.first()),
                "duplicate placement id $id",
            )
        }
    }

    private fun problem(recordType: String, index: Int, detail: String) =
        BackupValidationProblem(recordType, index, detail)

    private fun isPlausibleDate(value: String): Boolean {
        val parsed = runCatching { LocalDate.parse(value) }.getOrNull() ?: return false
        return parsed >= BackupValidator.EARLIEST_PLAUSIBLE_DATE &&
            parsed <= BackupValidator.LATEST_PLAUSIBLE_DATE
    }

    private fun isZone(value: String): Boolean =
        value.isNotBlank() && runCatching { ZoneId.of(value) }.isSuccess

    private fun Long.inRange(): Boolean = this in EARLIEST_MILLIS..LATEST_MILLIS

    /**
     * Plausibility ceiling for cumulative Steam minutes. `1e12` minutes is roughly 1,900 years of
     * continuous play; anything beyond it is corruption, and staying far under `Long.MAX_VALUE`
     * keeps every wide arithmetic check honest.
     */
    private const val MAX_PLAUSIBLE_CUMULATIVE_MINUTES = 1_000_000_000_000L

    private fun String.epochMilliOrNull(): Long? = runCatching { iso8601ToEpochMilli() }.getOrNull()

    private val CLOCK_STATES = setOf(
        RecoveryClockStates.VALID,
        RecoveryClockStates.UNKNOWN,
        RecoveryClockStates.REGRESSION,
        RecoveryClockStates.INVERTED,
    )
    private val PROVENANCES = setOf(RecoveryProvenance.SYNC, RecoveryProvenance.RETROACTIVE_PLAY)
    private val EVIDENCE_QUALITIES = setOf(
        RecoveryEvidenceQuality.LOCAL,
        RecoveryEvidenceQuality.CLOUD,
        RecoveryEvidenceQuality.ESTIMATE,
    )
    private val ALLOCATION_METHODS = setOf(
        RecoveryAllocationMethods.SINGLE_DAY,
        RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
        RecoveryAllocationMethods.PENDING_DATE_UNKNOWN,
    )
    private val JOURNAL_STATUSES = setOf(
        RecoveryJournalStatus.PENDING,
        RecoveryJournalStatus.RAW_COMMITTED,
        RecoveryJournalStatus.CORRECTIVE_DONE,
        RecoveryJournalStatus.EARNED_DONE,
        RecoveryJournalStatus.FINALIZED,
    )
}
