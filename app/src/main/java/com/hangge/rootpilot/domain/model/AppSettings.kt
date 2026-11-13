package com.hangge.rootpilot.domain.model

enum class AppTheme { SYSTEM, LIGHT, DARK }

/** Material settings, persisted in SharedPreferences (course chapter 6.4). */
data class AppSettings(
    val safeMode: Boolean = true,
    val agentControl: Boolean = false,
    val agentPort: Int = DEFAULT_AGENT_PORT,
    val theme: AppTheme = AppTheme.SYSTEM,
) {
    companion object {
        const val DEFAULT_AGENT_PORT = 8765
        val PORT_RANGE = 1024..65535
    }
}
