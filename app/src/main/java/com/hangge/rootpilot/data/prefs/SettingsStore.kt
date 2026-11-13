package com.hangge.rootpilot.data.prefs

import com.hangge.rootpilot.domain.model.AppSettings
import com.hangge.rootpilot.domain.model.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings backed by SharedPreferences (course chapter 6.4). Values are mirrored in a StateFlow so
 * the UI reacts immediately; the store remains the source of truth after a process restart.
 */
class SettingsStore(private val store: KeyValueStore) {

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun current(): AppSettings = _settings.value

    fun setSafeMode(enabled: Boolean) {
        store.putBoolean(KEY_SAFE_MODE, enabled)
        _settings.value = _settings.value.copy(safeMode = enabled)
    }

    fun setAgentControl(enabled: Boolean) {
        store.putBoolean(KEY_AGENT_CONTROL, enabled)
        _settings.value = _settings.value.copy(agentControl = enabled)
    }

    fun setAgentPort(port: Int): Boolean {
        if (port !in AppSettings.PORT_RANGE) return false
        store.putInt(KEY_AGENT_PORT, port)
        _settings.value = _settings.value.copy(agentPort = port)
        return true
    }

    fun setTheme(theme: AppTheme) {
        store.putString(KEY_THEME, theme.name)
        _settings.value = _settings.value.copy(theme = theme)
    }

    fun setLanAccess(enabled: Boolean) {
        store.putBoolean(KEY_LAN_ACCESS, enabled)
        _settings.value = _settings.value.copy(lanAccess = enabled)
    }

    fun setRequireToken(enabled: Boolean) {
        store.putBoolean(KEY_REQUIRE_TOKEN, enabled)
        _settings.value = _settings.value.copy(requireToken = enabled)
    }

    private fun read(): AppSettings = AppSettings(
        safeMode = store.getBoolean(KEY_SAFE_MODE, true),
        agentControl = store.getBoolean(KEY_AGENT_CONTROL, false),
        agentPort = store.getInt(KEY_AGENT_PORT, AppSettings.DEFAULT_AGENT_PORT)
            .takeIf { it in AppSettings.PORT_RANGE } ?: AppSettings.DEFAULT_AGENT_PORT,
        theme = runCatching { AppTheme.valueOf(store.getString(KEY_THEME, AppTheme.SYSTEM.name) ?: AppTheme.SYSTEM.name) }
            .getOrDefault(AppTheme.SYSTEM),
        lanAccess = store.getBoolean(KEY_LAN_ACCESS, false),
        requireToken = store.getBoolean(KEY_REQUIRE_TOKEN, true),
    )

    companion object {
        const val KEY_AGENT_PORT = "agent_port"
        const val KEY_SAFE_MODE = "safe_mode"
        const val KEY_AGENT_CONTROL = "agent_control"
        const val KEY_THEME = "theme"
        const val KEY_LAN_ACCESS = "lan_access"
        const val KEY_REQUIRE_TOKEN = "require_token"
        const val PREFS_NAME = "rootpilot_settings"
    }
}
