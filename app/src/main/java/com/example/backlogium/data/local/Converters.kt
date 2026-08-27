package com.example.backlogium.data.local

import androidx.room.TypeConverter
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.domain.CollectionAccent
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.CollectionTimeBasis
import com.example.backlogium.domain.GameSource

/**
 * Room type converters. Stores the [HltbMatchStatus], [CollectionMode], [CollectionSort], and
 * [CollectionAccent] enums as their names — renaming one silently resets the affected value to
 * whatever the tolerant parse yields, the same label/identifier trade-off as the DataStore sort keys.
 */
class Converters {

    @TypeConverter
    fun fromMatchStatus(status: HltbMatchStatus): String = status.name

    @TypeConverter
    fun toMatchStatus(value: String): HltbMatchStatus = HltbMatchStatus.valueOf(value)

    /**
     * A game's source is stored as its name. Unlike the tolerant parses below, an unrecognised
     * value falls back to [GameSource.STEAM_OWNED]: that is what every pre-migration row is, and
     * treating an unreadable value as owned keeps the game on playtime diffing — the mechanism
     * that has a Steam-side baseline to recover from — rather than handing it to the presence
     * deriver, which would start inventing sessions for a library game.
     */
    @TypeConverter
    fun fromGameSource(source: GameSource): String = source.name

    @TypeConverter
    fun toGameSource(value: String): GameSource =
        runCatching { GameSource.valueOf(value) }.getOrDefault(GameSource.STEAM_OWNED)

    @TypeConverter
    fun fromCollectionMode(mode: CollectionMode): String = mode.name

    @TypeConverter
    fun toCollectionMode(value: String): CollectionMode =
        runCatching { CollectionMode.valueOf(value) }.getOrDefault(CollectionMode.BASIC)

    @TypeConverter
    fun fromCollectionSort(sort: CollectionSort): String = sort.name

    @TypeConverter
    fun toCollectionSort(value: String): CollectionSort =
        runCatching { CollectionSort.valueOf(value) }.getOrDefault(CollectionSort.NAME)

    @TypeConverter
    fun fromCollectionAccent(accent: CollectionAccent?): String? = accent?.name

    @TypeConverter
    fun toCollectionAccent(value: String?): CollectionAccent? =
        CollectionAccent.parse(value)

    @TypeConverter
    fun fromCollectionTimeBasis(basis: CollectionTimeBasis): String = basis.name

    @TypeConverter
    fun toCollectionTimeBasis(value: String): CollectionTimeBasis =
        runCatching { CollectionTimeBasis.valueOf(value) }
            .getOrDefault(CollectionTimeBasis.COMPLETIONIST)
}
