package com.hangge.rootpilot.ui.components

import com.hangge.rootpilot.domain.model.ActionType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())

private val fullTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) {
        "${bytes} ${units[index]}"
    } else {
        String.format(Locale.ROOT, "%.1f %s", value, units[index])
    }
}

fun formatTime(epochMillis: Long, full: Boolean = false): String =
    (if (full) fullTimeFormatter else timeFormatter).format(Instant.ofEpochMilli(epochMillis))

fun actionLabel(action: ActionType): String = when (action) {
    ActionType.SNAPSHOT -> "采集快照"
    ActionType.FREEZE -> "冻结"
    ActionType.UNFREEZE -> "解冻"
    ActionType.FORCE_STOP -> "强制停止"
    ActionType.BOOT -> "开机记录"
    ActionType.LAUNCH -> "启动应用"
    ActionType.TAP -> "屏幕点击"
    ActionType.SWIPE -> "屏幕滑动"
    ActionType.KEY_EVENT -> "按键输入"
    ActionType.TEXT_INPUT -> "文本输入"
    ActionType.TAP_NODE -> "节点点击"
    ActionType.CLIPBOARD_SET -> "写剪贴板"
    ActionType.PASTE -> "粘贴"
}

fun shortPackage(packageName: String, maxLength: Int = 34): String =
    if (packageName.length <= maxLength) packageName else packageName.take(maxLength - 1) + "…"
