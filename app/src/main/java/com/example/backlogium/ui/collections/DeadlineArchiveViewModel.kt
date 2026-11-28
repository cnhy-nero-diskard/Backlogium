package com.example.backlogium.ui.collections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.DeadlineArchivePreview
import com.example.backlogium.data.repo.DeadlineArchiveRepository
import com.example.backlogium.data.repo.DeadlinePreviewExpired
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.domain.DeadlineCollectionIdentity
import com.example.backlogium.domain.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

data class DeadlineFinishState(
    val preview: DeadlineArchivePreview? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val finished: DeadlineCollectionIdentity? = null,
)

@HiltViewModel
class DeadlineArchiveViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: DeadlineArchiveRepository,
    hiddenGames: HiddenGamesRepository,
    time: TimeProvider,
) : ViewModel() {
    private val collectionId = savedState.get<Long>("collectionId") ?: 0L
    val archiveIdentity = savedState.get<Long>("archiveOriginId")?.let { id ->
        DeadlineCollectionIdentity(id, requireNotNull(savedState.get<Long>("archiveCreatedAt")))
    }

    val hiddenAppIds = hiddenGames.hiddenAppIds
    val archives = repository.archives
    private val mutableFinish = MutableStateFlow(DeadlineFinishState())
    val finish = mutableFinish.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val eligible = repository.observeCutoff(collectionId).transformLatest<Long?, Boolean> { cutoff ->
        emit(cutoff != null && time.nowMillis() >= cutoff)
        if (cutoff != null) {
            while (time.nowMillis() < cutoff) delay((cutoff - time.nowMillis()).coerceIn(1, 60_000))
            emit(true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun openPreview() = perform {
        val preview = repository.preview(collectionId)
        DeadlineFinishState(preview = preview, error = if (preview == null) "This deadline is not ready to archive." else null)
    }

    fun dismissPreview() {
        if (!finish.value.busy) mutableFinish.value = DeadlineFinishState()
    }

    fun confirm() {
        val preview = finish.value.preview ?: return
        perform { DeadlineFinishState(finished = repository.confirm(preview).identity) }
    }

    private fun perform(operation: suspend () -> DeadlineFinishState) {
        if (finish.value.busy) return
        mutableFinish.value = finish.value.copy(busy = true, error = null)
        viewModelScope.launch {
            mutableFinish.value = try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: DeadlinePreviewExpired) {
                DeadlineFinishState(error = "The collection changed. Open Finish and archive to review it again.")
            } catch (_: Exception) {
                DeadlineFinishState(error = "Could not archive the collection. Try again.")
            }
        }
    }
}
