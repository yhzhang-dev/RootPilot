package com.hangge.rootpilot.data.repository

import android.graphics.Bitmap
import com.hangge.rootpilot.data.system.AppInfoSource
import com.hangge.rootpilot.domain.model.AppInfo

class AppRepository(private val source: AppInfoSource) {

    suspend fun loadApps(): List<AppInfo> = source.loadApps()

    suspend fun loadApp(packageName: String): AppInfo? = source.loadApp(packageName)

    suspend fun loadIcon(packageName: String): Bitmap? = source.loadIcon(packageName)
}
