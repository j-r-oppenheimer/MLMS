package com.cnumed.mlms.domain.model

data class LessonFile(
    val fileName: String,
    val downloadUrl: String,
    val attachSeq: String = "",
    val dataSeq: String = ""
)

data class LessonDetail(
    val subject: String = "",
    val room: String = "",
    val files: List<LessonFile> = emptyList()
)
