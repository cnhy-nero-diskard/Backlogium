package com.example.backlogium.data.local

import androidx.room.TypeConverter
import com.example.backlogium.data.local.entity.HltbMatchStatus

/** Room type converters. Stores the [HltbMatchStatus] enum as its name. */
class Converters {

    @TypeConverter
    fun fromMatchStatus(status: HltbMatchStatus): String = status.name

    @TypeConverter
    fun toMatchStatus(value: String): HltbMatchStatus = HltbMatchStatus.valueOf(value)
}
