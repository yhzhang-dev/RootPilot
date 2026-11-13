package com.hangge.rootpilot.data.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageNamesTest {

    @Test
    fun `accepts valid package names`() {
        listOf(
            "com.example.app",
            "com.hangge.rootpilot",
            "a.b",
            "com.example.app_2",
        ).forEach { packageName ->
            assertTrue("should accept $packageName", PackageNames.isValid(packageName))
        }
    }

    @Test
    fun `rejects shell metacharacters and malformed names`() {
        listOf(
            "",
            "a",
            "com",
            "com..example",
            "com.example; rm -rf /",
            "com.example && reboot",
            "com.example|id",
            "com.example/../x",
            " com.example",
            "com.example ",
            "1com.example",
            "com.example-app",
            "com.example\n",
        ).forEach { packageName ->
            assertFalse("should reject [$packageName]", PackageNames.isValid(packageName))
        }
    }

    @Test
    fun `requireValid throws for invalid names`() {
        val error = runCatching { PackageNames.requireValid("bad name") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("INVALID_PACKAGE", error?.message)
    }
}

class RootCommandTest {

    @Test
    fun `every command is a fixed template`() {
        assertEquals("id", RootCommand.Id.commandLine)
        assertEquals("getprop ro.product.model", RootCommand.DeviceModel.commandLine)
        assertEquals("getprop ro.build.version.release", RootCommand.AndroidRelease.commandLine)
        assertEquals("dumpsys battery", RootCommand.BatteryDump.commandLine)
        assertEquals("pm list packages -3", RootCommand.UserPackages.commandLine)
        assertEquals("pm disable-user --user 0 com.example.app", RootCommand.DisableUser("com.example.app").commandLine)
        assertEquals("pm enable com.example.app", RootCommand.Enable("com.example.app").commandLine)
        assertEquals("am force-stop com.example.app", RootCommand.ForceStop("com.example.app").commandLine)
        assertEquals("input tap 120 340", RootCommand.Tap(120, 340).commandLine)
        assertEquals("input swipe 10 20 30 40 500", RootCommand.Swipe(10, 20, 30, 40, 500).commandLine)
        assertEquals("input keyevent 3", RootCommand.KeyEvent(3).commandLine)
        assertEquals("input text hello%sworld", RootCommand.InputText("hello world").commandLine)
    }

    @Test
    fun `invalid package cannot construct a command`() {
        val error = runCatching { RootCommand.DisableUser("com.example; reboot") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `automation commands reject shell metacharacters and invalid coordinates`() {
        assertTrue(runCatching { RootCommand.InputText("hello; reboot") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { RootCommand.Tap(-1, 20) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { RootCommand.Swipe(0, 0, 100, 100, 10) }.exceptionOrNull() is IllegalArgumentException)
    }
}
