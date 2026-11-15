package com.airplaytv.app.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Whether our UI is on screen, and "show the player" requests from the service. */
object AppVisibility {
    @Volatile var foreground: Boolean = false

    private val _playerRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val playerRequests: SharedFlow<Unit> = _playerRequests.asSharedFlow()

    fun requestPlayer() {
        _playerRequests.tryEmit(Unit)
    }
}
