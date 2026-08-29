package com.example.backlogium.data.remote.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject

/**
 * One app's entry in a price-filtered `appdetails` response.
 *
 * Deliberately **not** [StoreAppDetails]. That DTO types `data` as an object, which is correct for
 * the unfiltered request the genre path makes but wrong here: a price-filtered request answers
 * `"data": []` for any app that has no price, and deserializing an array into an object type
 * throws. Because Steam returns the whole batch as a single JSON document, one free-to-play game
 * would fail the *entire* response rather than just its own entry — see `add-wishlist-section`'s
 * design, decision 2. Widening the shared DTO instead would add a branch the genre caller can
 * never take.
 *
 * The two absences are different answers and are kept apart:
 * - `success = true` with `data: []` — Steam says this app has no price. Definitive.
 * - `success = false` — Steam declines to describe the app id at all, and says nothing about
 *   whether a price exists. Not an answer, so not something to record.
 */
@Serializable
data class StorePriceEnvelope(
    val success: Boolean = false,
    @Serializable(with = StorePriceDataSerializer::class)
    val data: StorePriceData? = null,
)

/** The `data` object of a price-filtered entry. A null [priceOverview] means "no price". */
@Serializable
data class StorePriceData(
    @SerialName("price_overview") val priceOverview: StorePriceOverviewDto? = null,
)

/**
 * Steam's price block, in the currency of the requested region.
 *
 * [finalFormatted] is the only field safe to render unconditionally: it is the rendered price for
 * the region, symbol placement included, which is not worth reconstructing from the minor units.
 * [initialFormatted] is **empty at full price** and carries the struck-through list price only
 * while [discountPercent] is non-zero, so it must never be shown as "the price" on its own.
 * [initialMinorUnits] and [finalMinorUnits] are the integer minor units, for anything arithmetic.
 */
@Serializable
data class StorePriceOverviewDto(
    val currency: String = "",
    @SerialName("initial") val initialMinorUnits: Long = 0,
    @SerialName("final") val finalMinorUnits: Long = 0,
    @SerialName("discount_percent") val discountPercent: Int = 0,
    @SerialName("initial_formatted") val initialFormatted: String = "",
    @SerialName("final_formatted") val finalFormatted: String = "",
)

/**
 * Reads `data` as an object when there is one and as "no price" when Steam sends `[]`.
 *
 * The array carries nothing — it is purely the encoding for absence — so it maps to a
 * [StorePriceData] with no price overview rather than to a failure. Anything that is not a JSON
 * object is treated the same way for the same reason: the request succeeded, and this entry
 * simply has no price object in it.
 */
object StorePriceDataSerializer : KSerializer<StorePriceData> {
    private val delegate = StorePriceData.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): StorePriceData {
        val json = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val element = json.decodeJsonElement()
        if (element !is JsonObject) return StorePriceData()
        return json.json.decodeFromJsonElement(delegate, element)
    }

    override fun serialize(encoder: Encoder, value: StorePriceData) =
        delegate.serialize(encoder, value)
}
