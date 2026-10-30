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

    @Query("DELETE FROM classes")
    suspend fun deleteAll()

    /**
     * 전체 달력을 새로 받아왔을 때 기존 시간표를 통째로 교체한다.
     * (수업이 모두 없어진 주가 예전 시간표로 남지 않도록 주별이 아니라 전부 지운다)
     */
    @Transaction
    suspend fun replaceAll(byWeek: Map<String, List<ClassEntity>>) {
        deleteAll()
        for (classes in byWeek.values) insertAll(classes)
    }

    @Query("SELECT * FROM classes ORDER BY date, startTime LIMIT 1")
    suspend fun getNextClass(): ClassEntity?
}
