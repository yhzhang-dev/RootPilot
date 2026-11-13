package com.hangge.rootpilot.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionRecordDao {
    @Query("SELECT * FROM action_records ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<ActionRecordEntity>>

    @Query("SELECT * FROM action_records ORDER BY createdAt DESC, id DESC LIMIT 1")
    fun observeLatest(): Flow<ActionRecordEntity?>

    @Query("SELECT * FROM action_records ORDER BY createdAt ASC, id ASC")
    suspend fun all(): List<ActionRecordEntity>

    @Query("SELECT * FROM action_records ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ActionRecordEntity>

    @Query("SELECT COUNT(*) FROM action_records")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: ActionRecordEntity): Long

    @Update
    suspend fun update(entity: ActionRecordEntity)

    @Query("DELETE FROM action_records")
    suspend fun clear()
}
