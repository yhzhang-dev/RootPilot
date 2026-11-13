package com.hangge.rootpilot.domain

import com.hangge.rootpilot.data.local.ActionRecordDao
import com.hangge.rootpilot.data.local.ActionRecordEntity
import com.hangge.rootpilot.data.prefs.SettingsStore
import com.hangge.rootpilot.data.root.PackageNames
import com.hangge.rootpilot.data.root.RootCommand
import com.hangge.rootpilot.data.root.RootResult
import com.hangge.rootpilot.data.root.RootShell
import com.hangge.rootpilot.data.system.AppProtection
import com.hangge.rootpilot.domain.model.ActionType

/**
 * The single execution path for freeze / unfreeze / force-stop. Both the Compose UI and the
 * loopback HTTP API call this class; every attempt, including validation failures, becomes an
 * ActionRecordEntity row so History always reflects what was tried.
 */
class ActionExecutor(
    private val rootShell: RootShell,
    private val settings: SettingsStore,
    private val protection: AppProtection,
    private val actionDao: ActionRecordDao,
) {

    suspend fun freeze(packageName: String): ActionResult =
        guarded(ActionType.FREEZE, packageName, requireSafeModeOff = true) { RootCommand.DisableUser(it) }

    suspend fun unfreeze(packageName: String): ActionResult =
        guarded(ActionType.UNFREEZE, packageName, requireSafeModeOff = false) { RootCommand.Enable(it) }

    suspend fun forceStop(packageName: String): ActionResult =
        guarded(ActionType.FORCE_STOP, packageName, requireSafeModeOff = true) { RootCommand.ForceStop(it) }

    suspend fun recordBoot(): ActionResult {
        val result = ActionResult.ok("", "设备已启动")
        actionDao.insert(
            ActionRecordEntity(
                createdAt = System.currentTimeMillis(),
                packageName = null,
                action = ActionType.BOOT.name,
                success = true,
                message = result.message,
            ),
        )
        return result
    }

    /**
     * Rebuilds the set of packages RootPilot froze by replaying its own successful actions, then
     * unfreezes each one that is still installed. Unfreeze is never blocked by SafeMode.
     */
    suspend fun unfreezeAllRootPilotFrozen(): UnfreezeAllResult {
        val frozen = frozenPackages()
        var attempted = 0
        var succeeded = 0
        frozen.forEach { packageName ->
            if (!protection.exists(packageName)) return@forEach
            attempted++
            if (unfreeze(packageName).success) succeeded++
        }
        return UnfreezeAllResult(attempted = attempted, succeeded = succeeded)
    }

    suspend fun frozenPackages(): List<String> {
        val frozen = LinkedHashSet<String>()
        actionDao.all().forEach { entity ->
            val packageName = entity.packageName ?: return@forEach
            when (entity.action) {
                ActionType.FREEZE.name -> if (entity.success) frozen.add(packageName)
                ActionType.UNFREEZE.name -> frozen.remove(packageName)
                else -> Unit
            }
        }
        return frozen.toList()
    }

    private suspend fun guarded(
        action: ActionType,
        packageName: String,
        requireSafeModeOff: Boolean,
        command: (String) -> RootCommand,
    ): ActionResult {
        validate(action, packageName, requireSafeModeOff)?.let { rejection ->
            record(action, packageName, rejection)
            return rejection
        }

        val createdAt = System.currentTimeMillis()
        val pendingId = actionDao.insert(
            ActionRecordEntity(
                createdAt = createdAt,
                packageName = packageName,
                action = action.name,
                success = false,
                message = "执行中…",
            ),
        )

        val rootResult = rootShell.run(command(packageName))
        val result = if (rootResult.success) {
            ActionResult.ok(packageName, successMessage(action, packageName))
        } else {
            ActionResult.failure(
                code = ActionErrorCode.COMMAND_FAILED,
                message = failureMessage(rootResult),
                packageName = packageName,
            )
        }
        actionDao.update(
            ActionRecordEntity(
                id = pendingId,
                createdAt = createdAt,
                packageName = packageName,
                action = action.name,
                success = result.success,
                message = result.message,
            ),
        )
        return result
    }

    private suspend fun validate(
        action: ActionType,
        packageName: String,
        requireSafeModeOff: Boolean,
    ): ActionResult? {
        if (!PackageNames.isValid(packageName)) {
            return ActionResult.failure(ActionErrorCode.INVALID_PACKAGE, "包名无效：$packageName", packageName)
        }
        if (!protection.exists(packageName)) {
            return ActionResult.failure(ActionErrorCode.NOT_FOUND, "应用未安装：$packageName", packageName)
        }
        if (action != ActionType.UNFREEZE) {
            if (protection.isSystem(packageName)) {
                return ActionResult.failure(ActionErrorCode.SYSTEM_APP, "该应用是系统应用", packageName)
            }
            protection.protectedReason(packageName)?.let { reason ->
                return ActionResult.failure(
                    ActionErrorCode.COMMAND_FAILED,
                    "已保护（$reason），禁止冻结或强制停止",
                    packageName,
                )
            }
        }
        if (requireSafeModeOff && settings.current().safeMode) {
            return ActionResult.failure(ActionErrorCode.SAFE_MODE, "SafeMode 已开启", packageName)
        }
        if (!rootShell.isRootAvailable()) {
            return ActionResult.failure(ActionErrorCode.ROOT_UNAVAILABLE, "未授权 Root", packageName)
        }
        return null
    }

    private suspend fun record(action: ActionType, packageName: String, result: ActionResult) {
        actionDao.insert(
            ActionRecordEntity(
                createdAt = System.currentTimeMillis(),
                packageName = packageName,
                action = action.name,
                success = result.success,
                message = result.message,
            ),
        )
    }

    private fun successMessage(action: ActionType, packageName: String): String = when (action) {
        ActionType.FREEZE -> "已冻结 $packageName"
        ActionType.UNFREEZE -> "已解冻 $packageName"
        ActionType.FORCE_STOP -> "已强制停止 $packageName"
        else -> "完成 $packageName"
    }

    private fun failureMessage(result: RootResult): String {
        val output = result.output.trim()
        return if (output.isEmpty()) {
            "命令失败（exit ${result.exitCode}）"
        } else {
            "命令失败（exit ${result.exitCode}）：${output.take(200)}"
        }
    }
}

data class UnfreezeAllResult(val attempted: Int, val succeeded: Int)
