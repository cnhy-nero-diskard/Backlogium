package com.example.backlogium.ui.analytics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.PersonalMomentumRepository
import com.example.backlogium.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
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
    private val time: TimeProvider,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val detailUnavailable = MutableStateFlow(false)

    // Active-screen broadcasts restart the midnight delay after clock/zone changes, including
    // a zone change which leaves the local date unchanged. No timer, worker or wake lock is added.
    private val clockChanges = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { trySend(Unit) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(Unit)
        awaitClose { context.unregisterReceiver(receiver) }
    }
    private val calendar = clockChanges.flatMapLatest {
        currentDate.currentDate.map { it to time.zone() }
    }
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
