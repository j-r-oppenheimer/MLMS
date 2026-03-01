package com.cnumed.mlms.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object DateUtils {

    /** 주의 시작일 (일요일) 반환 */
    fun getWeekStart(date: LocalDate = LocalDate.now()): LocalDate {
        val dow = date.dayOfWeek
        return if (dow == DayOfWeek.SUNDAY) date else date.with(DayOfWeek.MONDAY).minusDays(1)
    }

    fun formatWeekRange(weekStart: LocalDate): String {
        val end = weekStart.plusDays(6)
        return "${weekStart.format(DateTimeFormatter.ofPattern("M/d"))} ~ ${end.format(DateTimeFormatter.ofPattern("M/d"))}"
    }

    fun formatDate(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("M월 d일"))

    fun dayOfWeekKorean(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "월"; 2 -> "화"; 3 -> "수"; 4 -> "목"
        5 -> "금"; 6 -> "토"; 7 -> "일"
        else -> ""
    }
}
