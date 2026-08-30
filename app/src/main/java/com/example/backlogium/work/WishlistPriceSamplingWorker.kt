package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.backlogium.data.repo.WishlistRefresh
import com.example.backlogium.data.repo.WishlistRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Samples wishlist prices on a schedule of its own, so price history does not depend on the player
 * happening to open the section.
 *
 * It records and surfaces nothing else: no alert, no notification, no progress. The history table
 * is the entire output. That restraint is deliberate — drop alerting is a real feature with real
 * decisions of its own, and the recording half is the only part that cannot wait, because history
 * is impossible to reconstruct after the fact.
 *
 * The pass forces past the freshness window: the view path's window exists to stop repeated
 * navigation re-requesting, and applying it here would mean the sampler records nothing precisely
 * on the days the player did open the section — the opposite of what it is for. It issues exactly
 * the batched requests the view path does, one per hundred wanted games, with no per-game fan-out.
 */
@HiltWorker
class WishlistPriceSamplingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val wishlist: WishlistRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        when (wishlist.refresh(force = true)) {
            // Nothing to sample and nothing wrong: no credentials, or every request failed. Both
            // are ordinary conditions for a background pass, and neither is worth a retry storm
            // against endpoints that are undocumented to begin with.
            WishlistRefresh.NOT_CONFIGURED, WishlistRefresh.FAILED -> Result.success()
            WishlistRefresh.REFRESHED, WishlistRefresh.SKIPPED_FRESH -> Result.success()
        }
    } catch (error: Exception) {
        Timber.tag(TAG).w(error, "Wishlist price sampling failed")
        Result.retry()
    }

    companion object {
        const val PERIODIC_NAME = "wishlist_price_sampling"
        private const val TAG = "WishlistSampling"
    }
}
