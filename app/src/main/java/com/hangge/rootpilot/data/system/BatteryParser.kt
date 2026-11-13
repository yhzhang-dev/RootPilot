package com.hangge.rootpilot.data.system

import android.content.Intent
import android.os.BatteryManager
import com.hangge.rootpilot.domain.model.BatteryState

/** Parses the sticky `ACTION_BATTERY_CHANGED` broadcast into a small immutable state. */
object BatteryParser {

    fun from(intent: Intent?, now: Long = System.currentTimeMillis()): BatteryState {
        if (intent == null) return BatteryState(updatedAt = now)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL,
            -> true

            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            -> false

            else -> null
        }
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperature = if (tenths != Int.MIN_VALUE) tenths / 10f else null
        return BatteryState(percent = percent, charging = charging, temperatureC = temperature, updatedAt = now)
    }
}
