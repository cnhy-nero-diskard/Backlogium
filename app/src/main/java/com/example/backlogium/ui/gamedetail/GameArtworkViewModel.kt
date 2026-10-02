package com.example.backlogium.ui.gamedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.GamePreferenceRepository
import com.example.backlogium.domain.GameArtworkPreference
import com.example.backlogium.domain.GameArtworkVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtworkActionState(
    val preference: GameArtworkPreference? = null,
    val pending: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class GameArtworkViewModel @Inject constructor(private val preferences: GamePreferenceRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ArtworkActionState())
    val state = mutableState.asStateFlow()
    private var appId: Long? = null
    private var observer: Job? = null
    private var generation = 0L

    fun show(id: Long) {
        if (appId == id) return
        generation++
        appId = id
        observer?.cancel()
        mutableState.value = ArtworkActionState()
        observer = viewModelScope.launch {
            preferences.artwork(id).collect { preference ->
                mutableState.value = mutableState.value.copy(preference = preference)
            }
        }
    }

    fun select(variant: GameArtworkVariant?) {
        val preference = state.value.preference ?: return
        if (state.value.pending) return
        val token = generation
        mutableState.value = state.value.copy(pending = true, error = null)
        viewModelScope.launch {
            try {
                preferences.setArtwork(preference.appId, variant, preference.steamId)
                if (token == generation) mutableState.value = state.value.copy(pending = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (token == generation) mutableState.value = state.value.copy(pending = false,
                    error = "Could not save the cover. Reopen the game and retry.")
            }
        }
    }
}
