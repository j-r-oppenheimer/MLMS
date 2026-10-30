package com.cnumed.mlms.util

import android.content.Context
import android.graphics.Color
import java.time.LocalDate
import java.time.LocalTime

/**
 * 수업 블록 색 관련 공통 규칙.
 * - 시험 블록 판별(오탐 단어 제외)
 * - 수업 칸별 보색 반전 토글 저장/조회
 *
 * 반전은 "그 회차 한 칸" 단위다. 날짜+시작시간+제목을 키로 써서
 * 같은 과목이라도 다른 날짜의 수업은 영향을 받지 않는다.
 */
object ClassColorPrefs {

    private const val PREFS = "class_settings"
    private const val KEY_INVERTED = "inverted_classes"

    private val examKeywords = listOf("시험", "중간", "기말", "평가", "MCQ", "MEQ", "CPX")

    /** "시험관"처럼 시험이 아닌데 키워드를 품고 있는 단어는 매칭 전에 제거한다. */
    private val examFalsePositives = listOf("시험관")

    fun isExamClass(title: String): Boolean {
        var t = title
        for (word in examFalsePositives) t = t.replace(word, "")
        return examKeywords.any { t.contains(it, ignoreCase = true) }
    }

    // 색상 휠(RYB) <-> RGB 색상각 대응표 — 미술 색상환 기준 보색을 얻기 위한 것.
    // 노랑(RGB 60도)의 반대가 보라, 파랑 계열의 반대가 빨강 계열이 되도록 한다.
    // Windows판(MLMS_Windows/timetable_widget.py의 complement_color)과 동일한 매핑.
    private val RGB_HUES = floatArrayOf(0f, 20f, 60f, 120f, 240f, 300f, 360f)
    private val RYB_HUES = floatArrayOf(0f, 60f, 120f, 180f, 240f, 300f, 360f)

    private fun lerpHue(v: Float, xs: FloatArray, ys: FloatArray): Float {
        for (i in 0 until xs.size - 1) {
            if (v >= xs[i] && v <= xs[i + 1]) {
                val span = xs[i + 1] - xs[i]
                val t = if (span != 0f) (v - xs[i]) / span else 0f
                return ys[i] + (ys[i + 1] - ys[i]) * t
            }
        }
        return ys[ys.size - 1]
    }

    /** 색상 휠 반대편 색 — 노랑↔보라, 파랑 계열↔빨강 계열. 채도·명도는 유지한다. */
    fun complementary(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[1] == 0f) return color  // 무채색 — 돌릴 색상이 없다
        val ryb = (lerpHue(hsv[0], RGB_HUES, RYB_HUES) + 180f) % 360f
        hsv[0] = lerpHue(ryb, RYB_HUES, RGB_HUES) % 360f
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    /** 수업 한 칸(회차)을 가리키는 키. */
    fun key(title: String, date: LocalDate, startTime: LocalTime): String =
        "$date|$startTime|${title.trim()}"

    fun invertedKeys(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_INVERTED, emptySet()) ?: emptySet()

    fun isInverted(context: Context, title: String, date: LocalDate, startTime: LocalTime): Boolean =
        key(title, date, startTime) in invertedKeys(context)

    fun setInverted(
        context: Context,
        title: String,
        date: LocalDate,
        startTime: LocalTime,
        inverted: Boolean
    ) {
        // getStringSet이 돌려주는 Set은 직접 수정하면 안 되므로 항상 복사본을 저장한다.
        val next = invertedKeys(context).toMutableSet()
        val k = key(title, date, startTime)
        if (inverted) next.add(k) else next.remove(k)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_INVERTED, next).apply()
    }

    /** 반전 여부 + 시험 강조를 반영한 최종 블록 색. */
    fun blockColorFor(
        title: String,
        date: LocalDate,
        startTime: LocalTime,
        baseColor: Int,
        invertedKeys: Set<String>,
        examHighlight: Boolean,
        dark: Boolean
    ): Int {
        val base = if (key(title, date, startTime) in invertedKeys) complementary(baseColor) else baseColor
        return if (examHighlight && isExamClass(title)) examColor(base, dark) else base
    }

    /** 시험 블록 강조색: 다크는 +20% 밝게, 라이트는 15% 어둡게. */
    fun examColor(base: Int, dark: Boolean): Int {
        val r = Color.red(base); val g = Color.green(base); val b = Color.blue(base)
        return if (dark)
            Color.rgb(
                r + ((255 - r) * 0.20f).toInt(),
                g + ((255 - g) * 0.20f).toInt(),
                b + ((255 - b) * 0.20f).toInt()
            )
        else
            Color.rgb((r * 0.85f).toInt(), (g * 0.85f).toInt(), (b * 0.85f).toInt())
    }
}
