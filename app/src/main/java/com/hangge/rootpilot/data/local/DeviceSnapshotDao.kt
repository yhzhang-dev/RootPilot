package com.hangge.rootpilot.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceSnapshotDao {
    @Query("SELECT * FROM device_snapshots ORDER BY capturedAt DESC, id DESC")
    fun observeAll(): Flow<List<DeviceSnapshotEntity>>

    @Query("SELECT * FROM device_snapshots ORDER BY capturedAt DESC, id DESC LIMIT 1")
    fun observeLatest(): Flow<DeviceSnapshotEntity?>

    @Query("SELECT * FROM device_snapshots ORDER BY capturedAt DESC, id DESC")
    suspend fun all(): List<DeviceSnapshotEntity>

    @Query("SELECT * FROM device_snapshots ORDER BY capturedAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DeviceSnapshotEntity>

    @Query("SELECT COUNT(*) FROM device_snapshots")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: DeviceSnapshotEntity): Long

    @Query("DELETE FROM device_snapshots")
    suspend fun clear()
}
