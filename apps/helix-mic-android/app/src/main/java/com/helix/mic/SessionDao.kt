package com.helix.mic

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    fun getAll(): List<SessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    fun delete(id: String)

    @Query("DELETE FROM sessions")
    fun deleteAll()

    @Query("UPDATE sessions SET summary = :summary WHERE id = :id")
    fun updateSummary(id: String, summary: String)

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    fun updateTitle(id: String, title: String?)
}
