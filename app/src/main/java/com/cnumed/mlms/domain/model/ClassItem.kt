package com.cnumed.mlms.domain.model

import java.time.LocalDate
import java.time.LocalTime

data class ClassItem(
    val id: Long = 0,
    val title: String,
    val professor: String,
    val dayOfWeek: Int,       // 1=월, 2=화, 3=수, 4=목, 5=금
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val weekStart: LocalDate  // 해당 주의 일요일
)
