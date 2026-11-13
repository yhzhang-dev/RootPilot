package com.hangge.rootpilot.domain.model

data class AppPermission(
    val name: String,
    val dangerous: Boolean,
    val granted: Boolean,
)

data class AppInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val targetSdk: Int,
    val minSdk: Int,
    val installTime: Long,
    val isSystem: Boolean,
    val isEnabled: Boolean,
    val permissions: List<AppPermission> = emptyList(),
)
