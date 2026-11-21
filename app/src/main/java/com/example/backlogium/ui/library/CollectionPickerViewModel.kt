package com.example.backlogium.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.CollectionMembershipRepository
import com.example.backlogium.domain.CollectionPicker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionPickerUiState(
    val appId: Long? = null,
    val gameName: String = "",
    val picker: CollectionPicker? = null,
    val pending: Boolean = false,
    val feedback: String? = null,
    val failed: Boolean = false,
)

@HiltViewModel
class CollectionPickerViewModel @Inject constructor(
    private val memberships: CollectionMembershipRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CollectionPickerUiState())
    val state = mutableState.asStateFlow()
    private var observer: Job? = null

    fun open(appId: Long, name: String) {
        if (mutableState.value.pending) return
        observer?.cancel()
        mutableState.value = CollectionPickerUiState(appId, name)
        observer = viewModelScope.launch {
            var boundIdentity: String? = null
            memberships.picker(appId).collect { picker ->
                if (boundIdentity == null) boundIdentity = picker.steamId
                if (picker.steamId != boundIdentity) close() else mutableState.update { it.copy(picker = picker) }
            }
        }
    }

    fun close() {
        if (mutableState.value.pending) return
        observer?.cancel()
        mutableState.update { it.copy(appId = null, picker = null) }
    }

    fun add(collectionId: Long) {
        val current = mutableState.value
        val appId = current.appId ?: return
        val picker = current.picker ?: return
        val target = picker.targets.find { it.id == collectionId } ?: return
        if (current.pending || target.alreadyMember) return
        mutableState.update { it.copy(pending = true, feedback = null, failed = false) }
        viewModelScope.launch {
            try {
                memberships.add(collectionId, appId, picker.steamId)
                mutableState.update { it.copy(feedback = "${current.gameName} added to ${target.name}") }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(feedback = "Could not add to collection. Refresh the picker and retry.", failed = true) }
            } finally { mutableState.update { it.copy(pending = false) } }
        }
    }
}
