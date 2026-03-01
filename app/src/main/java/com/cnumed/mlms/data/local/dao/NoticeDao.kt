package com.cnumed.mlms.data.local.dao

import androidx.room.*
import com.cnumed.mlms.data.local.entity.NoticeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoticeDao {
    @Query("SELECT * FROM notices ORDER BY date DESC")
    fun getAllNotices(): Flow<List<NoticeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notices: List<NoticeEntity>)

    @Query("UPDATE notices SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: Long)

    @Query("SELECT id FROM notices")
    suspend fun getAllNoticeIds(): List<Long>

    @Query("SELECT id FROM notices WHERE isRead = 1")
    suspend fun getReadNoticeIds(): List<Long>

    @Query("DELETE FROM notices")
    suspend fun deleteAll()
}
