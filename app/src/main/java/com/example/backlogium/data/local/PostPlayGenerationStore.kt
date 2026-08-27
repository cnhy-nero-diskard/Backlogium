package com.example.backlogium.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.backlogium.domain.PostPlayGenerations
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.postPlayGenerationDataStore by preferencesDataStore(name = "post_play_generations")

/**
 * The per-app schedule generation for the post-play playtime fetch: a monotonically increasing
 * counter, advanced once per session end, that says which of an app's schedules is the live one.
 *
 * Persisted rather than held in memory because the schedule it owns outlives the process — a
 * session ending just before the app is closed is exactly the case the feature exists for, so the
 * guard that stops a superseded attempt from committing has to survive process death too.
 *
 * A key space of its own rather than a set of keys in [SettingsDataStore]: the keys are per app id
 * and therefore unbounded, where that store's vocabulary is a fixed list of settings fields. Its
 * values are counters with no meaning outside a pending schedule, so nothing here needs clearing
 * on an account change — a fresh generation still wins over whatever number was stored.
 */
@Singleton
class PostPlayGenerationStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : PostPlayGenerations {
    /**
     * Advance the app's generation and return the new value.
     *
     * DataStore serializes `edit {}`, so the read-modify-write below cannot lose a concurrent
     * advance. That makes this atomic on its own; [com.example.backlogium.work.PostPlayGenerationCoordinator]
     * still serializes it against the *enqueue* that follows, which is a wider critical section
     * than this store can see.
     */
    override suspend fun advance(appId: Long): Long {
        var advanced = FIRST_GENERATION
        context.postPlayGenerationDataStore.edit { prefs ->
            advanced = (prefs[key(appId)] ?: 0L) + 1
            prefs[key(appId)] = advanced
        }
        return advanced
    }

    /** The app's live generation, or 0 when no schedule has ever started for it. */
    override suspend fun current(appId: Long): Long =
        context.postPlayGenerationDataStore.data.first()[key(appId)] ?: 0L

    private fun key(appId: Long) = longPreferencesKey("post_play_generation_$appId")

    companion object {
        /** The first generation an app is ever given; 0 means "no schedule has started". */
        const val FIRST_GENERATION = 1L
    }
}
