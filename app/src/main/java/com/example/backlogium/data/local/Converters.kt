package com.example.backlogium.data.local

import androidx.room.TypeConverter
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.domain.CollectionAccent
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.CollectionTimeBasis

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
