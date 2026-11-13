package com.hangge.rootpilot.data.export

import com.hangge.rootpilot.domain.model.ActionRecord
import com.hangge.rootpilot.domain.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFormatterTest {

    private val record = ActionRecord(
        id = 3,
        createdAt = 1_700_000_000_000,
        packageName = "com.example.app",
        action = ActionType.FREEZE,
        success = true,
        message = "已冻结 com.example.app",
    )

    @Test
    fun `csv has a header and one row per record`() {
        val csv = ExportFormatter.actionsToCsv(listOf(record))

        val lines = csv.trim().split("\r\n")
        assertEquals(2, lines.size)
        assertEquals("createdAt,time,packageName,action,success,message", lines[0])
        assertTrue(lines[1].contains("com.example.app"))
        assertTrue(lines[1].contains("FREEZE"))
    }

    @Test
    fun `csv escapes commas quotes and newlines`() {
        val tricky = record.copy(message = "a,b \"quoted\"\nnext")
        val csv = ExportFormatter.actionsToCsv(listOf(tricky))

        assertTrue(csv.contains("\"a,b \"\"quoted\"\"\nnext\""))
    }

    @Test
    fun `empty history still yields a header`() {
        assertEquals("createdAt,time,packageName,action,success,message\r\n", ExportFormatter.actionsToCsv(emptyList()))
    }

    @Test
    fun `snapshot csv keeps nullable columns empty`() {
        val csv = ExportFormatter.snapshotsToCsv(emptyList())
        assertTrue(csv.startsWith("capturedAt,time,batteryPercent"))
        assertFalse(csv.contains("null"))
    }
}
