package com.hangge.rootpilot.domain

import android.content.Context
import android.content.Intent
import com.hangge.rootpilot.data.local.ActionRecordDao
import com.hangge.rootpilot.data.local.ActionRecordEntity
import com.hangge.rootpilot.data.prefs.SettingsStore
import com.hangge.rootpilot.data.root.PackageNames
import com.hangge.rootpilot.data.root.RootCommand
import com.hangge.rootpilot.data.root.RootShell
import com.hangge.rootpilot.domain.model.ActionType
import java.io.File

/** Structured device automation for the desktop Agent. No arbitrary shell string is accepted. */
class DeviceAgentController(
    private val context: Context,
    private val settings: SettingsStore,
    private val rootShell: RootShell,
    private val actionDao: ActionRecordDao,
) {

    suspend fun launchApp(packageName: String): ActionResult {
        if (!PackageNames.isValid(packageName)) return failure(ActionType.LAUNCH, "包名无效：$packageName")
        if (!settings.current().agentControl) return failure(ActionType.LAUNCH, "未开启 Agent 控制")
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return failure(ActionType.LAUNCH, "应用不存在或没有启动入口：$packageName")
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            success(ActionType.LAUNCH, "已启动 $packageName", packageName)
        }.getOrElse { error -> failure(ActionType.LAUNCH, "启动失败：${error.message}", packageName) }
    }

    suspend fun tap(x: Int, y: Int): ActionResult = runRoot(ActionType.TAP, RootCommand.Tap(x, y), "点击 ($x,$y)")

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): ActionResult =
        runRoot(ActionType.SWIPE, RootCommand.Swipe(x1, y1, x2, y2, durationMs), "滑动 ($x1,$y1) → ($x2,$y2)")

    suspend fun keyEvent(keyCode: Int): ActionResult =
        runRoot(ActionType.KEY_EVENT, RootCommand.KeyEvent(keyCode), "按键 $keyCode")

    suspend fun inputText(text: String): ActionResult =
        runRoot(ActionType.TEXT_INPUT, RootCommand.InputText(text), "输入文本（${text.length} 字符）")

    suspend fun foregroundPackage(): String? {
        val result = rootShell.run(RootCommand.CurrentActivity)
        if (!result.success) return null
        return FOREGROUND_PACKAGE.find(result.output)?.groupValues?.getOrNull(1)
    }

    suspend fun captureScreen(): ByteArray {
        requireRootRead()
        val result = rootShell.run(RootCommand.CaptureScreen)
        check(result.success) { result.output.ifBlank { "截图失败" } }
        val file = File(RootCommand.CaptureScreen.OUTPUT_PATH)
        check(file.isFile) { "截图文件未生成" }
        check(file.length() <= MAX_SCREENSHOT_BYTES) { "截图文件过大" }
        return file.readBytes()
    }

    suspend fun dumpUiHierarchy(): String {
        requireRootRead()
        val result = rootShell.run(RootCommand.DumpUiHierarchy)
        check(result.success) { result.output.ifBlank { "UI 层级采集失败" } }
        val file = File(RootCommand.DumpUiHierarchy.OUTPUT_PATH)
        check(file.isFile) { "UI 层级文件未生成" }
        check(file.length() <= MAX_UI_XML_BYTES) { "UI 层级文件过大" }
        return file.readText()
    }

    private suspend fun requireRootRead() {
        check(rootShell.isRootAvailable()) { "未授权 Root" }
    }

    private suspend fun runRoot(type: ActionType, command: RootCommand, label: String): ActionResult {
        if (!settings.current().agentControl) return failure(type, "未开启 Agent 控制")
        if (settings.current().safeMode) return failure(type, "SafeMode 已开启")
        if (!rootShell.isRootAvailable()) return failure(type, "未授权 Root")
        val root = rootShell.run(command)
        return if (root.success) success(type, "已执行：$label") else failure(type, "执行失败：${root.output.take(200)}")
    }

    private suspend fun success(type: ActionType, message: String, packageName: String? = null): ActionResult {
        record(type, true, message, packageName)
        return ActionResult.ok(packageName.orEmpty(), message)
    }

    private suspend fun failure(type: ActionType, message: String, packageName: String? = null): ActionResult {
        record(type, false, message, packageName)
        val code = when {
            message.contains("SafeMode") -> ActionErrorCode.SAFE_MODE
            message.contains("Agent 控制") -> ActionErrorCode.AGENT_CONTROL_OFF
            message.contains("Root") -> ActionErrorCode.ROOT_UNAVAILABLE
            message.contains("包名") -> ActionErrorCode.INVALID_PACKAGE
            message.contains("不存在") -> ActionErrorCode.NOT_FOUND
            else -> ActionErrorCode.COMMAND_FAILED
        }
        return ActionResult.failure(code, message, packageName)
    }

    private suspend fun record(type: ActionType, success: Boolean, message: String, packageName: String?) {
        actionDao.insert(
            ActionRecordEntity(
                createdAt = System.currentTimeMillis(),
                packageName = packageName,
                action = type.name,
                success = success,
                message = message,
            ),
        )
    }

    companion object {
        private const val MAX_SCREENSHOT_BYTES = 12 * 1024 * 1024
        private const val MAX_UI_XML_BYTES = 2 * 1024 * 1024
        private val FOREGROUND_PACKAGE = Regex("(?:mResumedActivity|topResumedActivity).*? ([A-Za-z][A-Za-z0-9_.]+)/(?:[A-Za-z0-9_.$]+)")
    }
}
