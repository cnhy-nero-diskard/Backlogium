package com.example.backlogium.ui.collections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.CollectionRepository
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.defaultSort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** One member row in the management screen: identity plus the library facts it renders. */
data class CollectionMemberUi(
    val appId: Long,
    val name: String,
    val iconUrl: String?,
)

/** Full management-screen state for one collection (create or edit), all local/offline-first. */
data class CollectionUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val collectionId: Long = 0L,
    val name: String = "",
    val mode: CollectionMode = CollectionMode.BASIC,
    val sort: CollectionSort = CollectionSort.NAME,
    val targetDate: LocalDate? = null,
    /** Current members in their editing sequence (queue order for ordered-queue collections). */
    val members: List<CollectionMemberUi> = emptyList(),
    /** Every library game, so the add-games control can offer them without a second source. */
    val libraryGames: List<LibraryGame> = emptyList(),
    /** Set true after save/delete to trigger navigation back to Home. */
    val done: Boolean = false,
) {
    /** Library games not already members — the add-games control's pool. */
    val addableGames: List<LibraryGame>
        get() {
            val memberIds = members.mapTo(mutableSetOf()) { it.appId }
            return libraryGames.filter { it.appId !in memberIds }
        }
}


/**
 * Owns the collection management screen (tasks 4.2–4.6): create/edit of a collection's name,
 * mode, sort, and deadline; add/remove of games; move up/down reordering for ordered-queue
 * collections; and delete. Renders purely from local state (Room + cached library) — no
 * network. Membership edits are buffered and persisted atomically on save, so cancelling
 * (popping back) discards them.
 */
@HiltViewModel
class CollectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val collectionRepository: CollectionRepository,
    private val gameRepository: GameRepository,
) : ViewModel() {

    /** 0 when creating a new collection; otherwise the collection being edited. */
    val collectionId: Long = savedStateHandle.get<Long>("collectionId") ?: 0L

    private val _name = MutableStateFlow("")
    private val _mode = MutableStateFlow(CollectionMode.BASIC)
    private val _sort = MutableStateFlow(CollectionSort.NAME)
    private val _targetDate = MutableStateFlow<LocalDate?>(null)
    /** The editing session's member sequence (app ids, in queue order). */
    private val _memberAppIds = MutableStateFlow<List<Long>>(emptyList())
    private val _loaded = MutableStateFlow(collectionId == 0L)
    private val _done = MutableStateFlow(false)

    init {
        if (collectionId != 0L) {
            viewModelScope.launch {
                val collection = collectionRepository.getById(collectionId)
                if (collection != null) {
                    _name.value = collection.name
                    _mode.value = collection.mode
                    _sort.value = collection.sort
                    _targetDate.value = collection.targetDate
                        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                }
                _memberAppIds.value = collectionRepository.getMembers(collectionId)
                    .sortedBy { it.orderIndex }
                    .map { it.appId }
                _loaded.value = true
            }
        }
    }

    /** The editing session's draft fields, grouped so the UI combine stays within the typed overloads. */
    private data class Draft(
        val name: String,
        val mode: CollectionMode,
        val sort: CollectionSort,
        val targetDate: LocalDate?,
    )

    private data class Session(
        val draft: Draft,
        val memberAppIds: List<Long>,
        val loaded: Boolean,
        val done: Boolean,
    )

    private val draft: StateFlow<Draft> = combine(
        _name,
        _mode,
        _sort,
        _targetDate,
    ) { name, mode, sort, targetDate ->
        Draft(name, mode, sort, targetDate)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Draft("", CollectionMode.BASIC, CollectionSort.NAME, null))

    private val session: StateFlow<Session> = combine(
        draft,
        _memberAppIds,
        _loaded,
        _done,
    ) { d, memberIds, loaded, done ->
        Session(d, memberIds, loaded, done)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        Session(Draft("", CollectionMode.BASIC, CollectionSort.NAME, null), emptyList(), collectionId == 0L, false),
    )

    val uiState: StateFlow<CollectionUiState> = combine(
        gameRepository.library,
        session,
    ) { games, s ->
        val gamesById = games.associateBy { it.appId }
        CollectionUiState(
            loading = !s.loaded,
            isNew = collectionId == 0L,
            collectionId = collectionId,
            name = s.draft.name,
            mode = s.draft.mode,
            sort = s.draft.sort,
            targetDate = s.draft.targetDate,
            members = s.memberAppIds.map { appId ->
                val game = gamesById[appId]
                CollectionMemberUi(
                    appId = appId,
                    // A dangling member (game absent from the library) stays listed under a
                    // readable fallback so it can still be removed; the pure summary omits it.
                    name = game?.name ?: "Game $appId",
                    iconUrl = game?.iconUrl,
                )
            },
            libraryGames = games,
            done = s.done,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CollectionUiState(),
    )

    fun setName(value: String) {
        _name.value = value
    }

    fun setMode(mode: CollectionMode) {
        _mode.value = mode
        // A mode is a preset, so switching modes adopts that mode's sensible default sort
        // (spec: "Default sort per mode"); the user can then refine it if offered. The target
        // date is deadline-only, so leaving deadline mode drops it (spec: "Target date stored
        // only for deadline mode").
        _sort.value = mode.defaultSort()
        if (mode != CollectionMode.DEADLINE_GOAL) _targetDate.value = null
    }

    fun setSort(sort: CollectionSort) {
        _sort.value = sort
    }

    fun setTargetDate(date: LocalDate?) {
        _targetDate.value = date
    }

    fun addGame(appId: Long) {
        _memberAppIds.update { current ->
            if (appId in current) current else current + appId
        }
    }

    fun removeGame(appId: Long) {
        _memberAppIds.update { current -> current.filterNot { it == appId } }
    }

    /** Move a member up/down in the editing sequence (ordered-queue reordering). */
    fun moveMember(fromIndex: Int, toIndex: Int) {
        _memberAppIds.update { current ->
            if (fromIndex !in current.indices || toIndex !in current.indices) return@update current
            val reordered = current.toMutableList()
            val moved = reordered.removeAt(fromIndex)
            reordered.add(toIndex, moved)
            reordered
        }
    }

    /**
     * Persist everything atomically: the collection row (create or update) plus the reconciled
     * member sequence — new members added, missing ones removed, and the queue order written as
     * the new orderIndex values.
     */
    fun save() {
        viewModelScope.launch {
            val target = _targetDate.value?.toString()
            val id = if (collectionId == 0L) {
                collectionRepository.create(_name.value, _mode.value, _sort.value, target)
            } else {
                collectionRepository.updateDetails(
                    collectionId,
                    _name.value,
                    _mode.value,
                    _sort.value,
                    target,
                )
                collectionId
            }

            val existing = collectionRepository.getMembers(id).map { it.appId }.toSet()
            val desired = _memberAppIds.value
            desired.forEach { appId ->
                if (appId !in existing) collectionRepository.addMember(id, appId)
            }
            collectionRepository.reorderMembers(id, desired)
            existing.filterNot { it in desired }.forEach { appId ->
                collectionRepository.removeMember(id, appId)
            }
            _done.value = true
        }
    }

    /** Delete the collection; memberships cascade via the FK. */
    fun delete() {
        if (collectionId == 0L) return
        viewModelScope.launch {
            collectionRepository.delete(collectionId)
            _done.value = true
        }
    }
}
