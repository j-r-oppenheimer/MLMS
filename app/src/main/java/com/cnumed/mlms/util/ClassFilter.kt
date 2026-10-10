package com.cnumed.mlms.util

import android.content.Context

/** 설정의 A/B반 선택에 따른 수업 필터. 앱·위젯이 같은 규칙과 기본값("ALL")을 쓴다. */
object ClassFilter {
    fun selectedClass(context: Context): String =
        context.getSharedPreferences("class_settings", Context.MODE_PRIVATE)
            .getString("selected_class", "ALL") ?: "ALL"

    fun matches(title: String, selectedClass: String): Boolean {
        if (selectedClass == "ALL") return true
        val t = title.uppercase()
        return when {
            "AB" in t || "AB 반" in t || "A/B" in t -> true
            "A반" in t || "A 반" in t -> selectedClass == "A"
            "B반" in t || "B 반" in t -> selectedClass == "B"
            else -> true
        }
    }
}
