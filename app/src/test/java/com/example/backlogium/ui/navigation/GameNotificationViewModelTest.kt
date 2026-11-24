package com.example.backlogium.ui.navigation

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.example.backlogium.GameNotificationTarget
import com.example.backlogium.data.repo.GameNotificationTargetValidator
import com.example.backlogium.data.setup.SetupAccounts
import com.example.backlogium.data.setup.SetupOwner
import com.example.backlogium.work.setup.FirstRunSetupGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GameNotificationViewModelTest {
    @Test
    fun requestWaitsForSetupToExitWhenItStartsDuringTargetValidation() = runTest {
        val setupActive = MutableStateFlow(false)
        val routeReady = MutableStateFlow(true)
        val target = GameNotificationTarget(ACCOUNT, 42L)
        val validator = PausedValidator(target.appId)
        val viewModel = viewModel(setupActive, validator)
        val opened = mutableListOf<GameNotificationTarget>()

        backgroundScope.launch {
            viewModel.collectOpenRequests(routeReady) { opened += it }
        }
        viewModel.request(target)
        runCurrent()
        assertEquals(target, validator.entered.await())

        setupActive.value = true
        routeReady.value = false
        validator.release.complete(true)
        runCurrent()

        assertTrue(opened.isEmpty())
        assertEquals(target, viewModel.requests.first())

        setupActive.value = false
        routeReady.value = true
        runCurrent()
        assertEquals(listOf(target), opened)
        assertEquals(listOf(target, target), validator.validated)
    }

    @Test
    fun requestIsRevalidatedAfterSetupInterruptsTargetValidation() = runTest {
        val setupActive = MutableStateFlow(false)
        val routeReady = MutableStateFlow(true)
        val target = GameNotificationTarget(ACCOUNT, 42L)
        val validator = PausedValidator(target.appId)
        val viewModel = viewModel(setupActive, validator)
        val opened = mutableListOf<GameNotificationTarget>()

        backgroundScope.launch {
            viewModel.collectOpenRequests(routeReady) { opened += it }
        }
        viewModel.request(target)
        runCurrent()
        assertEquals(target, validator.entered.await())

        setupActive.value = true
        routeReady.value = false
        validator.release.complete(true)
        runCurrent()

        assertTrue(opened.isEmpty())
        assertEquals(target, viewModel.requests.first())

        validator.eligible = false
        setupActive.value = false
        routeReady.value = true
        runCurrent()

        assertTrue(opened.isEmpty())
        assertEquals(listOf(target, target), validator.validated)
    }

    @Test
    fun newerRequestSupersedesOnePausedInTargetValidation() = runTest {
        val routeReady = MutableStateFlow(true)
        val oldTarget = GameNotificationTarget(ACCOUNT, 42L)
        val newTarget = GameNotificationTarget(ACCOUNT, 43L)
        val validator = PausedValidator(oldTarget.appId)
        val viewModel = viewModel(MutableStateFlow(false), validator)
        val opened = mutableListOf<GameNotificationTarget>()

        backgroundScope.launch {
            viewModel.collectOpenRequests(routeReady) { opened += it }
        }
        viewModel.request(oldTarget)
        runCurrent()
        assertEquals(oldTarget, validator.entered.await())

        viewModel.request(newTarget)
        runCurrent()

        assertEquals(listOf(oldTarget, newTarget), validator.validated)
        assertEquals(listOf(newTarget), opened)
    }

    private fun viewModel(
        setupActive: Flow<Boolean>,
        validator: GameNotificationTargetValidator,
    ) = GameNotificationViewModel(
        savedState = SavedStateHandle(),
        accounts = FakeAccounts,
        setup = FakeSetup(setupActive),
        targets = validator,
    )

    private class PausedValidator(private val pausedAppId: Long) : GameNotificationTargetValidator {
        val entered = CompletableDeferred<GameNotificationTarget>()
        val release = CompletableDeferred<Boolean>()
        val validated = mutableListOf<GameNotificationTarget>()
        var eligible = true

        override suspend fun canOpen(account: String, appId: Long): Boolean {
            val target = GameNotificationTarget(account, appId)
            validated += target
            if (appId == pausedAppId) {
                entered.complete(target)
                if (!release.await()) return false
            }
            return eligible
        }
    }

    private object FakeAccounts : SetupAccounts {
        override val owners: Flow<SetupOwner?> = flowOf(SetupOwner(ACCOUNT, 0L))

        override suspend fun <T> ifCurrent(owner: SetupOwner, block: suspend () -> T): T? =
            if (owner == SetupOwner(ACCOUNT, 0L)) block() else null
    }

    private class FakeSetup(
        override val firstRunSetupActive: Flow<Boolean>,
    ) : FirstRunSetupGateway {
        override suspend fun claimFirstRunSetup() = Unit
        override suspend fun releaseFirstRunSetup() = Unit
    }

    private companion object {
        const val ACCOUNT = "fixture-account"
    }
}
