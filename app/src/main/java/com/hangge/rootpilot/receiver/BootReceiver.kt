package com.hangge.rootpilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.hangge.rootpilot.RootPilotApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Static receiver (course chapter 8): a reboot leaves one BOOT record in History. Declared
 * exported=false, so only the system can deliver BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Some OEM ROMs replay BOOT_COMPLETED when a package is installed. Only a real, recent boot
        // should leave a record, so ignore anything delivered long after boot.
        if (SystemClock.elapsedRealtime() > FRESH_BOOT_WINDOW_MS) {
            Log.i(TAG, "Ignoring replayed BOOT_COMPLETED outside the boot window")
            return
        }
        val app = context.applicationContext as? RootPilotApplication ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.container.actionExecutor.recordBoot()
            } catch (error: Exception) {
                Log.w(TAG, "Unable to record boot: ${error.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val FRESH_BOOT_WINDOW_MS = 15 * 60 * 1000L
    }
}
