package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.WishlistItem
import com.example.backlogium.data.local.entity.WishlistPriceObservation
import kotlinx.coroutines.flow.Flow

@Dao
interface WishlistDao {

    /**
     * Entries in Steam's own order: prioritized ones first, ascending, then everything
     * unprioritized by when it was added. Priority 0 means "no priority set", so ordering by the
     * column alone would put every unprioritized entry above the player's actual first choice.
     */
    @Query(
        "SELECT * FROM wishlist_items " +
            "ORDER BY CASE WHEN priority = 0 THEN 1 ELSE 0 END, priority ASC, addedAt ASC",
    )
    fun observeItems(): Flow<List<WishlistItem>>

    @Query("SELECT appId FROM wishlist_items")
    suspend fun appIds(): List<Long>

    /** A one-shot read, for the refresh that has to merge Steam's entries onto what is stored. */
    @Query("SELECT * FROM wishlist_items")
    suspend fun items(): List<WishlistItem>

    @Upsert
    suspend fun upsertItems(items: List<WishlistItem>)

    /**
     * Drop entries Steam no longer lists. Called only after a wishlist read that actually
     * succeeded — a failed read says nothing about what is still wanted, and clearing on one
     * would empty the section every time the endpoint blinked.
     */
    @Query("DELETE FROM wishlist_items WHERE lastSeenAt < :seenBefore")
    suspend fun deleteItemsNotSeenSince(seenBefore: Long)

    @Query("DELETE FROM wishlist_items")
    suspend fun deleteAllItems()

    /** Append-only: observations are never updated, so there is no upsert here on purpose. */
    @Insert
    suspend fun insertObservations(observations: List<WishlistPriceObservation>)

    /**
     * The most recent observation per app. `MAX(id)` rather than `MAX(observedAt)` breaks ties in
     * insertion order, so two observations recorded in the same millisecond still resolve to the
     * later one.
     */
    @Query(
        "SELECT * FROM wishlist_price_observations WHERE id IN " +
            "(SELECT MAX(id) FROM wishlist_price_observations GROUP BY appId)",
    )
    fun observeLatestPrices(): Flow<List<WishlistPriceObservation>>

    /**
     * The oldest "last observed" instant across the wishlist, or null when it holds no entries.
     *
     * The freshness window is judged on the *oldest* entry rather than the newest, so a game
     * wishlisted since the last refresh still pulls one. An entry never observed at all counts as
     * 0 for exactly that reason — `MIN` skips nulls, which would otherwise let the one entry that
     * has no price be the one entry a refresh never covers.
     */
    @Query(
        "SELECT MIN(IFNULL(latest, 0)) FROM (SELECT MAX(o.observedAt) AS latest " +
            "FROM wishlist_items i LEFT JOIN wishlist_price_observations o ON o.appId = i.appId " +
            "GROUP BY i.appId)",
    )
    suspend fun oldestLatestObservationAt(): Long?

    @Query("DELETE FROM wishlist_price_observations")
    suspend fun deleteAllObservations()
}
