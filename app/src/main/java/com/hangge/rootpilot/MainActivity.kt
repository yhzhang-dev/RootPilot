package com.hangge.rootpilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hangge.rootpilot.ui.RootPilotApp
import com.hangge.rootpilot.ui.theme.RootPilotTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RootPilotApplication).container
        setContent {
            val settings by container.settingsStore.settings.collectAsStateWithLifecycle()
            RootPilotTheme(theme = settings.theme) {
                RootPilotApp(container = container)
            }
        }
    }
}
