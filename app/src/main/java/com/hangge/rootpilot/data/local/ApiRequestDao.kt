package com.hangge.rootpilot.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiRequestDao {
    @Query("SELECT * FROM api_requests ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<ApiRequestEntity>>

    @Query("SELECT * FROM api_requests ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ApiRequestEntity>

    @Query("SELECT COUNT(*) FROM api_requests")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: ApiRequestEntity): Long

    @Query("DELETE FROM api_requests")
    suspend fun clear()
}
