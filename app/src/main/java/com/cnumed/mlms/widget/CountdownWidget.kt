package com.cnumed.mlms.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.cnumed.mlms.R
import com.cnumed.mlms.util.ClassColorPrefs
import com.cnumed.mlms.util.ClassFilter
import com.cnumed.mlms.util.AppLog
import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * "다음 시험 D-", "종강 D-" 같은 카운트다운 위젯의 공통 베이스.
 *
 * 데이터는 [TimetableWidget]이 학기 전체를 주차별로 저장해 둔 `widget_cache`
 * SharedPreferences(`week_classes_*`)를 그대로 읽는다. 별도 네트워크 요청 없이
 * 로컬 캐시만으로 다음/마지막 시험을 계산하므로 WebView를 돌리지 않는다.
 *
 * 설정(투명도·다크·글씨 크기)은 배치된 위젯마다 독립적으로
 * `countdown_{appWidgetId}_*` 키에 저장한다.
 */
abstract class CountdownWidget : AppWidgetProvider() {

    abstract val mode: Mode

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) render(context, appWidgetManager, id, mode)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        render(context, appWidgetManager, appWidgetId, mode)
    }

    enum class Mode { NEXT_EXAM, SEMESTER_END }

    companion object {

        private const val TAG = "CountdownWidget"

        const val PREFS_WIDGET = TimetableWidget.PREFS_WIDGET // "widget_cache"

        // 글씨 크기 감쇠 파라미터. SIZE_DAMP<1 이면 위젯 크기 변화에 덜 민감(작을수록 완만).
        // pivot은 "글씨 변화가 0인 기준점" — 이 위젯의 기본 크기(1×1, ref≈72dp)에 맞춰
        // 기본 크기에선 원래 크기 그대로, 리사이즈할 때만 완만하게 변하게 한다.
        private const val SIZE_DAMP = 0.55
        private const val SIZE_PIVOT_DP = 72f

        // 이 일수 이상 수업이 없으면 방학(학기 경계)으로 인식한다.
        private const val VACATION_GAP_DAYS = 14L

        // 배치된 위젯별 설정 키
        private fun keyBgAlpha(id: Int)  = "countdown_${id}_bg_alpha"
        private fun keyDark(id: Int)     = "countdown_${id}_dark"
        private fun keyTextSize(id: Int) = "countdown_${id}_text_size"

        private fun isExamClass(title: String) = ClassColorPrefs.isExamClass(title)

        // LMS 주간표의 "교과" 기간 배너(수업이 아님). 캐시 실물: title="약물작용의 원리 2[8 / 17~10 / 23]".
        // 과목 기간 시작일 = 주 시작 월요일에 걸려서 개강일을 하루 앞당기므로 경계 계산에서 뺀다.
        // 소괄호 형태면 LmsParser.parseTitleProfessor가 professor 자리로 떼어내므로 그쪽도 본다.
        private val periodOnlyRegex = Regex("""^\d{1,2}\s*/\s*\d{1,2}\s*[~\-–]\s*\d{1,2}\s*/\s*\d{1,2}$""")
        private val periodInTitleRegex =
            Regex("""[(\[（【]\s*\d{1,2}\s*/\s*\d{1,2}\s*[~\-–]\s*\d{1,2}\s*/\s*\d{1,2}\s*[)\]）】]""")

        private fun isCoursePeriodBanner(title: String, professor: String): Boolean =
            periodInTitleRegex.containsMatchIn(title) || periodOnlyRegex.matches(professor.trim())

        private val dayLabels = listOf("월", "화", "수", "목", "금", "토", "일")

        data class ExamClass(
            val title: String,
            val date: LocalDate,
            val startTime: LocalTime
        )

        /** 설정 화면에서 저장 후 호출: 위젯 종류를 프로바이더로 판별해 다시 그린다. */
        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val info = manager.getAppWidgetInfo(widgetId) ?: return
            val mode = when (info.provider.className) {
                SemesterEndWidget::class.java.name -> Mode.SEMESTER_END
                else -> Mode.NEXT_EXAM
            }
            render(context, manager, widgetId, mode)
        }

        /** 워커가 캐시 갱신 후 모든 카운트다운 위젯을 다시 그릴 때 사용. */
        fun refreshAll(context: Context) {
            val awm = AppWidgetManager.getInstance(context)
            for (cls in listOf(NextExamWidget::class.java, SemesterEndWidget::class.java)) {
                val ids = awm.getAppWidgetIds(ComponentName(context, cls))
                val mode = if (cls == SemesterEndWidget::class.java) Mode.SEMESTER_END else Mode.NEXT_EXAM
                for (id in ids) render(context, awm, id, mode)
            }
        }

        // ── 데이터 ──────────────────────────────────────────────

        /** widget_cache의 모든 주차(week_classes_*)에서 시험 과목만 모은다. */
        private fun loadAllExams(context: Context): List<ExamClass> {
            val prefs = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE)
            val selectedClass = ClassFilter.selectedClass(context)

            val result = mutableListOf<ExamClass>()
            for ((key, value) in prefs.all) {
                if (!key.startsWith("week_classes_")) continue
                val json = value as? String ?: continue
                try {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val title = obj.optString("title")
                        if (isCoursePeriodBanner(title, obj.optString("professor"))) continue
                        if (!isExamClass(title)) continue
                        if (!ClassFilter.matches(title, selectedClass)) continue
                        result.add(
                            ExamClass(
                                title = title,
                                date = LocalDate.parse(obj.optString("date")),
                                startTime = LocalTime.parse(obj.optString("startTime"))
                            )
                        )
                    }
                } catch (_: Exception) { /* 손상된 주는 건너뜀 */ }
            }
            return result
        }

        /**
         * 캐시된 모든 주차의 전체 수업 날짜(중복 제거·정렬). 학기/방학 경계 판정용(A/B 필터 없음).
         * 교과 기간 배너는 제외한다([isCoursePeriodBanner]).
         */
        private fun loadAllClassDates(context: Context): List<LocalDate> {
            val prefs = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE)
            val dates = sortedSetOf<LocalDate>()
            for ((key, value) in prefs.all) {
                if (!key.startsWith("week_classes_")) continue
                val json = value as? String ?: continue
                try {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        if (isCoursePeriodBanner(obj.optString("title"), obj.optString("professor"))) continue
                        dates.add(LocalDate.parse(obj.optString("date")))
                    }
                } catch (_: Exception) { /* 손상된 주는 건너뜀 */ }
            }
            return dates.toList()
        }

        /**
         * 캐시 실물 덤프(디버그 빌드 전용) — 개강/종강 D-day가 실제 수업일과 어긋날 때 쓴다.
         * 오늘 기준 -7 ~ +35일 구간의 항목을 날짜순으로 찍는다.
         *
         * 보는 법: `adb logcat -s CountdownWidget`
         *  - banner=true 로 걸러진 항목이 무엇인지
         *  - 월요일 항목의 title/professor 실제 문자열이 어떤 형태인지
         *
         * release에서 봐야 하면 [AppLog.FORCE_LOG]와 isMinifyEnabled를 함께 끌 것.
         */
        private fun logCacheDump(context: Context, today: LocalDate) {
            val prefs = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE)
            val from = today.minusDays(7)
            val to = today.plusDays(35)

            val rows = mutableListOf<Pair<LocalDate, String>>()
            var weekKeys = 0
            for ((key, value) in prefs.all) {
                if (!key.startsWith("week_classes_")) continue
                weekKeys++
                val json = value as? String ?: continue
                try {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val date = LocalDate.parse(obj.optString("date"))
                        if (date < from || date > to) continue
                        val title = obj.optString("title")
                        val prof = obj.optString("professor")
                        val banner = isCoursePeriodBanner(title, prof)
                        rows.add(
                            date to "$date(${date.dayOfWeek}) ${obj.optString("startTime")}" +
                                "~${obj.optString("endTime")} banner=$banner " +
                                "title=[$title] prof=[$prof]"
                        )
                    }
                } catch (e: Exception) {
                    AppLog.w(TAG, "corrupt week: $key", e)
                }
            }

            AppLog.d(TAG, "── cache dump: today=$today, weekKeys=$weekKeys, rows=${rows.size} ──")
            rows.sortedBy { it.first }.forEach { AppLog.d(TAG, "  ${it.second}") }
        }

        /**
         * 수업 날짜를 학기 블록으로 분리한다. 연속된 두 수업일 사이가
         * [VACATION_GAP_DAYS]일 이상 비면(=그 기간 수업 없음) 방학으로 보고 경계로 삼는다.
         * 반환: 각 학기의 (첫 수업일, 마지막 수업일) 목록.
         */
        private fun segmentSemesters(dates: List<LocalDate>): List<Pair<LocalDate, LocalDate>> {
            if (dates.isEmpty()) return emptyList()
            val blocks = mutableListOf<Pair<LocalDate, LocalDate>>()
            var start = dates.first()
            var prev = dates.first()
            for (d in dates.drop(1)) {
                if (ChronoUnit.DAYS.between(prev, d) >= VACATION_GAP_DAYS) {
                    blocks.add(start to prev)
                    start = d
                }
                prev = d
            }
            blocks.add(start to prev)
            return blocks
        }

        /** true = 캐시에 시험 데이터가 하나라도 있음(즉 캐시가 채워짐). */
        private fun hasAnyCache(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE)
            return prefs.all.keys.any { it.startsWith("week_classes_") }
        }

        // ── 렌더링 ──────────────────────────────────────────────

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int, mode: Mode) {
            val prefs = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE)
            val bgAlpha  = prefs.getInt(keyBgAlpha(widgetId), 235)
            val isDark   = prefs.getBoolean(keyDark(widgetId), false)
            val textSize = prefs.getInt(keyTextSize(widgetId), 100)

            val options = manager.getAppWidgetOptions(widgetId)
            val density = context.resources.displayMetrics.density
            val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 110)
            val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
            val w = (maxW * density).toInt().coerceAtLeast(90)
            val h = (maxH * density).toInt().coerceAtLeast(90)

            val cached = hasAnyCache(context)
            val content = if (!cached) {
                CountdownContent.NeedsSync
            } else {
                val exams = loadAllExams(context)
                val allDates = loadAllClassDates(context)
                logCacheDump(context, LocalDate.now())
                buildContent(mode, exams, allDates)
            }
            AppLog.d(TAG, "render mode=$mode id=$widgetId content=$content")

            val classPrefs = context.getSharedPreferences("class_settings", Context.MODE_PRIVATE)
            val accentHex = classPrefs.getString("block_color", "#8D9DB6") ?: "#8D9DB6"

            // 카드 배경색(틴트)은 비트맵이 아니라 @android:id/background 뷰에 올린다.
            // One UI가 이 반투명 색 뒤 배경화면을 블러해 깔아준다. 알파는 블러 조건상 1~254로 클램프.
            val cardBase = if (isDark) Color.rgb(24, 24, 26) else Color.WHITE
            val tint = Color.argb(
                bgAlpha.coerceIn(1, 254),
                Color.red(cardBase), Color.green(cardBase), Color.blue(cardBase)
            )

            // 내용(글씨)만 든 투명 배경 비트맵. 모서리 곡률은 런처(One UI)가 담당.
            val bitmap = drawCountdown(w, h, density, content, isDark, textSize, accentHex)

            // 터치 시 런처가 앱을 여는 기본 동작을 막기 위한 무동작 인텐트
            // (시간표 위젯과 동일한 NOOP 브로드캐스트 재사용 — 수신되어도 아무것도 안 함)
            val noopPi = android.app.PendingIntent.getBroadcast(
                context, 100 + widgetId,
                Intent(TimetableWidget.ACTION_NOOP).setPackage(context.packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val views = RemoteViews(context.packageName, R.layout.widget_countdown).apply {
                setImageViewBitmap(R.id.countdown_image, bitmap)
                setInt(android.R.id.background, "setBackgroundColor", tint)
                setOnClickPendingIntent(android.R.id.background, noopPi)
            }
            manager.updateAppWidget(widgetId, views)

            // 캐시가 비어 있으면 시간표 워커를 띄워 학기 데이터를 채운다.
            if (!cached) {
                val work = OneTimeWorkRequestBuilder<TimetableWidgetUpdateWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniqueWork("widget_load_countdown", ExistingWorkPolicy.KEEP, work)
            }
        }

        private fun dateLabel(date: LocalDate): String =
            "${date.monthValue}/${date.dayOfMonth} (${dayLabels.getOrElse(date.dayOfWeek.value - 1) { "" }})"

        private fun buildContent(mode: Mode, exams: List<ExamClass>, allDates: List<LocalDate>): CountdownContent {
            val today = LocalDate.now()
            return when (mode) {
                Mode.NEXT_EXAM -> {
                    // 학기와 무관하게 오늘 이후 가장 이른 시험 (방학이면 다음 학기 첫 시험)
                    val next = exams
                        .filter { !it.date.isBefore(today) }
                        .minWithOrNull(compareBy({ it.date }, { it.startTime }))
                        ?: return CountdownContent.NoExam
                    CountdownContent.Data(
                        label = "다음 시험",
                        days = ChronoUnit.DAYS.between(today, next.date),
                        title = next.title,
                        dateText = dateLabel(next.date),
                        namePrimary = true
                    )
                }
                Mode.SEMESTER_END -> {
                    val blocks = segmentSemesters(allDates)
                    AppLog.d(TAG, "blocks=${blocks.map { "${it.first}~${it.second}" }}")
                    if (blocks.isEmpty()) return CountdownContent.NoExam
                    val current = blocks.firstOrNull { !today.isBefore(it.first) && !today.isAfter(it.second) }
                    if (current != null) {
                        // 학기 중 → 이 학기의 마지막 날(마지막 수업일)까지 종강 D-day
                        val target = current.second
                        CountdownContent.Data(
                            label = "종강까지",
                            days = ChronoUnit.DAYS.between(today, target),
                            title = "",
                            dateText = "${dateLabel(target)} 기준",
                            namePrimary = false
                        )
                    } else {
                        // 방학 → 다음 학기 첫 수업까지 개강 D-day (다음 학기 데이터 없으면 '방학 중')
                        val nextStart = blocks.firstOrNull { it.first.isAfter(today) }?.first
                            ?: return CountdownContent.Vacation
                        CountdownContent.Data(
                            label = "개강까지",
                            days = ChronoUnit.DAYS.between(today, nextStart),
                            title = "",
                            dateText = dateLabel(nextStart),
                            namePrimary = false
                        )
                    }
                }
            }
        }

        sealed class CountdownContent {
            object NeedsSync : CountdownContent()
            object NoExam : CountdownContent()
            object Vacation : CountdownContent()   // 방학 중 + 다음 학기 데이터 없음
            data class Data(
                val label: String,
                val days: Long,
                val title: String,
                val dateText: String,
                val namePrimary: Boolean  // true=시험 이름이 주인공(다음 시험), false=D-day가 주인공(종강/개강)
            ) : CountdownContent()
        }


        private fun drawCountdown(
            width: Int,
            height: Int,
            density: Float,
            content: CountdownContent,
            isDark: Boolean,
            textSizeScale: Int,
            accentHex: String
        ): Bitmap {
            // 투명 배경 비트맵 — 카드 배경/틴트는 @android:id/background 뷰가 담당(블러용),
            // 모서리 곡률은 런처(One UI)가 담당. 여기선 내용(글씨)만 그린다.
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val base = try { Color.parseColor(accentHex) } catch (_: Exception) { Color.parseColor("#8D9DB6") }
            // 라이트: 지정색 그대로. 다크: 앱 시험 블록(examColor)과 동일한 +20% 밝게 공식으로 가독성 확보.
            val accentDraw = if (isDark)
                Color.rgb(
                    Color.red(base)   + ((255 - Color.red(base))   * 0.20f).toInt(),
                    Color.green(base) + ((255 - Color.green(base)) * 0.20f).toInt(),
                    Color.blue(base)  + ((255 - Color.blue(base))  * 0.20f).toInt()
                )
            else base

            // 글씨 크기가 위젯 크기에 정비례하면 변화가 너무 크므로, pivot 기준으로
            // 제곱근류 곡선(지수<1)을 씌워 크기 변화율을 완만하게 만든다.
            // pivot 부근에서는 기존과 거의 같고, 키우면 천천히 커지고 줄이면 천천히 작아진다.
            val rawRef = kotlin.math.sqrt(width.toFloat() * height.toFloat())
            val pivot = SIZE_PIVOT_DP * density
            val ref = pivot * Math.pow((rawRef / pivot).toDouble(), SIZE_DAMP).toFloat()
            val scale = textSizeScale / 100f
            val hDp = height / density

            val subColor  = if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#888888")
            val titleColor = if (isDark) Color.parseColor("#E8E8E8") else Color.parseColor("#333333")

            when (content) {
                is CountdownContent.NeedsSync -> {
                    drawCenteredMessage(canvas, width, height, ref, scale, subColor,
                        "앱을 열어\n동기화하세요")
                }
                is CountdownContent.NoExam -> {
                    drawCenteredMessage(canvas, width, height, ref, scale, subColor,
                        "예정된\n시험 없음")
                }
                is CountdownContent.Vacation -> {
                    drawCenteredMessage(canvas, width, height, ref, scale, subColor,
                        "방학 중")
                }
                is CountdownContent.Data -> {
                    drawData(canvas, width, height, hDp, ref, scale,
                        content, accentDraw, subColor, titleColor)
                }
            }
            return bitmap
        }

        private fun drawData(
            canvas: Canvas, width: Int, height: Int, hDp: Float,
            ref: Float, scale: Float, data: CountdownContent.Data,
            accent: Int, subColor: Int, titleColor: Int
        ) {
            if (data.namePrimary)
                drawNamePrimary(canvas, width, height, hDp, ref, scale, data, accent, subColor, titleColor)
            else
                drawDayPrimary(canvas, width, height, hDp, ref, scale, data, accent, subColor, titleColor)
        }

        /** 다음 시험: 시험 "이름"이 주인공. 날짜·남은 일수는 보조. */
        private fun drawNamePrimary(
            canvas: Canvas, width: Int, height: Int, hDp: Float,
            ref: Float, scale: Float, data: CountdownContent.Data,
            accent: Int, subColor: Int, titleColor: Int
        ) {
            val showLabel = hDp >= 82f
            val showDate  = hDp >= 100f

            // 보조 날짜/남은일수 라인
            val dayText = when {
                data.days == 0L -> "오늘"
                data.days == 1L -> "내일"
                data.days < 0L  -> ""
                else            -> "${data.days}일 후"
            }
            val dateLine = if (dayText.isEmpty()) data.dateText else "${data.dateText} · $dayText"

            // 라벨("다음 시험")은 종강 위젯의 "종강까지" 라벨과 같은 크기(≈ref*0.090)로 맞춘다.
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent; textAlign = Paint.Align.CENTER
                isFakeBoldText = true; textSize = ref * 0.090f * scale
            }
            val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = subColor; textAlign = Paint.Align.CENTER; textSize = ref * 0.066f * scale
            }
            fun lineH(p: Paint): Float { val fm = p.fontMetrics; return fm.descent - fm.ascent }

            val labelH = if (showLabel) lineH(labelPaint) + ref * 0.02f else 0f
            val dateH  = if (showDate)  lineH(datePaint) + ref * 0.02f else 0f
            // 작을수록(1×1) 여백을 줄여 이름이 차지할 공간을 넓힌다.
            val availNameH = (height - labelH - dateH - height * 0.06f).coerceAtLeast(lineH(labelPaint))

            // 이름: 공백 우선 줄바꿈 + 폭·높이에 맞춰 자동 축소, 최대 3줄
            // 라벨보다 크지만 과하지 않게(≈라벨의 1.7배).
            val maxLines = 3
            val nameMaxW = width * 0.92f
            var nameSize = minOf(ref * 0.155f, height * 0.34f) * scale
            val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = titleColor; textAlign = Paint.Align.CENTER
                isFakeBoldText = true; textSize = nameSize
            }
            var lines = wrapPreferSpace(data.title, namePaint, nameMaxW)
            while ((lines.size * lineH(namePaint) > availNameH || lines.size > maxLines) && nameSize > 9f) {
                nameSize -= 1f; namePaint.textSize = nameSize
                lines = wrapPreferSpace(data.title, namePaint, nameMaxW)
            }
            // 그래도 3줄을 넘으면 마지막 줄을 말줄임 처리
            if (lines.size > maxLines) {
                val kept = lines.take(maxLines).toMutableList()
                var last = kept[maxLines - 1]
                while (namePaint.measureText("$last…") > nameMaxW && last.length > 1) last = last.dropLast(1)
                kept[maxLines - 1] = "$last…"
                lines = kept
            }

            val nameFm = namePaint.fontMetrics
            val nameLineH = nameFm.descent - nameFm.ascent
            val nameBlockH = lines.size * nameLineH
            val total = labelH + nameBlockH + dateH
            var y = (height - total) / 2f
            val cx = width / 2f

            if (showLabel) {
                val fm = labelPaint.fontMetrics
                canvas.drawText(data.label, cx, y - fm.ascent, labelPaint)
                y += labelH
            }
            for (line in lines) {
                y -= nameFm.ascent
                canvas.drawText(line, cx, y, namePaint)
                y += nameFm.descent
            }

            if (showDate) {
                val fm = datePaint.fontMetrics
                y += ref * 0.02f
                canvas.drawText(dateLine, cx, y - fm.ascent, datePaint)
            }
        }

        /**
         * 공백을 우선 끊는 줄바꿈. "과목명 시험종류"를 공백에서 나눠 자연스럽게 감싼다.
         * 한 토큰이 한 줄보다 길면(공백 없는 긴 단어) 그 토큰만 글자 단위로 나눈다.
         */
        private fun wrapPreferSpace(text: String, paint: Paint, maxWidth: Float): List<String> {
            val lines = mutableListOf<String>()
            var current = ""
            fun flush() { if (current.isNotEmpty()) { lines.add(current); current = "" } }
            for (token in text.split(" ").filter { it.isNotEmpty() }) {
                val candidate = if (current.isEmpty()) token else "$current $token"
                when {
                    paint.measureText(candidate) <= maxWidth -> current = candidate
                    paint.measureText(token) <= maxWidth -> { flush(); current = token }
                    else -> {
                        // 공백 없는 긴 토큰 → 글자 단위로 나눔
                        flush()
                        var chunk = ""
                        for (ch in token) {
                            if (paint.measureText(chunk + ch) <= maxWidth) chunk += ch
                            else { if (chunk.isNotEmpty()) lines.add(chunk); chunk = ch.toString() }
                        }
                        current = chunk
                    }
                }
            }
            flush()
            return if (lines.isEmpty()) listOf(text) else lines
        }

        /** 종강: 남은 "D-day"가 주인공. */
        private fun drawDayPrimary(
            canvas: Canvas, width: Int, height: Int, hDp: Float,
            ref: Float, scale: Float, data: CountdownContent.Data,
            accent: Int, subColor: Int, titleColor: Int
        ) {
            // 종강 위젯은 라벨 + D-day + 날짜만. 어떤 시험인지(이름)는 표시하지 않는다.
            val showLabel = hDp >= 82f
            val showDate  = hDp >= 118f

            // D-day 텍스트
            val ddayText = when {
                data.days == 0L -> "D-DAY"
                data.days < 0L  -> "종강"
                else            -> "D-${data.days}"
            }

            // 메인 숫자: 폭에 맞춰 자동 축소. 작을수록(1×1) 여백 줄이고 크게.
            var mainSize = minOf(ref * 0.32f, height * 0.56f) * scale
            val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent; textAlign = Paint.Align.CENTER
                isFakeBoldText = true; textSize = mainSize
            }
            while (mainPaint.measureText(ddayText) > width * 0.92f && mainSize > 8f) {
                mainSize -= 1f; mainPaint.textSize = mainSize
            }

            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = subColor; textAlign = Paint.Align.CENTER; textSize = mainSize * 0.28f
            }
            val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = subColor; textAlign = Paint.Align.CENTER; textSize = mainSize * 0.22f
            }

            fun lineH(p: Paint): Float { val fm = p.fontMetrics; return fm.descent - fm.ascent }

            val gapS = mainSize * 0.08f
            val gapM = mainSize * 0.12f

            val lines = mutableListOf<Pair<Paint, String>>()
            if (showLabel) lines.add(labelPaint to data.label)
            lines.add(mainPaint to ddayText)
            if (showDate)  lines.add(datePaint to data.dateText)

            var total = 0f
            for ((i, l) in lines.withIndex()) {
                total += lineH(l.first)
                if (i < lines.size - 1) total += if (l.first == mainPaint || lines[i + 1].first == mainPaint) gapS else gapM
            }

            var y = (height - total) / 2f
            val cx = width / 2f
            for (l in lines) {
                val fm = l.first.fontMetrics
                y -= fm.ascent
                canvas.drawText(l.second, cx, y, l.first)
                y += fm.descent
                y += if (l.first == mainPaint) gapS else gapM
            }
        }

        private fun drawCenteredMessage(
            canvas: Canvas, width: Int, height: Int, ref: Float, scale: Float,
            color: Int, message: String
        ) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; textAlign = Paint.Align.CENTER
                textSize = ref * 0.075f * scale
            }
            val fm = paint.fontMetrics
            val lineH = fm.descent - fm.ascent
            val msgLines = message.split("\n")
            var y = (height - lineH * msgLines.size) / 2f - fm.ascent
            for (line in msgLines) {
                canvas.drawText(line, width / 2f, y, paint)
                y += lineH
            }
        }
    }
}
