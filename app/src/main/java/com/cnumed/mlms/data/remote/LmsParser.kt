package com.cnumed.mlms.data.remote

import com.cnumed.mlms.domain.model.ClassItem
import com.cnumed.mlms.domain.model.Notice
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class LmsParser @Inject constructor() {

    // ──────────────────────────────────────────────
    // 공지사항 파싱
    // ──────────────────────────────────────────────
    fun parseNotices(html: String): List<Notice> {
        // JSON 응답 처리 (/ajax/common/SLife/notice/list 등)
        val trimmed = html.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return parseNoticesJson(trimmed)
        }

        val doc = Jsoup.parse(html, LmsApi.BASE_URL)
        val notices = mutableListOf<Notice>()

        // 로그인 리다이렉트 감지
        if (doc.select("input[name=id], input[name=username], input[name=pwd]").isNotEmpty()) {
            android.util.Log.w("LmsParser", "parseNotices: redirected to login page")
            return notices
        }

        android.util.Log.d("LmsParser", "parseNotices HTML: title='${doc.title()}' bodyLen=${html.length} tbodyRows=${doc.select("tbody#noticeListView tr").size}")

        // ── SLife 구조 (충남대 의대 LMS): tbody#noticeListView ──
        // 제목 td에 a 태그 없이 onclick="boardDetail(seq)" 사용
        val slifeRows = doc.select("tbody#noticeListView tr, table.tr-line tbody tr")
        if (slifeRows.isNotEmpty()) {
            for (row in slifeRows) {
                try {
                    val id = row.select("input[name=board_seq]").firstOrNull()
                        ?.attr("value")?.toLongOrNull() ?: continue

                    val title = row.select("td.m-title span.title, td.m-title span.tdSpan")
                        .firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: continue

                    val dateTd = row.select("td").firstOrNull { td ->
                        td.select("span.thSpan").any { it.text().trim() == "등록일" }
                    }
                    val dateText = dateTd?.select("span.tdSpan")?.firstOrNull()?.text()?.trim() ?: ""

                    val viewTd = row.select("td").firstOrNull { td ->
                        td.select("span.thSpan").any { it.text().trim() == "조회수" }
                    }
                    val viewCount = viewTd?.select("span.tdSpan")?.firstOrNull()?.text()
                        ?.let { Regex("\\d+").find(it)?.value }?.toIntOrNull() ?: 0

                    val date = parseDate(dateText) ?: LocalDate.now()
                    val url = "${LmsApi.BASE_URL}/common/SLife/notice/detail?seq=$id"

                    notices.add(Notice(id = id, title = title, date = date, viewCount = viewCount, url = url))
                } catch (_: Exception) { }
            }
            android.util.Log.d("LmsParser", "SLife parse: ${notices.size} notices")
            if (notices.isNotEmpty()) return notices
        }

        // ── 범용 게시판 구조 ──
        val rows = doc.select(
            "table.board_list tbody tr, " +
            "table.list-table tbody tr, " +
            "table.content-list tbody tr, " +
            "table.boardList tbody tr, " +
            ".board-list tbody tr, " +
            ".notice-list li, " +
            ".board_list tbody tr, " +
            "table tbody tr"
        )

        for ((index, row) in rows.withIndex()) {
            try {
                val titleEl = row.select(
                    "a.aCharacter, .subject a, td.subject a, td.title a, td.tit a, " +
                    "a[href*='view'], a[href*='notice'], td a[href]"
                ).firstOrNull() ?: continue

                val title = titleEl.text().trim()
                if (title.isEmpty() || title == "제목" || title == "Title") continue

                val href = titleEl.attr("abs:href").ifEmpty {
                    val raw = titleEl.attr("href")
                    when {
                        raw.startsWith("http") -> raw
                        raw.startsWith("/") -> "${LmsApi.BASE_URL}$raw"
                        else -> raw
                    }
                }

                val cells = row.select("td")
                val dateText = cells.firstOrNull {
                    it.text().trim().matches(Regex("\\d{4}[-./ ]\\d{2}[-./ ]\\d{2}"))
                }?.text()?.trim() ?: ""
                val viewText = cells.lastOrNull { it.text().trim().matches(Regex("\\d+")) }
                    ?.text()?.trim() ?: "0"

                notices.add(
                    Notice(
                        id = index.toLong(),
                        title = title,
                        date = parseDate(dateText) ?: LocalDate.now(),
                        viewCount = viewText.toIntOrNull() ?: 0,
                        url = href.takeIf { it.isNotEmpty() } ?: LmsApi.NOTICE_URL
                    )
                )
            } catch (_: Exception) { }
        }

        android.util.Log.d("LmsParser", "Generic parse: ${notices.size} notices")
        return notices
    }

    private fun parseNoticesJson(json: String): List<Notice> {
        val notices = mutableListOf<Notice>()
        try {
            val arr: JSONArray = when {
                json.startsWith("[") -> JSONArray(json)
                else -> {
                    val obj = JSONObject(json)
                    obj.optJSONArray("list")
                        ?: obj.optJSONArray("data")
                        ?: obj.optJSONArray("noticeList")
                        ?: obj.optJSONArray("result")
                        ?: return notices
                }
            }
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optLong("board_seq",
                         obj.optLong("seq",
                         obj.optLong("id", i.toLong())))
                val title = obj.optString("title")
                    .ifEmpty { obj.optString("boardTitle") }
                    .ifEmpty { obj.optString("subject") }
                    .trim()
                if (title.isEmpty()) continue
                val dateStr = obj.optString("reg_date")
                    .ifEmpty { obj.optString("regDate") }
                    .ifEmpty { obj.optString("registDate") }
                val viewCnt = obj.optInt("view_cnt",
                              obj.optInt("viewCnt",
                              obj.optInt("readCnt", 0)))
                val date = parseDate(dateStr) ?: LocalDate.now()
                val url = "${LmsApi.BASE_URL}/common/SLife/notice/detail?seq=$id"
                notices.add(Notice(id = id, title = title, date = date, viewCount = viewCnt, url = url))
            }
        } catch (e: Exception) {
            android.util.Log.w("LmsParser", "parseNoticesJson error: ${e.message}")
        }
        android.util.Log.d("LmsParser", "JSON parse: ${notices.size} notices")
        return notices
    }

    // ──────────────────────────────────────────────
    // 시간표 파싱 (HTML 또는 JSON 자동 감지)
    // ──────────────────────────────────────────────
    fun parseTimetable(html: String, weekStart: LocalDate): List<ClassItem> {
        val trimmed = html.trimStart()
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            return parseTimetableJson(html, weekStart)
        }
        return parseTimetableHtml(html, weekStart)
    }

    private fun parseTimetableHtml(html: String, weekStart: LocalDate): List<ClassItem> {
        val doc = Jsoup.parse(html)
        val classes = mutableListOf<ClassItem>()

        // === 1. 날짜 목록 수집 ===
        // FullCalendar v3: th.fc-day-header[data-date] 또는 td.fc-day[data-date]
        val dayDates: List<String> = run {
            // th[data-date]
            var dates = doc.select("th[data-date]")
                .map { it.attr("data-date") }.filter { it.isNotEmpty() }
            if (dates.isNotEmpty()) return@run dates

            // td[data-date] (fc-bg 테이블)
            dates = doc.select("td[data-date]")
                .map { it.attr("data-date") }.filter { it.isNotEmpty() }.distinct().sorted()
            if (dates.isNotEmpty()) return@run dates

            // 전체 [data-date]
            dates = doc.select("[data-date]")
                .map { it.attr("data-date") }.filter { it.isNotEmpty() }.distinct().sorted()
            if (dates.isNotEmpty()) return@run dates

            // 없으면 weekStart 기준 일~토 7일 생성 (컬럼 인덱스 매핑용)
            android.util.Log.w("LmsParser", "No data-date found; fallback to weekStart=$weekStart")
            (0..6).map { weekStart.plusDays(it.toLong()).toString() }
        }
        android.util.Log.d("LmsParser", "dayDates=$dayDates")

        // === 2. 이벤트 파싱 ===
        // ".fc-content:has(.fc-time):has(.fc-title)" → 이 LMS의 이벤트 컨텐츠 구조
        val eventDivs = doc.select(".fc-content:has(.fc-time):has(.fc-title)")
        android.util.Log.d("LmsParser", "fc-content events: ${eventDivs.size}")

        for (eventDiv in eventDivs) {
            try {
                val timeEl = eventDiv.select(".fc-time").firstOrNull() ?: continue
                val titleText = eventDiv.select(".fc-title").firstOrNull()
                    ?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val timeText = timeEl.attr("data-full")
                    .ifEmpty { timeEl.text() }.takeIf { it.isNotEmpty() } ?: continue

                // 날짜 결정: ① 부모 경로의 data-date ② td 컬럼 인덱스 ③ CSS left% ④ 진단 로그
                val dateStr: String = run {
                    // ① 부모에 data-date 속성 있으면 사용
                    val fromParent = eventDiv.parents()
                        .firstOrNull { it.hasAttr("data-date") }?.attr("data-date")
                    if (!fromParent.isNullOrEmpty()) return@run fromParent

                    // ② 부모 td의 형제 중 fc-axis 제외 인덱스
                    val parentTd = eventDiv.parents().firstOrNull { it.tagName() == "td" }
                    if (parentTd != null) {
                        val siblings = parentTd.parent()?.select("td:not(.fc-axis)")
                            ?: org.jsoup.select.Elements()
                        val colIdx = siblings.indexOf(parentTd)
                        val mapped = if (colIdx >= 0) dayDates.getOrNull(colIdx) else null
                        if (!mapped.isNullOrEmpty()) return@run mapped
                    }

                    // ③ CSS left:X% 로 컬럼 인덱스 추정 (FullCalendar CSS 레이아웃 방식)
                    val styledParent = eventDiv.parents()
                        .firstOrNull { it.attr("style").contains("left:") || it.attr("style").contains("left :") }
                    if (styledParent != null) {
                        val leftMatch = Regex("""left\s*:\s*([\d.]+)%""").find(styledParent.attr("style"))
                        val leftPct = leftMatch?.groupValues?.get(1)?.toDoubleOrNull()
                        if (leftPct != null && dayDates.isNotEmpty()) {
                            val colIdx = (leftPct / (100.0 / dayDates.size)).toInt()
                                .coerceIn(0, dayDates.size - 1)
                            return@run dayDates[colIdx]
                        }
                    }

                    // ④ 진단: 부모 체인 로깅
                    val chain = eventDiv.parents().take(5)
                        .joinToString(" > ") { "${it.tagName()}.${it.className().take(20)}" }
                    android.util.Log.w("LmsParser", "No date for '$titleText' | parent chain: $chain")
                    "" // 결정 불가
                }

                if (dateStr.isEmpty()) {
                    android.util.Log.w("LmsParser", "No date for: $titleText | $timeText")
                    continue
                }

                val date = try { LocalDate.parse(dateStr) } catch (_: Exception) { continue }
                val (startTime, endTime) = parseTimeRange(timeText) ?: continue
                val (title, professor) = parseTitleProfessor(titleText)

                android.util.Log.d("LmsParser", "[$dateStr] $title | $timeText")
                classes.add(ClassItem(
                    title = title, professor = professor,
                    dayOfWeek = date.dayOfWeek.value,
                    date = date,
                    startTime = startTime, endTime = endTime,
                    weekStart = weekStart
                ))
            } catch (_: Exception) { }
        }

        android.util.Log.d("LmsParser", "Total from HTML: ${classes.size}")
        return classes
    }

    /** HTML에서 FullCalendar가 현재 표시 중인 주의 첫째 날 반환 */
    fun extractDisplayedWeekStart(html: String): LocalDate? {
        val doc = Jsoup.parse(html)

        // 1. 모든 [data-date] 요소
        doc.select("[data-date]").firstOrNull()?.attr("data-date")?.let {
            try { return LocalDate.parse(it) } catch (_: Exception) { }
        }

        // 2. h2 텍스트 파싱: "2026년 3월 1 – 7일", "2026년 2월 22 – 28일"
        doc.select("h2").forEach { h2 ->
            val text = h2.text()
            android.util.Log.d("LmsParser", "h2 candidate: $text")
            val m = Regex("""(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})""").find(text) ?: return@forEach
            try {
                return LocalDate.of(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt()
                )
            } catch (_: Exception) { }
        }

        return null
    }

    private fun parseTimetableJson(json: String, weekStart: LocalDate): List<ClassItem> {
        val classes = mutableListOf<ClassItem>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val rawTitle = obj.optString("title").ifEmpty { obj.optString("courseName") }
                val rawProfessor = obj.optString("professor").ifEmpty { obj.optString("profName", "") }
                val start = obj.optString("start")
                val end = obj.optString("end", "")

                if (rawTitle.isEmpty() || start.isEmpty()) continue

                val startDate = LocalDate.parse(start.take(10))

                // 요청한 주차 범위(weekStart ~ weekStart+6)에 속하는 이벤트만 처리
                val weekEnd = weekStart.plusDays(6)
                if (startDate < weekStart || startDate > weekEnd) continue

                // WebView FullCalendar 추출 시 교수명이 제목에 포함됨 → 분리
                val (title, professor) = if (rawProfessor.isEmpty()) {
                    parseTitleProfessor(rawTitle)
                } else {
                    Pair(rawTitle, rawProfessor)
                }
                val startTime = LocalTime.parse(start.drop(11).take(5))
                val endTime = if (end.length >= 16) LocalTime.parse(end.drop(11).take(5))
                              else startTime.plusHours(2)

                val dayOfWeek = startDate.dayOfWeek.value

                classes.add(
                    ClassItem(
                        title = title,
                        professor = professor,
                        dayOfWeek = dayOfWeek,
                        date = startDate,
                        startTime = startTime,
                        endTime = endTime,
                        weekStart = weekStart
                    )
                )
            }
        } catch (_: Exception) { }
        return classes
    }

    // ──────────────────────────────────────────────
    // 유틸리티
    // ──────────────────────────────────────────────
    private fun parseTitleProfessor(text: String): Pair<String, String> {
        val match = Regex("""^(.*?)\s*\(([^)]+)\)\s*$""").find(text)
        return if (match != null) {
            Pair(match.groupValues[1].trim(), match.groupValues[2].trim())
        } else {
            Pair(text.trim(), "")
        }
    }

    private fun parseTimeRange(timeText: String): Pair<LocalTime, LocalTime>? {
        val cleaned = timeText
            .replace("오전", "AM")
            .replace("오후", "PM")
            .replace("~", " - ")
            .replace("–", " - ")

        val parts = cleaned.split(" - ", " ~ ").map { it.trim() }
        if (parts.size < 2) return null

        val start = parseTime(parts[0]) ?: return null
        val end = parseTime(parts[1]) ?: return null
        return Pair(start, end)
    }

    private fun parseTime(s: String): LocalTime? = try {
        val t = s.trim()
        when {
            t.contains("AM", ignoreCase = true) -> {
                val parts = t.replace("AM", "", ignoreCase = true).trim().split(":")
                val h = parts[0].toInt().let { if (it == 12) 0 else it }
                LocalTime.of(h, parts[1].toInt())
            }
            t.contains("PM", ignoreCase = true) -> {
                val parts = t.replace("PM", "", ignoreCase = true).trim().split(":")
                val h = parts[0].toInt().let { if (it == 12) 12 else it + 12 }
                LocalTime.of(h, parts[1].toInt())
            }
            else -> {
                val parts = t.split(":")
                LocalTime.of(parts[0].toInt(), parts[1].toInt())
            }
        }
    } catch (_: Exception) { null }

    fun extractCsrfToken(html: String): String? {
        val doc = Jsoup.parse(html)
        return doc.select("input[name=_csrf], input[name=csrf_token], input[name=_token], meta[name=_csrf]")
            .firstOrNull()
            ?.let {
                if (it.tagName() == "meta") it.attr("content") else it.attr("value")
            }
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * 로그인 폼의 action URL과 모든 hidden 필드를 추출.
     * 서버에 따라 POST 엔드포인트가 /login 이 아닐 수 있음.
     */
    fun extractLoginForm(html: String): LoginFormData {
        val doc = Jsoup.parse(html, LmsApi.BASE_URL)

        // id/pwd input이 있는 폼 우선, 없으면 첫 번째 POST 폼
        val form = doc.select("form").firstOrNull { f ->
            f.select("input[name=id], input[name=pwd], input[name=username], input[name=password]").isNotEmpty()
        } ?: doc.select("form[method~=(?i)post]").firstOrNull()

        val action = form?.attr("abs:action")
            ?.takeIf { it.isNotEmpty() }
            ?: LmsApi.LOGIN_URL

        val idField = form?.select("input[name=id], input[name=username]")
            ?.firstOrNull()?.attr("name") ?: "id"
        val pwdField = form?.select("input[name=pwd], input[name=password]")
            ?.firstOrNull()?.attr("name") ?: "pwd"

        val hiddenFields = form?.select("input[type=hidden]")
            ?.associate { it.attr("name") to it.attr("value") }
            ?: emptyMap()

        return LoginFormData(action, idField, pwdField, hiddenFields)
    }

    data class LoginFormData(
        val actionUrl: String,
        val idFieldName: String,
        val pwdFieldName: String,
        val hiddenFields: Map<String, String>
    )

    /**
     * FullCalendar 설정에서 events 피드 URL 추출.
     * 한국 LMS에서 흔히 쓰이는 패턴들을 모두 시도함.
     */
    fun extractCalendarEventsUrl(html: String): String? {
        // <script> 태그 내용만 추출해서 탐색
        val doc = Jsoup.parse(html)
        val scriptContent = doc.select("script:not([src])").joinToString("\n") { it.data() }

        android.util.Log.d("LmsParser", "Script length: ${scriptContent.length}")
        // script 내 /aca/ 경로 있는 URL 힌트 출력
        Regex("""['"]([/][^'"]{3,60})['"]""").findAll(scriptContent)
            .map { it.groupValues[1] }
            .filter { it.contains("aca") || it.contains("schedule") || it.contains("calendar") || it.contains("event") }
            .distinct()
            .forEach { android.util.Log.d("LmsParser", "JS URL hint: $it") }

        val patterns = listOf(
            // events: { url: '/path' }
            Regex("""events\s*:\s*\{[^}]*url\s*:\s*['"]([^'"]+)['"]"""),
            // events: '/path'
            Regex("""events\s*:\s*['"]([/][^'"]+)['"]"""),
            // url: '/aca/...schedule...' 또는 '/aca/...cal...'
            Regex("""url\s*:\s*['"]([/][^'"]*(?:schedule|calendar|event|sched|cal)[^'"]*)['"]""", RegexOption.IGNORE_CASE),
            // $.ajax({ url: '/path' })
            Regex("""ajax\s*\(\s*\{[^}]*url\s*:\s*['"]([/][^'"]+)['"]"""),
        )
        for (p in patterns) {
            p.find(scriptContent)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    private fun parseDate(text: String): LocalDate? {
        val patterns = listOf("yyyy-MM-dd", "yyyy.MM.dd", "yyyy/MM/dd")
        for (pattern in patterns) {
            try { return LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern)) }
            catch (_: Exception) { }
        }
        return null
    }
}
