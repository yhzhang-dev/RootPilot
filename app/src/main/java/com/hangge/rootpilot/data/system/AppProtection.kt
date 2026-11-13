package com.hangge.rootpilot.data.system

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Packages that must never be frozen or force-stopped: RootPilot itself, the active home app, and
 * enabled input methods. Detection failures fall back to "not protected"; the caller still asks for
 * an explicit confirmation before any freeze.
 */
class AppProtection(private val context: Context) {

    fun exists(packageName: String): Boolean =
        runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess

    fun isSystem(packageName: String): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(false)

    fun isSelf(packageName: String): Boolean = packageName == context.packageName

    fun protectedReason(packageName: String): String? = when {
        isSelf(packageName) -> "RootPilot 自身"
        isHomeApp(packageName) -> "当前桌面启动器"
        isEnabledInputMethod(packageName) -> "已启用的输入法"
        else -> null
    }

    fun isHomeApp(packageName: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        resolved?.activityInfo?.packageName == packageName
    }.getOrDefault(false)

    fun isEnabledInputMethod(packageName: String): Boolean = runCatching {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS,
        ) ?: return@runCatching false
        enabled.split(':').any { component -> component.substringBefore('/') == packageName }
    }.getOrDefault(false)
}
