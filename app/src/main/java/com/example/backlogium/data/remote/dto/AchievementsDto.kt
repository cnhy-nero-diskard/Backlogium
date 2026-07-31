package com.example.backlogium.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response envelope for `ISteamUserStats/GetPlayerAchievements`. */
@Serializable
data class PlayerAchievementsResponse(
    val playerstats: PlayerAchievementsResult = PlayerAchievementsResult(),
)

/**
 * [success] is false when Steam has nothing to report for this app (private profile, no
 * stats, or another per-app error) — [error] carries Steam's message. A true [success] with
 * an empty [achievements] list means the game genuinely defines no achievements.
 */
@Serializable
data class PlayerAchievementsResult(
    val success: Boolean = false,
    val error: String? = null,
    val achievements: List<PlayerAchievementDto> = emptyList(),
)

@Serializable
data class PlayerAchievementDto(
    @SerialName("apiname") val apiName: String,
    val achieved: Int = 0,
    val unlocktime: Long = 0L,
)

/** Response envelope for `ISteamUserStats/GetGlobalAchievementPercentagesForApp`. */
@Serializable
data class GlobalAchievementPercentagesResponse(
    val achievementpercentages: GlobalAchievementPercentagesResult =
        GlobalAchievementPercentagesResult(),
)

@Serializable
data class GlobalAchievementPercentagesResult(
    val achievements: List<GlobalAchievementPercentageDto> = emptyList(),
)

@Serializable
data class GlobalAchievementPercentageDto(
    val name: String,
    val percent: Double = 0.0,
)

/** Response envelope for `ISteamUserStats/GetSchemaForGame`. Games with no achievement/stat
 * schema at all omit [GameSchemaResult.availableGameStats] entirely. */
@Serializable
data class GameSchemaResponse(
    val game: GameSchemaResult = GameSchemaResult(),
)

@Serializable
data class GameSchemaResult(
    val availableGameStats: AvailableGameStatsDto? = null,
)

@Serializable
data class AvailableGameStatsDto(
    val achievements: List<AchievementSchemaDto> = emptyList(),
)

@Serializable
data class AchievementSchemaDto(
    val name: String,
    val displayName: String = "",
    val icon: String = "",
)
