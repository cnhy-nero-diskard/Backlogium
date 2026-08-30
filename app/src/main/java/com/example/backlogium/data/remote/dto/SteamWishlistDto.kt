package com.example.backlogium.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response envelope for `IWishlistService/GetWishlist`. */
@Serializable
data class WishlistResponse(
    val response: WishlistResult = WishlistResult(),
)

/**
 * [items] is **nullable on purpose**. A wishlist that cannot be read answers `HTTP 200` with
 * `{"response":{}}` — the key is absent rather than an empty array — so a null list and an empty
 * one are Steam's own way of separating "not readable" from "nothing wishlisted". Defaulting this
 * to `emptyList()` would erase the only signal the app has and make a private wishlist look like
 * an empty one, which is precisely the confusion the wishlist spec forbids.
 */
@Serializable
data class WishlistResult(
    val items: List<WishlistItemDto>? = null,
)

/**
 * One wishlisted app. Three fields is all the endpoint returns — no name, no artwork — which is
 * why entry details come from [StoreItemDto] instead.
 *
 * [priority] is the player's own ordering, with 0 meaning unprioritized rather than "first".
 * [dateAddedSeconds] is epoch **seconds**, unlike every timestamp stored locally.
 */
@Serializable
data class WishlistItemDto(
    @SerialName("appid") val appId: Long = 0,
    val priority: Int = 0,
    @SerialName("date_added") val dateAddedSeconds: Long = 0,
)

/** Response envelope for `IStoreBrowseService/GetItems`. */
@Serializable
data class StoreItemsResponse(
    val response: StoreItemsResult = StoreItemsResult(),
)

@Serializable
data class StoreItemsResult(
    @SerialName("store_items") val storeItems: List<StoreItemDto> = emptyList(),
)

/**
 * One app as the store describes it. [success] is `1` when the store answered for the id;
 * anything else means it did not, and the entry carries no usable name.
 */
@Serializable
data class StoreItemDto(
    val id: Long = 0,
    @SerialName("appid") val appId: Long? = null,
    val success: Int = 0,
    val visible: Boolean = false,
    val name: String = "",
    val assets: StoreItemAssetsDto? = null,
)

/**
 * Where the store serves an app's art. [assetUrlFormat] is a CDN-relative path with a `${FILENAME}`
 * placeholder and a cache-busting timestamp — the store's own answer for this app, rather than the
 * well-known path [com.example.backlogium.data.remote.SteamIconMapper] would guess.
 */
@Serializable
data class StoreItemAssetsDto(
    @SerialName("asset_url_format") val assetUrlFormat: String? = null,
    val header: String? = null,
)
