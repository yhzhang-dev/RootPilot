package com.hangge.rootpilot.domain

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.hangge.rootpilot.data.local.ActionRecordDao
import com.hangge.rootpilot.data.local.ActionRecordEntity
import com.hangge.rootpilot.data.prefs.SettingsStore
import com.hangge.rootpilot.data.root.PackageNames
import com.hangge.rootpilot.data.root.RootCommand
import com.hangge.rootpilot.data.root.RootShell
import com.hangge.rootpilot.domain.model.ActionType
import com.hangge.rootpilot.service.RootPilotAccessibilityService
import com.hangge.rootpilot.service.WindowSnapshot
import kotlinx.coroutines.delay
import java.io.File

/** Structured device automation for the desktop Agent. No arbitrary shell string is accepted. */
class DeviceAgentController(
    private val context: Context,
    private val settings: SettingsStore,
    private val rootShell: RootShell,
    private val actionDao: ActionRecordDao,
) {

    suspend fun launchApp(packageName: String, userId: Int? = null): ActionResult {
        if (!PackageNames.isValid(packageName)) return failure(ActionType.LAUNCH, "包名无效：$packageName")
        if (!settings.current().agentControl) return failure(ActionType.LAUNCH, "未开启 Agent 控制")
        // A cloned app (ColorOS 应用分身) is the same package in another user; startActivity from
        // this process always lands in user 0, so resolve the launcher component and start it there.
        if (userId != null) {
            val resolved = rootShell.run(RootCommand.ResolveLauncher(packageName, userId))
            if (!resolved.success) {
                return failure(ActionType.LAUNCH, "无法解析启动组件：${resolved.output.take(160)}", packageName)
            }
            val component = resolved.output.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.matches(COMPONENT_PATTERN) }
                ?: return failure(ActionType.LAUNCH, "应用没有启动入口：$packageName", packageName)
            return runRoot(
                ActionType.LAUNCH,
                RootCommand.LaunchComponent(component, userId),
                "启动 $packageName（user $userId）",
            )
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return failure(ActionType.LAUNCH, "应用不存在或没有启动入口：$packageName")
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            success(ActionType.LAUNCH, "已启动 $packageName", packageName)
        }.getOrElse { error -> failure(ActionType.LAUNCH, "启动失败：${error.message}", packageName) }
    }

    suspend fun tap(x: Int, y: Int, holdMs: Int = 0): ActionResult {
        if (holdMs <= 0) return runRoot(ActionType.TAP, RootCommand.Tap(x, y), "点击 ($x,$y)")
        val down = runRoot(ActionType.TAP, RootCommand.MotionEdge(down = true, x = x, y = y), "按下 ($x,$y)")
        if (!down.success) return down
        delay(holdMs.toLong().coerceIn(10, 3_000))
        return runRoot(
            ActionType.TAP,
            RootCommand.MotionEdge(down = false, x = x, y = y),
            "按住 ${holdMs}ms 后抬起 ($x,$y)",
        )
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): ActionResult =
        runRoot(ActionType.SWIPE, RootCommand.Swipe(x1, y1, x2, y2, durationMs), "滑动 ($x1,$y1) → ($x2,$y2)")

    suspend fun keyEvent(keyCode: Int): ActionResult =
        runRoot(ActionType.KEY_EVENT, RootCommand.KeyEvent(keyCode), "按键 $keyCode")

    /**
     * Fill the focused field. Preference order: ACTION_SET_TEXT through the accessibility bridge
     * (no key injection, any Unicode), then the rooted `input text` fallback for the apps that
     * hide their node tree (WeChat) or when the bridge is off.
     */
    suspend fun inputText(text: String): ActionResult {
        if (text.isEmpty() || text.length > MAX_TEXT_CHARS) {
            return failure(ActionType.TEXT_INPUT, "文本长度需在 1..$MAX_TEXT_CHARS")
        }
        if (!settings.current().agentControl) return failure(ActionType.TEXT_INPUT, "未开启 Agent 控制")
        if (settings.current().safeMode) return failure(ActionType.TEXT_INPUT, "SafeMode 已开启")
        val service = RootPilotAccessibilityService.instance
        if (service != null) {
            val written = runCatching { service.setText(text) }.getOrDefault(false)
            if (written) {
                return success(
                    ActionType.TEXT_INPUT,
                    "已写入文本（无障碍，${text.length} 字符）",
                    service.lastPackage,
                )
            }
        }
        return runRoot(ActionType.TEXT_INPUT, RootCommand.InputText(text), "输入文本（${text.length} 字符）")
    }

    /** Paste whatever is on the clipboard with Ctrl+V, bypassing the IME entirely. */
    suspend fun paste(): ActionResult = runRoot(ActionType.PASTE, RootCommand.Paste, "粘贴（Ctrl+V）")

    /**
     * Put text on the clipboard so a following [paste] can deliver it, Chinese included.
     *
     * Android 13+ only honours clipboard access for the focused app: a write issued while another
     * app is in front is dropped without an error. That silent drop is why a paste can bring back
     * the *previous* clip. So the foreground is checked first and the write is read back.
     */
    suspend fun setClipboard(text: String): ActionResult {
        if (text.isEmpty() || text.length > MAX_TEXT_CHARS) {
            return failure(ActionType.CLIPBOARD_SET, "文本长度需在 1..$MAX_TEXT_CHARS")
        }
        if (!settings.current().agentControl) return failure(ActionType.CLIPBOARD_SET, "未开启 Agent 控制")

        val foreground = foregroundApp()?.first
        if (foreground != null && foreground != context.packageName) {
            return failure(
                ActionType.CLIPBOARD_SET,
                "剪贴板只在 RootPilot 位于前台时才会生效（当前前台是 $foreground）。" +
                    "先 launch ${context.packageName} 写入剪贴板，再切回目标应用粘贴",
                code = ActionErrorCode.CLIPBOARD_UNAVAILABLE,
            )
        }

        return runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("RootPilot", text))
            val readBack = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
            if (readBack == text) {
                success(ActionType.CLIPBOARD_SET, "已写入剪贴板（${text.length} 字符，已校验）")
            } else {
                failure(
                    ActionType.CLIPBOARD_SET,
                    "剪贴板写入未生效（读回 ${readBack?.take(20) ?: "null"}），系统只在应用位于前台时接受写入",
                    code = ActionErrorCode.CLIPBOARD_UNAVAILABLE,
                )
            }
        }.getOrElse { error ->
            failure(
                ActionType.CLIPBOARD_SET,
                "剪贴板写入失败：${error.message ?: "unknown"}",
                code = ActionErrorCode.CLIPBOARD_UNAVAILABLE,
            )
        }
    }

    /** Package plus the Android user it runs in; vendor app clones live in a second user. */
    suspend fun foregroundApp(): Pair<String, Int?>? {
        val result = rootShell.run(RootCommand.CurrentActivity)
        if (!result.success) return null
        val match = FOREGROUND_ACTIVITY.find(result.output) ?: return null
        val packageName = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return null
        return packageName to match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    suspend fun foregroundPackage(): String? = foregroundApp()?.first

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

    /**
     * Node tree from the accessibility bridge when it is connected. Callers fall back to
     * [dumpUiHierarchy] (the rooted uiautomator dump) when this returns null.
     */
    suspend fun accessibilityTree(): String? {
        val service = RootPilotAccessibilityService.instance ?: return null
        return runCatching { service.dumpTreeXml() }.getOrNull()
    }

    /** Click a node by its `node-index` in the accessibility tree. No root and no coordinates. */
    suspend fun tapNode(nodeIndex: Int): ActionResult {
        if (!settings.current().agentControl) return failure(ActionType.TAP_NODE, "未开启 Agent 控制")
        if (settings.current().safeMode) return failure(ActionType.TAP_NODE, "SafeMode 已开启")
        val service = RootPilotAccessibilityService.instance
            ?: return failure(ActionType.TAP_NODE, "无障碍服务未连接")
        val clicked = runCatching { service.clickNode(nodeIndex) }.getOrDefault(false)
        return if (clicked) {
            success(ActionType.TAP_NODE, "已点击节点 #$nodeIndex", service.lastPackage)
        } else {
            failure(ActionType.TAP_NODE, "节点 #$nodeIndex 点击失败，可能已被界面刷新丢弃")
        }
    }

    /** Window geometry from the accessibility bridge; empty when it is not connected. */
    suspend fun windowSnapshots(): List<WindowSnapshot> =
        RootPilotAccessibilityService.instance
            ?.let { service -> runCatching { service.snapshotWindows() }.getOrNull() }
            .orEmpty()

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

    private suspend fun failure(
        type: ActionType,
        message: String,
        packageName: String? = null,
        code: ActionErrorCode? = null,
    ): ActionResult {
        record(type, false, message, packageName)
        val resolved = code ?: when {
            message.contains("SafeMode") -> ActionErrorCode.SAFE_MODE
            message.contains("Agent 控制") -> ActionErrorCode.AGENT_CONTROL_OFF
            message.contains("Root") -> ActionErrorCode.ROOT_UNAVAILABLE
            message.contains("包名") -> ActionErrorCode.INVALID_PACKAGE
            message.contains("不存在") -> ActionErrorCode.NOT_FOUND
            else -> ActionErrorCode.COMMAND_FAILED
        }
        return ActionResult.failure(resolved, message, packageName)
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
        private const val MAX_TEXT_CHARS = 4_000
        private val FOREGROUND_PACKAGE = Regex("(?:mResumedActivity|topResumedActivity).*? ([A-Za-z][A-Za-z0-9_.]+)/(?:[A-Za-z0-9_.$]+)")
        private val FOREGROUND_ACTIVITY =
            Regex("(?:topResumedActivity|mResumedActivity|ResumedActivity).*?u(\\d+)\\s+([A-Za-z][A-Za-z0-9_.]+)/")
        private val COMPONENT_PATTERN = Regex("[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+")
    }
}
