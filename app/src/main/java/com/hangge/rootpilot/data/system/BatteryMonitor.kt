package com.hangge.rootpilot.data.system

import android.content.Context
import com.hangge.rootpilot.domain.model.BatteryState
import com.hangge.rootpilot.receiver.BatteryReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide battery state fed by the dynamic [BatteryReceiver]. */
class BatteryMonitor(private val context: Context) {

    private val _state = MutableStateFlow(BatteryReceiver.stickyState(context))
    val state: StateFlow<BatteryState> = _state.asStateFlow()

    private val receiver = BatteryReceiver { updated -> _state.value = updated }
    private var started = false

    fun start() {
        if (started) return
        started = true
        runCatching { BatteryReceiver.register(context, receiver) }
    }

    fun stop() {
        if (!started) return
        started = false
        BatteryReceiver.unregister(context, receiver)
    }

    fun refresh() {
        _state.value = BatteryReceiver.stickyState(context)
    }
}
