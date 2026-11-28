package com.example.backlogium.data.remote.dto

import com.example.backlogium.domain.normalizedPriceCurrency
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Steam's display strings and independently validated numeric evidence. */
internal data class StorePriceOverviewDto(
    val currency: String?,
    val finalMinorUnits: Long?,
    val initialMinorUnits: Long?,
    val discountPercent: Int,
    val finalFormatted: String,
    val initialFormatted: String?,
) {
    companion object {
        fun decode(element: JsonElement?): StorePriceOverviewDto? {
            val overview = element as? JsonObject ?: return null
            val formatted = overview["final_formatted"].string()
                ?.takeIf { it.isNotBlank() } ?: return null
            return StorePriceOverviewDto(
                currency = normalizedPriceCurrency(overview["currency"].string()),
                finalMinorUnits = overview["final"].nonnegativeInteger(),
                initialMinorUnits = overview["initial"].nonnegativeInteger(),
                discountPercent = overview["discount_percent"].nonnegativeInteger()
                    ?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: 0,
                finalFormatted = formatted,
                initialFormatted = overview["initial_formatted"].string(),
            )
        }
    }
}

private val integerToken = Regex("-?(0|[1-9][0-9]*)")

private fun JsonElement?.nonnegativeInteger(): Long? = (this as? JsonPrimitive)
    ?.takeUnless { it.isString }
    ?.content?.takeIf(integerToken::matches)
    ?.toLongOrNull()?.takeIf { it >= 0 }

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)
    ?.takeIf { it.isString }?.content
