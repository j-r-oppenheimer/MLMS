package com.cnumed.mlms.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cnumed.mlms.domain.model.Notice
import java.time.LocalDate

@Entity(tableName = "notices")
data class NoticeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val date: String,       // ISO-8601: yyyy-MM-dd
    val viewCount: Int,
    val url: String,
    val isRead: Boolean = false
) {
    fun toDomain() = Notice(
        id = id,
        title = title,
        date = LocalDate.parse(date),
        viewCount = viewCount,
        url = url,
        isRead = isRead
    )

    companion object {
        fun fromDomain(notice: Notice) = NoticeEntity(
            id = notice.id,
            title = notice.title,
            date = notice.date.toString(),
            viewCount = notice.viewCount,
            url = notice.url,
            isRead = notice.isRead
        )
    }
}
