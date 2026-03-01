package com.cnumed.mlms.domain.model

import java.time.LocalDate

data class Notice(
    val id: Long = 0,
    val title: String,
    val date: LocalDate,
    val viewCount: Int,
    val url: String,
    val isRead: Boolean = false
)
