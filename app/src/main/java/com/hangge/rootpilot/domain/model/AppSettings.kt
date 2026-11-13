package com.hangge.rootpilot.domain.model

enum class AppTheme { SYSTEM, LIGHT, DARK }

/** Material settings, persisted in SharedPreferences (course chapter 6.4). */
data class AppSettings(
    val safeMode: Boolean = true,
    val agentControl: Boolean = false,
    val agentPort: Int = DEFAULT_AGENT_PORT,
    val theme: AppTheme = AppTheme.SYSTEM,
    /**
     * Opt-in LAN access: the Agent API binds 0.0.0.0 instead of 127.0.0.1 so a
     * desktop on the same Wi-Fi can reach it without an adb forward.
     */
    val lanAccess: Boolean = false,
    /** Bearer-token check for the Agent API. Off is only for a trusted private LAN. */
    val requireToken: Boolean = true,
) {
    companion object {
        const val DEFAULT_AGENT_PORT = 8765
        val PORT_RANGE = 1024..65535
    }
}
