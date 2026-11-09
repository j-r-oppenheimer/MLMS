package com.cnumed.mlms.convert

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 다운로드 파일 이름에 넣을 수업 정보 */
data class DownloadMeta(
    val lessonDate: LocalDate?,
    val subject: String,
    val lessonTitle: String,
    val professor: String,
)

/**
 * 사용자 지정 형식으로 다운로드 파일 이름을 만든다.
 * 예) "{날짜} {과목} ({강의명}_{교수})" → "20260418 파인만의 물리학 강의 (1강_리처드 파인만).pdf"
 */
object FileNameFormatter {
    const val DEFAULT_TEMPLATE = "{날짜} {과목} ({강의명}_{교수})"
    /** 예전 기본값 — 사용자가 바꾸지 않았다면 새 기본값으로 옮긴다 */
    const val OLD_DEFAULT_TEMPLATE = "{날짜} {과목} ({파일명}_{교수})"

    val DATE_FORMATS = listOf(
        "yyyyMMdd", "yyMMdd", "yyyy-MM-dd", "yy-MM-dd", "yyyy.MM.dd", "yy.MM.dd", "yyyy_MM_dd", "MMdd", "M월 d일", "yyyy년 M월 d일",
    )

    /** 설정 화면 토큰 버튼: (보여줄 이름, 넣을 문자열) */
    val TOKENS = listOf(
        "날짜" to "{날짜}", "과목" to "{과목}", "강의명" to "{강의명}", "파일명" to "{파일명}",
        "교수" to "{교수}", "요일" to "{요일}", "오늘" to "{오늘}", "_" to "_", "공백" to " ", "( )" to "()", "-" to "-",
    )

    private val TRAILING_PAREN = Regex("""\(([^()]+)\)\s*$""")
    private val ANY_PAREN = Regex("""\s*[(（][^()（）]*[)）]""")

    /**
     * 시간표 항목으로 이름 정보 만들기.
     * 교수 칸이 비어 있으면 제목 끝 괄호 속 이름을 쓴다 (예: "진단혈액 TBL (박수용)" → 박수용).
     */
    fun metaFor(lessonDate: LocalDate?, subject: String, title: String, professor: String): DownloadMeta {
        val oneLine = title.replace(Regex("""\s+"""), " ").trim()
        val fromTitle = TRAILING_PAREN.find(oneLine)?.groupValues?.get(1)?.trim().orEmpty()
        val prof = professor.trim().ifEmpty { fromTitle }
        // 강의명: 괄호 부분(교수 이름 등)은 뺀다
        val lesson = oneLine.replace(ANY_PAREN, "").replace(Regex("""\s+"""), " ").trim().ifEmpty { oneLine }
        return DownloadMeta(lessonDate, subject.replace(Regex("""\s+"""), " ").trim(), lesson, prof)
    }

    fun dateExample(fmt: String, d: LocalDate = LocalDate.of(2026, 4, 18)): String =
        runCatching { d.format(DateTimeFormatter.ofPattern(fmt, Locale.KOREAN)) }.getOrDefault(fmt)

    fun format(template: String, dateFmt: String, meta: DownloadMeta, baseName: String, today: LocalDate = LocalDate.now()): String {
        val f = runCatching { DateTimeFormatter.ofPattern(dateFmt, Locale.KOREAN) }.getOrElse { DateTimeFormatter.BASIC_ISO_DATE }
        val dow = meta.lessonDate?.dayOfWeek?.getDisplayName(java.time.format.TextStyle.SHORT, Locale.KOREAN) ?: ""
        var s = template
            .replace("{날짜}", meta.lessonDate?.format(f) ?: "")
            .replace("{오늘}", today.format(f))
            .replace("{과목}", meta.subject.ifBlank { meta.lessonTitle })
            .replace("{강의명}", meta.lessonTitle)
            .replace("{수업}", meta.lessonTitle)
            .replace("{파일명}", baseName)
            .replace("{교수}", meta.professor)
            .replace("{요일}", dow)
        s = cleanup(s)
        return sanitize(s).ifBlank { sanitize(baseName) }
    }

    /** 빈 값 때문에 생긴 "()", "(_x)", "a__b" 같은 흔적 정리 */
    private fun cleanup(input: String): String {
        var s = input
        repeat(3) {
            s = s.replace(Regex("([(\\[{])[\\s_\\-.]+"), "$1")
                .replace(Regex("[\\s_\\-.]+([)\\]}])"), "$1")
                .replace(Regex("\\(\\)|\\[\\]|\\{\\}"), "")
                .replace(Regex("_{2,}"), "_")
                .replace(Regex("-{2,}"), "-")
                .replace(Regex(" {2,}"), " ")
                .replace(Regex("\\s+_|_\\s+"), "_")
        }
        return s.trim(' ', '_', '-', '.')
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().take(150)

    /**
     * 설정에 따라 최종 파일 이름(확장자 포함). 기능이 꺼져 있으면 원래 이름.
     * @param newExt 변환 결과 확장자(예: "pdf"). null이면 원래 확장자
     */
    fun resolve(context: Context, originalName: String, meta: DownloadMeta?, newExt: String? = null): String {
        val base = originalName.substringBeforeLast('.', originalName)
        val ext = newExt ?: originalName.substringAfterLast('.', "")
        // 라이트 버전은 항상 원래 이름
        val name = if (meta != null && PptxFeature.AVAILABLE && ConvertPrefs.nameEnabled(context)) {
            format(ConvertPrefs.nameTemplate(context), ConvertPrefs.dateFormat(context), meta, base)
        } else base
        return if (ext.isEmpty()) name else "$name.$ext"
    }
}
