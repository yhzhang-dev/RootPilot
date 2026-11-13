package com.hangge.rootpilot.data.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import com.hangge.rootpilot.domain.model.AppInfo
import com.hangge.rootpilot.domain.model.AppPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PackageManager is used only here, always on Dispatchers.IO, so no Compose frame decodes icons or
 * enumerates packages on the main thread.
 */
class AppInfoSource(
    private val context: Context,
    private val iconCache: LruCache<String, Bitmap> = LruCache(160),
) {

    /** List requests skip permissions: reading them for ~500 packages is slow and only the detail
     *  screen needs them. */
    @Suppress("DEPRECATION")
    suspend fun loadApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        manager.getInstalledPackages(0)
            .mapNotNull { packageInfo ->
                runCatching { toAppInfo(manager, packageInfo, includePermissions = false) }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
    }

    suspend fun loadApp(packageName: String): AppInfo? = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        runCatching {
            @Suppress("DEPRECATION")
            val packageInfo = manager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            toAppInfo(manager, packageInfo, includePermissions = true)
        }.getOrNull()
    }

    suspend fun loadIcon(packageName: String): Bitmap? = withContext(Dispatchers.IO) {
        iconCache.get(packageName)?.let { return@withContext it }
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            drawable.toBitmap(width = ICON_SIZE_PX, height = ICON_SIZE_PX).also { bitmap ->
                iconCache.put(packageName, bitmap)
            }
        }.getOrNull()
    }

    fun isInstalled(packageName: String): Boolean =
        runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess

    fun clearIconCache() {
        iconCache.evictAll()
    }

    private fun toAppInfo(
        manager: PackageManager,
        packageInfo: PackageInfo,
        includePermissions: Boolean,
    ): AppInfo {
        val applicationInfo = packageInfo.applicationInfo
            ?: error("Missing applicationInfo for ${packageInfo.packageName}")
        val label = runCatching { applicationInfo.loadLabel(manager).toString() }
            .getOrDefault(packageInfo.packageName)
        val isSystem = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        return AppInfo(
            packageName = packageInfo.packageName,
            label = label,
            versionName = packageInfo.versionName ?: "—",
            versionCode = packageInfo.longVersionCode,
            targetSdk = applicationInfo.targetSdkVersion,
            minSdk = applicationInfo.minSdkVersion,
            installTime = packageInfo.firstInstallTime,
            isSystem = isSystem,
            isEnabled = isEnabled(manager, packageInfo.packageName, applicationInfo),
            permissions = if (includePermissions) readPermissions(manager, packageInfo) else emptyList(),
        )
    }

    private fun isEnabled(manager: PackageManager, packageName: String, info: ApplicationInfo): Boolean {
        if (!info.enabled) return false
        val setting = runCatching { manager.getApplicationEnabledSetting(packageName) }.getOrDefault(0)
        return setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
            setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    }

    private fun readPermissions(manager: PackageManager, packageInfo: PackageInfo): List<AppPermission> {
        val requested = packageInfo.requestedPermissions ?: return emptyList()
        return requested.map { permissionName ->
            val granted = manager.checkPermission(permissionName, packageInfo.packageName) ==
                PackageManager.PERMISSION_GRANTED
            val dangerous = runCatching {
                val info = manager.getPermissionInfo(permissionName, 0)
                (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) ==
                    PermissionInfo.PROTECTION_DANGEROUS
            }.getOrDefault(permissionName.startsWith("android.permission."))
            AppPermission(name = permissionName, dangerous = dangerous, granted = granted)
        }.sortedBy { it.name }
    }

    companion object {
        private const val ICON_SIZE_PX = 96
    }
}
