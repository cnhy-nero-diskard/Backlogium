package com.example.backlogium.ui

import androidx.lifecycle.ViewModel
import com.example.backlogium.data.network.ConnectivityObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
internal class ConnectivityViewModel @Inject constructor(observer: ConnectivityObserver) : ViewModel() {
    val status = observer.status
}
