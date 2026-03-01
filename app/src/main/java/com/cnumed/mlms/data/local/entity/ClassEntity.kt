package com.cnumed.mlms.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cnumed.mlms.domain.model.ClassItem
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "classes")
data class ClassEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val professor: String,
    val dayOfWeek: Int,
    val date: String,       // ISO-8601: yyyy-MM-dd
    val startTime: String,  // HH:mm
    val endTime: String,    // HH:mm
    val weekStart: String   // ISO-8601: yyyy-MM-dd
) {
    fun toDomain() = ClassItem(
        id = id,
        title = title,
        professor = professor,
        dayOfWeek = dayOfWeek,
        date = LocalDate.parse(date),
        startTime = LocalTime.parse(startTime),
        endTime = LocalTime.parse(endTime),
        weekStart = LocalDate.parse(weekStart)
    )

    companion object {
        fun fromDomain(item: ClassItem) = ClassEntity(
            id = item.id,
            title = item.title,
            professor = item.professor,
            dayOfWeek = item.dayOfWeek,
            date = item.date.toString(),
            startTime = item.startTime.toString(),
            endTime = item.endTime.toString(),
            weekStart = item.weekStart.toString()
        )
    }
}
