package com.example.backlogium.data.local.entity

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Versioned, immutable recovery record for the Room side of a historical re-file. */
@Serializable
data class CloudHistoricalApplyPayload(
    val version: Int = CURRENT_VERSION,
    val operationId: String,
    val account: String,
    val startChoice: String,
    val selectedStartAt: Long,
    val effectiveStartAt: Long,
    val throughAt: Long,
    val zoneId: String,
    val confirmedCutoffAt: Long?,
    val originalSessions: List<CloudHistoricalSessionSnapshot>,
    /** Actual replacement rows, including the original ids retained by first-row updates. */
    val replacementSessions: List<CloudHistoricalSessionSnapshot>,
    /** Newly inserted split rows and imported-minute rows, with their Room ids. */
    val createdSessions: List<CloudHistoricalSessionSnapshot>,
    val transferredMinutesByAppId: List<CloudHistoricalGameMinutes>,
    val remainingImportedMinutesByAppId: List<CloudHistoricalGameMinutes>,
    val dailyProgressBefore: List<CloudHistoricalDailyProgressSnapshot>,
    val sessionsRefiled: Int,
    val datesAffected: List<String>,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class CloudHistoricalSessionSnapshot(
    val id: Long,
    val appId: Long,
    val startAt: Long,
    val endAt: Long?,
    val minutes: Int,
    val open: Boolean,
    val recoveredSharedPlay: String?,
    val timingInformedSteamPlay: String?,
    val openAppId: Long?,
)

@Serializable
data class CloudHistoricalGameMinutes(
    val appId: Long,
    val minutes: Int,
)

@Serializable
data class CloudHistoricalDailyProgressSnapshot(
    val date: String,
    val minutesPlayed: Int,
    val goalMinutesPlayed: Int,
    val questMet: Boolean,
)

/** Strict, version-checked encoding for a journal that is the source of truth after Room commit. */
object CloudHistoricalApplyPayloadCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(payload: CloudHistoricalApplyPayload): String =
        json.encodeToString(payload)

    fun decode(value: String): CloudHistoricalApplyPayload =
        json.decodeFromString<CloudHistoricalApplyPayload>(value).also { payload ->
            require(payload.version == CloudHistoricalApplyPayload.CURRENT_VERSION) {
                "Unsupported cloud historical apply payload version ${payload.version}"
            }
        }
}

fun Session.toCloudHistoricalSnapshot(): CloudHistoricalSessionSnapshot =
    CloudHistoricalSessionSnapshot(
        id = id,
        appId = appId,
        startAt = startAt,
        endAt = endAt,
        minutes = minutes,
        open = open,
        recoveredSharedPlay = recoveredSharedPlay?.name,
        timingInformedSteamPlay = timingInformedSteamPlay?.name,
        openAppId = openAppId,
    )

fun CloudHistoricalSessionSnapshot.toSession(): Session = Session(
    id = id,
    appId = appId,
    startAt = startAt,
    endAt = endAt,
    minutes = minutes,
    open = open,
    recoveredSharedPlay = recoveredSharedPlay?.let { value ->
        runCatching { RecoveredSharedPlayState.valueOf(value) }.getOrNull()
    },
    timingInformedSteamPlay = timingInformedSteamPlay?.let { value ->
        runCatching { TimingInformedSteamPlayState.valueOf(value) }.getOrNull()
    },
    openAppId = openAppId,
)

fun DailyProgress.toCloudHistoricalSnapshot(): CloudHistoricalDailyProgressSnapshot =
    CloudHistoricalDailyProgressSnapshot(
        date = date,
        minutesPlayed = minutesPlayed,
        goalMinutesPlayed = goalMinutesPlayed,
        questMet = questMet,
    )
