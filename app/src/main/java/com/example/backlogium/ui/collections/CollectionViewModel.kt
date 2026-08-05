package com.example.backlogium.ui.collections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.CollectionRepository
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.domain.CollectionAccent
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
    val done: Boolean = false,
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
    val accent: CollectionAccent? = null,
    /** Current members in their editing sequence (queue order for ordered-queue collections). */
    val members: List<CollectionMemberUi> = emptyList(),
    /** Every library game, so the add-games control can offer them without a second source. */
    val libraryGames: List<LibraryGame> = emptyList(),
    /** Set true after save/delete to trigger navigation back to Home. */
    val done: Boolean = false,
    /** True while save is in flight; guards against double taps and disables controls. */
    val saving: Boolean = false,
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
 * mode, sort, accent, and deadline; add/remove of games; move up/down reordering and manual
 * done toggles for ordered-queue collections; and delete. Renders purely from local state
 * (Room + cached library) — no network. Membership edits are buffered and persisted atomically
 * on save, so cancelling (popping back) discards them.
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
    private val _accent = MutableStateFlow<CollectionAccent?>(null)
    /** The editing session's member sequence (app ids, in queue order). */
    private val _memberAppIds = MutableStateFlow<List<Long>>(emptyList())
    /** Manual done marks buffered during the edit session, keyed by app id. */
    private val _doneMarks = MutableStateFlow<Set<Long>>(emptySet())
    private val _loaded = MutableStateFlow(collectionId == 0L)
    private val _done = MutableStateFlow(false)
    private val _saving = MutableStateFlow(false)

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
                    _accent.value = collection.accent
                }
                val members = collectionRepository.getMembers(collectionId)
                    .sortedBy { it.orderIndex }
                _memberAppIds.value = members.map { it.appId }
                _doneMarks.value = members.filter { it.done }.map { it.appId }.toSet()
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
        val accent: CollectionAccent?,
    )

    private data class Session(
        val draft: Draft,
        val memberAppIds: List<Long>,
        val doneMarks: Set<Long>,
        val loaded: Boolean,
        val done: Boolean,
        val saving: Boolean,
    )

    private val draft: StateFlow<Draft> = combine(
        _name,
        _mode,
        _sort,
        _targetDate,
        _accent,
    ) { name, mode, sort, targetDate, accent ->
        Draft(name, mode, sort, targetDate, accent)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Draft("", CollectionMode.BASIC, CollectionSort.NAME, null, null))

    private val session: StateFlow<Session> = combine(
        draft,
        _memberAppIds,
        _doneMarks,
        _loaded,
        _done,
        _saving,
    ) { d, memberIds, doneMarks, loaded, done, saving ->
        Session(d, memberIds, doneMarks, loaded, done, saving)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        Session(Draft("", CollectionMode.BASIC, CollectionSort.NAME, null, null), emptyList(), emptySet(), collectionId == 0L, false, false),
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
            accent = s.draft.accent,
            members = s.memberAppIds.map { appId ->
                val game = gamesById[appId]
                CollectionMemberUi(
                    appId = appId,
                    // A dangling member (game absent from the library) stays listed under a
                    // readable fallback so it can still be removed; the pure summary omits it.
                    name = game?.name ?: "Game $appId",
                    iconUrl = game?.iconUrl,
                    done = appId in s.doneMarks,
                )
            },
            libraryGames = games,
            done = s.done,
            saving = s.saving,
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

    fun setAccent(accent: CollectionAccent?) {
        _accent.value = accent
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

    /** Toggle the manual done mark for an ordered-queue member (buffered until save). */
    fun toggleMemberDone(appId: Long) {
        _doneMarks.update { current ->
            if (appId in current) current - appId else current + appId
        }
    }

    /**
     * Persist everything atomically: the collection row (create or update) plus the reconciled
     * member sequence — new members added, missing ones removed, the queue order written as
     * the new orderIndex values, and the buffered done marks reconciled.
     */
    fun save() {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            val target = _targetDate.value?.toString()
            val accent = _accent.value
            val id = if (collectionId == 0L) {
                collectionRepository.create(_name.value, _mode.value, _sort.value, target, accent)
            } else {
                collectionRepository.updateDetails(
                    collectionId,
                    _name.value,
                    _mode.value,
                    _sort.value,
                    target,
                    accent,
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
            val doneSet = _doneMarks.value
            desired.forEach { appId ->
                collectionRepository.setMemberDone(id, appId, appId in doneSet)
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
