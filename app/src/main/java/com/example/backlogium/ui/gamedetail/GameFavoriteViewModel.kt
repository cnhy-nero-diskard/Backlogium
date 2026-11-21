package com.example.backlogium.ui.gamedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.GamePreferenceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class GameFavoriteViewModel @Inject constructor(preferences: GamePreferenceRepository) : ViewModel() {
    private val controller = FavoriteActionController(viewModelScope, preferences::favorite, preferences::setFavorite)
    val state = controller.state
    fun show(appId: Long) = controller.show(appId)
    fun toggle() = controller.toggle()
}
