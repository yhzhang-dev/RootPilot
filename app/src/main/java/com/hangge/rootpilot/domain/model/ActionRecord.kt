package com.hangge.rootpilot.domain.model

enum class ActionType {
    SNAPSHOT, FREEZE, UNFREEZE, FORCE_STOP, BOOT,
    LAUNCH, TAP, SWIPE, KEY_EVENT, TEXT_INPUT,
}

data class ActionRecord(
    val id: Long = 0,
    val createdAt: Long,
    val packageName: String?,
    val action: ActionType,
    val success: Boolean,
    val message: String,
)

data class ApiRequestLog(
    val id: Long = 0,
    val createdAt: Long,
    val method: String,
    val path: String,
    val statusCode: Int,
    val durationMs: Long,
)
