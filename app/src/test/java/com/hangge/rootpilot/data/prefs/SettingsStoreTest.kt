package com.hangge.rootpilot.data.prefs

import com.hangge.rootpilot.domain.model.AppTheme
import com.hangge.rootpilot.testing.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreTest {

    @Test
    fun `defaults are safe`() {
        val store = SettingsStore(InMemoryKeyValueStore())

        val settings = store.current()
        assertTrue("SafeMode must default to on", settings.safeMode)
        assertFalse("Agent control must default to off", settings.agentControl)
        assertEquals(8765, settings.agentPort)
        assertEquals(AppTheme.SYSTEM, settings.theme)
    }

    @Test
    fun `values survive a new store instance`() {
        val backing = InMemoryKeyValueStore()
        SettingsStore(backing).apply {
            setSafeMode(false)
            setAgentControl(true)
            setAgentPort(9000)
            setTheme(AppTheme.DARK)
        }

        val reloaded = SettingsStore(backing).current()
        assertFalse(reloaded.safeMode)
        assertTrue(reloaded.agentControl)
        assertEquals(9000, reloaded.agentPort)
        assertEquals(AppTheme.DARK, reloaded.theme)
    }

    @Test
    fun `invalid port is rejected and previous value kept`() {
        val store = SettingsStore(InMemoryKeyValueStore())

        assertFalse(store.setAgentPort(80))
        assertFalse(store.setAgentPort(70_000))
        assertEquals(8765, store.current().agentPort)
        assertTrue(store.setAgentPort(1024))
        assertEquals(1024, store.current().agentPort)
    }

    @Test
    fun `corrupted theme falls back to system`() {
        val backing = InMemoryKeyValueStore()
        backing.putString(SettingsStore.KEY_THEME, "NEON")

        assertEquals(AppTheme.SYSTEM, SettingsStore(backing).current().theme)
    }
}
