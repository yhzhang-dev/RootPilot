package com.hangge.rootpilot.provider

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.hangge.rootpilot.RootPilotApplication
import com.hangge.rootpilot.data.local.RootPilotDatabase
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Read-only provider (course chapter 7). Exported=false: the HTTP API is the external seam, while
 * this provider demonstrates a custom ContentProvider and powers the in-app ContentResolver test.
 *
 * ContentProvider.query must return synchronously, so the Room query runs on Dispatchers.IO and the
 * rows are copied into an in-memory MatrixCursor; the database cursor is closed before returning.
 */
class RootPilotProvider : ContentProvider() {

    private lateinit var database: RootPilotDatabase
    private var matcher: UriMatcher = UriMatcher(UriMatcher.NO_MATCH)

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext as? RootPilotApplication ?: return false
        database = appContext.container.database
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_SNAPSHOTS, CODE_SNAPSHOTS)
            addURI(AUTHORITY, PATH_ACTIONS, CODE_ACTIONS)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val table = when (matcher.match(uri)) {
            CODE_SNAPSHOTS -> TABLE_SNAPSHOTS
            CODE_ACTIONS -> TABLE_ACTIONS
            else -> throw IllegalArgumentException("Unsupported URI: $uri")
        }
        return runBlocking(Dispatchers.IO) {
            database.openHelper.readableDatabase
                .query("SELECT * FROM $table ORDER BY id DESC")
                .use { cursor -> copyToMatrix(cursor, projection) }
        }
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        CODE_SNAPSHOTS -> TYPE_SNAPSHOTS
        CODE_ACTIONS -> TYPE_ACTIONS
        else -> throw IllegalArgumentException("Unsupported URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("RootPilotProvider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("RootPilotProvider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("RootPilotProvider is read-only")

    private fun copyToMatrix(cursor: Cursor, projection: Array<out String>?): MatrixCursor {
        val columns = projection ?: cursor.columnNames
        val matrix = MatrixCursor(columns, cursor.count)
        while (cursor.moveToNext()) {
            matrix.addRow(
                columns.map { column ->
                    val index = cursor.getColumnIndex(column)
                    if (index < 0) null else readColumn(cursor, index)
                },
            )
        }
        return matrix
    }

    private fun readColumn(cursor: Cursor, index: Int): Any? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
        else -> cursor.getString(index)
    }

    companion object {
        const val AUTHORITY = "com.hangge.rootpilot.provider"
        const val PATH_SNAPSHOTS = "snapshots"
        const val PATH_ACTIONS = "actions"
        const val CODE_SNAPSHOTS = 1
        const val CODE_ACTIONS = 2
        private const val TABLE_SNAPSHOTS = "device_snapshots"
        private const val TABLE_ACTIONS = "action_records"
        private const val TYPE_SNAPSHOTS = "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH_SNAPSHOTS"
        private const val TYPE_ACTIONS = "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH_ACTIONS"

        fun snapshotsUri(): Uri = "content://$AUTHORITY/$PATH_SNAPSHOTS".toUri()
        fun actionsUri(): Uri = "content://$AUTHORITY/$PATH_ACTIONS".toUri()
        fun contentUri(id: Long): Uri = ContentUris.withAppendedId(snapshotsUri(), id)
    }
}
