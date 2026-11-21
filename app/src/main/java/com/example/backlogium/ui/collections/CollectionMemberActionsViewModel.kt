package com.example.backlogium.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.CollectionMembershipRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MemberRemovalState(
    val ready: Boolean = false,
    val pending: Boolean = false,
    val feedback: String? = null,
    val failed: Boolean = false,
    val committed: Int = 0,
)

@HiltViewModel
class CollectionMemberActionsViewModel @Inject constructor(
    private val memberships: CollectionMembershipRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MemberRemovalState())
    val state = mutableState.asStateFlow()
    private var identity: String? = null
    private var collectionId: Long? = null

    fun open(id: Long) {
        if (collectionId == id) return
        collectionId = id
        identity = null
        mutableState.value = MemberRemovalState()
        viewModelScope.launch {
            try {
                val capturedIdentity = memberships.accountIdentity()
                if (collectionId == id) {
                    identity = capturedIdentity
                    mutableState.update { it.copy(ready = true) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(feedback = "Account changed. Reopen this collection.", failed = true)
                }
            }
        }
    }

    fun remove(appId: Long, gameName: String, collectionName: String) {
        val steamId = identity ?: return
        val id = collectionId ?: return
        if (mutableState.value.pending) return
        mutableState.update { it.copy(pending = true, feedback = null, failed = false) }
        viewModelScope.launch {
            try {
                memberships.remove(id, appId, steamId)
                mutableState.update { it.copy(feedback = "$gameName removed from $collectionName", committed = it.committed + 1) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.update { it.copy(feedback = "Could not remove membership. Retry or reopen the collection.", failed = true) }
            } finally { mutableState.update { it.copy(pending = false) } }
        }
    }
}
