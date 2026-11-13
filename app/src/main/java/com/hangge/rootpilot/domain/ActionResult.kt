package com.hangge.rootpilot.domain

/**
 * The single outcome type returned by every privileged action. Codes mirror the Agent API
 * error codes so the UI and the HTTP API can report the same reason.
 */
data class ActionResult(
    val success: Boolean,
    val code: ActionErrorCode?,
    val message: String,
    val packageName: String? = null,
) {
    companion object {
        fun ok(packageName: String, message: String) =
            ActionResult(success = true, code = null, message = message, packageName = packageName)

        fun failure(code: ActionErrorCode, message: String, packageName: String? = null) =
            ActionResult(success = false, code = code, message = message, packageName = packageName)
    }
}

enum class ActionErrorCode {
    ROOT_UNAVAILABLE,
    SAFE_MODE,
    AGENT_CONTROL_OFF,
    INVALID_PACKAGE,
    SYSTEM_APP,
    NOT_FOUND,
    COMMAND_FAILED,
}

enum class ActionSource { UI, API, BOOT }
