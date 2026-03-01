package com.cnumed.mlms.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.cnumed.mlms.data.local.dao.ClassDao
import com.cnumed.mlms.data.local.dao.NoticeDao
import com.cnumed.mlms.data.local.entity.ClassEntity
import com.cnumed.mlms.data.local.entity.NoticeEntity

@Database(
    entities = [ClassEntity::class, NoticeEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun classDao(): ClassDao
    abstract fun noticeDao(): NoticeDao
}
