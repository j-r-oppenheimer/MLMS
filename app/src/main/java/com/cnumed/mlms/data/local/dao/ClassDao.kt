package com.cnumed.mlms.data.local.dao

import androidx.room.*
import com.cnumed.mlms.data.local.entity.ClassEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ClassDao {
    @Query("SELECT * FROM classes WHERE weekStart = :weekStart ORDER BY dayOfWeek, startTime")
    fun getClassesByWeek(weekStart: String): Flow<List<ClassEntity>>

    @Query("SELECT * FROM classes WHERE weekStart = :weekStart ORDER BY dayOfWeek, startTime")
    suspend fun getClassesByWeekOnce(weekStart: String): List<ClassEntity>

    @Query("SELECT * FROM classes WHERE date = :date ORDER BY startTime")
    fun getClassesByDate(date: String): Flow<List<ClassEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(classes: List<ClassEntity>)

    @Query("DELETE FROM classes WHERE weekStart = :weekStart")
    suspend fun deleteByWeek(weekStart: String)

    @Query("SELECT * FROM classes ORDER BY date, startTime LIMIT 1")
    suspend fun getNextClass(): ClassEntity?
}
