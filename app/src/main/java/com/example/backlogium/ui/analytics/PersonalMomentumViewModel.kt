package com.example.backlogium.ui.analytics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.PersonalMomentumRepository
import com.example.backlogium.domain.CurrentDateProvider
import com.example.backlogium.domain.MomentumKey
import com.example.backlogium.domain.MomentumRead
import com.example.backlogium.domain.observePersonalMomentum
import com.example.backlogium.ui.util.observeCalendar
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MomentumUiState(val read: MomentumRead = MomentumRead(), val detailUnavailable: Boolean = false)

/** Re-check after the local read: account replacement or a disappearing row must not open old detail. */
internal suspend fun openMomentumDetail(appId: Long, accountId: String,
    currentAccount: suspend () -> String?, visible: suspend (Long) -> Boolean,
    stillDisplayed: () -> Boolean, onOpen: (Long) -> Unit): Boolean {
    if (currentAccount() != accountId || !visible(appId) || currentAccount() != accountId || !stillDisplayed()) return false
    onOpen(appId)
    return true
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PersonalMomentumViewModel @Inject constructor(
    private val repository: PersonalMomentumRepository,
    private val credentials: CredentialsRepository,
    private val currentDate: CurrentDateProvider,
    @param:ApplicationContext context: Context,
) : ViewModel() {
    private val detailUnavailable = MutableStateFlow(false)

    private val calendar = currentDate.observeCalendar(context)
    private val keys = combine(credentials.steamIdFlow, calendar) { account, (today, zone) ->
        MomentumKey(account.orEmpty(), today, zone)
    }.distinctUntilChanged().onEach { key ->
        if (uiState.value.read.key?.accountId != key.accountId) detailUnavailable.value = false
    }

    val uiState: StateFlow<MomentumUiState> = observePersonalMomentum(keys, repository::observe)
        .flowOn(Dispatchers.Default).combine(detailUnavailable) { read, unavailable -> MomentumUiState(read, unavailable) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MomentumUiState())

    fun openGame(appId: Long, onOpen: (Long) -> Unit) {
        viewModelScope.launch {
            val account = uiState.value.read.key?.accountId.orEmpty()
            val opened = openMomentumDetail(appId, account,
                currentAccount = { credentials.currentCredentials()?.steamId },
                visible = repository::detailAvailable,
                stillDisplayed = { uiState.value.read.key?.accountId == account &&
                    uiState.value.read.result?.candidates?.any { it.game.appId == appId } == true },
                onOpen = onOpen)
            detailUnavailable.value = !opened
        }
    }
}
