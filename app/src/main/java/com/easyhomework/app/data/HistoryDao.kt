package com.easyhomework.app.data

import androidx.room.*
import com.easyhomework.app.model.QueryHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Query("SELECT * FROM query_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<QueryHistory>>

    @Query("SELECT * FROM query_history WHERE id = :id")
    suspend fun getHistoryById(id: Long): QueryHistory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: QueryHistory): Long

    @Update
    suspend fun updateHistory(history: QueryHistory)

    @Delete
    suspend fun deleteHistory(history: QueryHistory)

    @Query("DELETE FROM query_history")
    suspend fun clearAllHistory()

    @Query("SELECT COUNT(*) FROM query_history")
    suspend fun getHistoryCount(): Int
}
