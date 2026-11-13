package com.hangge.rootpilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.hangge.rootpilot.data.system.BatteryParser
import com.hangge.rootpilot.domain.model.BatteryState

/**
 * Dynamic battery receiver (course chapter 8). Registered while the process is alive so the
 * Dashboard reflects charger plug/unplug without polling.
 */
class BatteryReceiver(
    private val onBattery: (BatteryState) -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        onBattery(BatteryParser.from(intent))
    }

    companion object {
        val FILTER: IntentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

        fun register(context: Context, receiver: BatteryReceiver) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                FILTER,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }

        fun unregister(context: Context, receiver: BatteryReceiver) {
            runCatching { context.unregisterReceiver(receiver) }
        }

        fun stickyState(context: Context): BatteryState =
            BatteryParser.from(context.registerReceiver(null, FILTER))
    }
}
