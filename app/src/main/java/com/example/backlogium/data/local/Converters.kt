package com.example.backlogium.data.local

import androidx.room.TypeConverter
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort

/**
 * Room type converters. Stores the [HltbMatchStatus], [CollectionMode], and [CollectionSort]
 * enums as their names — renaming one silently resets the affected value to whatever the
 * tolerant parse yields, the same label/identifier trade-off as the DataStore sort keys.
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
}
