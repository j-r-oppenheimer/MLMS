package com.cnumed.mlms.data.remote

import com.cnumed.mlms.util.AppLog

import com.cnumed.mlms.domain.model.ClassItem
import com.cnumed.mlms.domain.model.Notice
import com.cnumed.mlms.util.DateUtils
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

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
            AppLog.w("LmsParser", "parseNotices: redirected to login page")
            return notices
        }

        AppLog.d("LmsParser", "parseNotices HTML: title='${doc.title()}' bodyLen=${html.length} tbodyRows=${doc.select("tbody#noticeListView tr").size}")

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
            AppLog.d("LmsParser", "SLife parse: ${notices.size} notices")
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

        AppLog.d("LmsParser", "Generic parse: ${notices.size} notices")
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
            AppLog.w("LmsParser", "parseNoticesJson error: ${e.message}")
        }
        AppLog.d("LmsParser", "JSON parse: ${notices.size} notices")
        return notices
    }

    // ──────────────────────────────────────────────
    // 시간표 파싱
    // ──────────────────────────────────────────────
    /**
     * 전체 이벤트를 주차 필터 없이 파싱. 각 이벤트의 weekStart는 날짜에서 자동 계산.
     */
    fun parseAllTimetableJson(json: String): List<ClassItem> {
        val classes = mutableListOf<ClassItem>()
        val popupRegex = Regex("""showPopup\((\d+),(\d+),(\d+)\)""")
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val rawTitle = obj.optString("title").ifEmpty { obj.optString("courseName") }
                val rawProfessor = obj.optString("professor").ifEmpty { obj.optString("profName", "") }
                val start = obj.optString("start")
                val end = obj.optString("end", "")
                val url = obj.optString("url", "")

                if (rawTitle.isEmpty() || start.isEmpty()) continue

                val startDate = LocalDate.parse(start.take(10))

                // showPopup(lp_seq, curr_seq, aca_seq) 파싱
                val seqMatch = popupRegex.find(url)
                val lpSeq = seqMatch?.groupValues?.get(1)?.toIntOrNull()
                val currSeq = seqMatch?.groupValues?.get(2)?.toIntOrNull()
                val acaSeq = seqMatch?.groupValues?.get(3)?.toIntOrNull()

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
                        weekStart = DateUtils.getWeekStart(startDate),
                        lpSeq = lpSeq,
                        currSeq = currSeq,
                        acaSeq = acaSeq
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

    private fun parseDate(text: String): LocalDate? {
        val patterns = listOf("yyyy-MM-dd", "yyyy.MM.dd", "yyyy/MM/dd")
        for (pattern in patterns) {
            try { return LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern)) }
            catch (_: Exception) { }
        }
        return null
    }
}
