package com.hangge.rootpilot.data.root

data class RootResult(
    val success: Boolean,
    val exitCode: Int,
    val output: String,
)
