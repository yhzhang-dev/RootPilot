package com.hangge.rootpilot.data.root

/**
 * Package names are the only user-controlled fragment that reaches `su`. They must match the
 * Android package grammar exactly; everything else is built from a fixed template.
 */
object PackageNames {
    private val PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    const val MAX_LENGTH = 255

    fun isValid(packageName: String): Boolean =
        packageName.length in 3..MAX_LENGTH && PATTERN.matches(packageName)

    fun requireValid(packageName: String) {
        require(isValid(packageName)) { "INVALID_PACKAGE" }
    }
}
