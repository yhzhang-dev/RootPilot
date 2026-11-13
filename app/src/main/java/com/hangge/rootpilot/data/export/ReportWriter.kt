package com.hangge.rootpilot.data.export

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Course chapter 6.2 (file storage): every export also leaves a copy in
 * `filesDir/reports/report-<timestamp>.json` (or .csv) before the user saves it through SAF.
 */
class ReportWriter(private val context: Context) {

    suspend fun writeInternalReport(fileName: String, content: String): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, REPORTS_DIR).apply { mkdirs() }
        val file = File(directory, fileName)
        file.writeText(content)
        file
    }

    fun reportsDirectory(): File = File(context.filesDir, REPORTS_DIR)

    companion object {
        const val REPORTS_DIR = "reports"
    }
}
