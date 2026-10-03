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
import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalTime

class TimetableWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val prefs = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE)
        when (intent.action) {
            ACTION_PREV_WEEK -> {
                prefs.edit().putInt(KEY_WEEK_OFFSET, prefs.getInt(KEY_WEEK_OFFSET, 0) - 1).apply()
                refreshAll(context)
            }
            ACTION_NEXT_WEEK -> {
                prefs.edit().putInt(KEY_WEEK_OFFSET, prefs.getInt(KEY_WEEK_OFFSET, 0) + 1).apply()
                refreshAll(context)
            }
        }
    }

    companion object {

        const val ACTION_PREV_WEEK = "com.cnumed.mlms.WIDGET_PREV_WEEK"
        const val ACTION_NEXT_WEEK = "com.cnumed.mlms.WIDGET_NEXT_WEEK"
        // 핸들러 없는 영역 탭 시 런처가 앱을 여는 기본 동작(One UI 8.5 등)을 막기 위한 무동작 인텐트
        const val ACTION_NOOP      = "com.cnumed.mlms.WIDGET_NOOP"
        const val PREFS_WIDGET     = "widget_cache"

        // 글씨 크기 감쇠: SIZE_DAMP<1 이면 위젯 크기 변화에 덜 민감. pivot 부근은 기존과 동일.
        // pivot은 이 위젯의 기본 크기(4×4, ref≈260dp)에 맞춰 기본 크기에선 원래 크기 유지.
        private const val SIZE_DAMP = 0.55
        private const val SIZE_PIVOT_DP = 260f

        const val KEY_WEEK_OFFSET  = "widget_week_offset"
        // offset이 기준 삼았던 "현재 주 일요일". 실제 달력상 주가 바뀌면 offset을 0으로
        // 되돌려, 넘겨봤던 주가 현재 주에 중복으로 얹혀 한 주 더 밀리는 것을 막는다.
        const val KEY_WEEK_ANCHOR  = "widget_week_anchor"
        const val KEY_BG_ALPHA     = "widget_bg_alpha"
        const val KEY_BLOCK_ALPHA  = "widget_block_alpha"
        const val KEY_DARK_MODE    = "widget_dark_mode"
        const val KEY_TEXT_SIZE    = "widget_text_size"

        data class WidgetClass(
            val title: String,
            val professor: String,
            val dayOfWeek: Int,
            val date: LocalDate,
            val startTime: LocalTime,
            val endTime: LocalTime
        )

        private fun refreshAll(context: Context) {
            val awm = AppWidgetManager.getInstance(context)
            val ids = awm.getAppWidgetIds(ComponentName(context, TimetableWidget::class.java))
            for (id in ids) updateWidget(context, awm, id)
        }

        fun currentWeekSunday(): LocalDate {
            val today = LocalDate.now()
            val dow = today.dayOfWeek.value
            return today.minusDays((dow % 7).toLong())
        }

        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs      = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE)

            // 실제 주가 바뀌면 offset을 0으로 초기화 → 위젯을 현재 주에 다시 맞춘다.
            // (앵커가 없거나 저장된 주와 다르면 주가 바뀐 것으로 판단)
            val currentSunday = currentWeekSunday()
            if (prefs.getString(KEY_WEEK_ANCHOR, null) != currentSunday.toString()) {
                prefs.edit()
                    .putString(KEY_WEEK_ANCHOR, currentSunday.toString())
                    .putInt(KEY_WEEK_OFFSET, 0)
                    .apply()
            }

            val weekOffset = prefs.getInt(KEY_WEEK_OFFSET, 0)
            val bgAlpha    = prefs.getInt(KEY_BG_ALPHA, 255)
            val blockAlpha = prefs.getInt(KEY_BLOCK_ALPHA, 255)
            val isDarkMode = prefs.getBoolean(KEY_DARK_MODE, false)
            val textSize   = prefs.getInt(KEY_TEXT_SIZE, 100)

            val weekStart = currentWeekSunday().plusWeeks(weekOffset.toLong())
            val classes   = loadClasses(prefs, weekStart)  // null = 미캐시, list = 캐시됨

            val classPrefs = context.getSharedPreferences("class_settings", Context.MODE_PRIVATE)
            val selectedClass = classPrefs.getString("selected_class", "A") ?: "A"
            val customColorHex = classPrefs.getString("block_color", "#8D9DB6") ?: "#8D9DB6"
            val examHighlight = classPrefs.getBoolean("exam_highlight", true)
            val invertedKeys = ClassColorPrefs.invertedKeys(context)

            val filteredClasses = if (selectedClass == "ALL") {
                classes ?: emptyList()
            } else {
                classes?.filter { cls ->
                    val title = cls.title.uppercase()
                    when {
                        "AB" in title || "AB 반" in title || "A/B" in title -> true
                        "A반" in title || "A 반" in title -> selectedClass == "A"
                        "B반" in title || "B 반" in title -> selectedClass == "B"
                        else -> true
                    }
                } ?: emptyList()
            }

            val options = manager.getAppWidgetOptions(widgetId)
            val density = context.resources.displayMetrics.density
            val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 300)
            val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250)
            val w = (maxW * density).toInt().coerceAtLeast(200)
            val h = (maxH * density).toInt().coerceAtLeast(140)

            // null = 아직 캐시 없음 → 로딩 화면
            // emptyList or classes with data = 캐시 있음 → 시간표 그리기 (빈 주도 그림)
            val isLoading = (classes == null)
            val bitmap = if (isLoading) {
                drawLoadingBitmap(w, h, density, weekStart, isDarkMode, bgAlpha)
            } else {
                drawTimetable(w, h, density, weekStart, filteredClasses, isDarkMode, bgAlpha, textSize, customColorHex, blockAlpha, examHighlight, invertedKeys)
            }

            val prevPi = android.app.PendingIntent.getBroadcast(
                context, 1, Intent(ACTION_PREV_WEEK).setPackage(context.packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val nextPi = android.app.PendingIntent.getBroadcast(
                context, 2, Intent(ACTION_NEXT_WEEK).setPackage(context.packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            // 루트 전체에 무동작 클릭을 깔아 본문 탭으로 앱이 열리는 것을 방지한다.
            // 자식 뷰(prev/next)는 자기 영역에서 우선하므로 주 이동 버튼은 그대로 동작한다.
            val noopPi = android.app.PendingIntent.getBroadcast(
                context, 3, Intent(ACTION_NOOP).setPackage(context.packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            // 카드 배경색(틴트)은 비트맵이 아니라 @android:id/background 뷰에 올린다.
            // One UI가 이 반투명 색 뒤 배경화면을 블러해 깔아주고, 모서리 곡률도 담당한다.
            // 알파는 블러 조건상 1~254로 클램프.
            val cardBase = if (isDarkMode) Color.rgb(0, 0, 0) else Color.WHITE
            val tint = Color.argb(
                bgAlpha.coerceIn(1, 254),
                Color.red(cardBase), Color.green(cardBase), Color.blue(cardBase)
            )

            val views = RemoteViews(context.packageName, R.layout.widget_timetable).apply {
                setImageViewBitmap(R.id.widget_image, bitmap)
                setInt(android.R.id.background, "setBackgroundColor", tint)
                setOnClickPendingIntent(android.R.id.background, noopPi)
                setOnClickPendingIntent(R.id.widget_btn_prev, prevPi)
                setOnClickPendingIntent(R.id.widget_btn_next, nextPi)
            }
            manager.updateAppWidget(widgetId, views)

            if (isLoading) {
                val workRequest = OneTimeWorkRequestBuilder<TimetableWidgetUpdateWorker>()
                    .setInputData(workDataOf("weekStart" to weekStart.toString()))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniqueWork("widget_load_$weekStart", ExistingWorkPolicy.KEEP, workRequest)
            }
        }

        private data class WidgetBlockLayout(val x: Float, val bw: Float, val y1: Float, val bh: Float)

        private fun timesOverlap(s1: LocalTime, e1: LocalTime, s2: LocalTime, e2: LocalTime) =
            s1 < e2 && s2 < e1

        private fun buildWidgetLayoutMap(
            classes: List<WidgetClass>,
            timeColW: Float, colW: Float,
            headerH: Float, hourH: Float,
            startH: Int, totalH: Int
        ): Map<WidgetClass, WidgetBlockLayout> {
            fun ceilTo30Min(hour: Int, minute: Int) = when {
                minute == 0  -> hour * 60
                minute <= 30 -> hour * 60 + 30
                else         -> (hour + 1) * 60
            }

            val result = mutableMapOf<WidgetClass, WidgetBlockLayout>()
            val byDay = classes.groupBy { it.dayOfWeek }

            for ((day, dayClasses) in byDay) {
                val col = day - 1
                if (col < 0 || col > 4) continue
                val colX = timeColW + col * colW

                val valid = dayClasses.filter { cls ->
                    val s = ceilTo30Min(cls.startTime.hour, cls.startTime.minute) - startH * 60
                    val e = ceilTo30Min(cls.endTime.hour, cls.endTime.minute) - startH * 60
                    s >= 0 && e > s && s < totalH * 60
                }

                val sorted = valid.sortedWith(compareBy({ it.startTime }, { it.title }))
                val laneEndTimes = mutableListOf<LocalTime>()
                val laneOf = mutableMapOf<WidgetClass, Int>()

                for (cls in sorted) {
                    var lane = laneEndTimes.indexOfFirst { !it.isAfter(cls.startTime) }
                    if (lane == -1) { lane = laneEndTimes.size; laneEndTimes.add(cls.endTime) }
                    else laneEndTimes[lane] = cls.endTime
                    laneOf[cls] = lane
                }

                for (cls in valid) {
                    val totalLanes = (valid
                        .filter { other -> timesOverlap(cls.startTime, cls.endTime, other.startTime, other.endTime) }
                        .mapNotNull { laneOf[it] }
                        .maxOrNull() ?: 0) + 1

                    val lane = laneOf[cls] ?: 0
                    val gap  = if (totalLanes > 1) 1f else 0f
                    val slotW = (colW - 2f - gap * (totalLanes - 1)) / totalLanes
                    val x  = colX + 1f + lane * (slotW + gap)
                    // 맨 오른쪽 열(금)의 가장 오른쪽 블록은 위젯 우측 끝까지 붙인다.
                    // (안 그러면 블록 오른쪽 1px 여백에 반투명 배경화면이 비쳐 세로선처럼 보인다)
                    val right = if (col == 4 && lane == totalLanes - 1) colX + colW else x + slotW
                    val bw = right - x
                    val s  = ceilTo30Min(cls.startTime.hour, cls.startTime.minute) - startH * 60
                    val e  = ceilTo30Min(cls.endTime.hour, cls.endTime.minute) - startH * 60
                    val eCapped = e.coerceAtMost(totalH * 60)
                    val y1 = headerH + s / 60f * hourH
                    val y2 = headerH + eCapped / 60f * hourH
                    // 맨 아래 교시까지 닿는 블록은 위젯 하단 끝까지 붙인다(우측과 동일한 이유).
                    val bottomTrim = if (eCapped >= totalH * 60) 0f else 1f
                    val bh = (y2 - y1 - bottomTrim).coerceAtLeast(0f)
                    if (bh >= 3f) result[cls] = WidgetBlockLayout(x, bw.coerceAtLeast(2f), y1, bh)
                }
            }
            return result
        }

        // null  = 아직 캐시 없음(로딩 중) / emptyList = 가져왔는데 수업이 없는 주
        fun loadClasses(prefs: android.content.SharedPreferences, weekStart: LocalDate): List<WidgetClass>? {
            val json = prefs.getString("week_classes_$weekStart", null) ?: return null
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.getJSONObject(i)
                    WidgetClass(
                        title     = obj.optString("title"),
                        professor = obj.optString("professor", ""),
                        dayOfWeek = obj.optInt("dayOfWeek"),
                        date      = LocalDate.parse(obj.optString("date")),
                        startTime = LocalTime.parse(obj.optString("startTime")),
                        endTime   = LocalTime.parse(obj.optString("endTime"))
                    )
                }
            } catch (_: Exception) { null }  // 파싱 오류 → 재시도
        }

        private fun drawLoadingBitmap(
            width: Int,
            height: Int,
            density: Float,
            weekStart: LocalDate,
            isDarkMode: Boolean,
            bgAlpha: Int
        ): Bitmap {
            // 투명 배경 — 카드 틴트/블러/곡률은 @android:id/background 뷰 + 런처가 담당.
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // drawTimetable과 동일하게 √(w*h) 기준으로 글씨 크기를 잡아 회전 시 안정화한다.
            val rawRef = kotlin.math.sqrt(width.toFloat() * height.toFloat())
            val pivot = SIZE_PIVOT_DP * density
            val ref = pivot * Math.pow((rawRef / pivot).toDouble(), SIZE_DAMP).toFloat()
            val textColor = if (isDarkMode) Color.parseColor("#AAAAAA") else Color.parseColor("#666666")
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textAlign = Paint.Align.CENTER
                textSize = ref * 0.0277f
            }
            canvas.drawText("시간표 불러오는 중…", width / 2f, height / 2f, textPaint)
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                alpha = 160
                textAlign = Paint.Align.CENTER
                textSize = ref * 0.0198f
            }
            val monday = weekStart.plusDays(1)
            val sunday = weekStart.plusDays(7)
            val rangeText = "${monday.monthValue}/${monday.dayOfMonth} – ${sunday.monthValue}/${sunday.dayOfMonth}"
            canvas.drawText(rangeText, width / 2f, height / 2f + height * 0.09f, subPaint)
            return bitmap
        }

        private fun drawTimetable(
            width: Int,
            height: Int,
            density: Float,
            weekStart: LocalDate,
            classes: List<WidgetClass>,
            isDarkMode: Boolean,
            bgAlpha: Int,
            textSizeScale: Int,
            customColorHex: String,
            blockAlpha: Int,
            examHighlight: Boolean = true,
            invertedKeys: Set<String> = emptySet()
        ): Bitmap {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val today  = LocalDate.now()

            val customColor = try {
                Color.parseColor(customColorHex)
            } catch (e: Exception) {
                Color.parseColor("#8D9DB6")
            }

            val highlightAlpha = if (isDarkMode) 40 else 25
            val todayColColor = Color.argb(
                highlightAlpha,
                Color.red(customColor),
                Color.green(customColor),
                Color.blue(customColor)
            )

            val gridColor = if (isDarkMode) Color.parseColor("#3A3A3A") else Color.parseColor("#E0E0E0")
            val textColor = if (isDarkMode) Color.parseColor("#AAAAAA") else Color.parseColor("#666666")

            val headerH  = height * 0.035f
            val timeColW = headerH
            val colW     = (width - timeColW) / 5f
            val startH   = 9
            val endH     = 18
            val totalH   = endH - startH
            val hourH    = (height - headerH) / totalH

            // 글씨 크기는 높이만이 아니라 가로·세로의 기하평균에 비례시킨다.
            // 높이에만 비례시키면 태블릿을 세로로 돌릴 때 위젯 높이는 커지고 열 폭은
            // 좁아져서 글씨가 과도하게 커진다. √(w*h)는 회전해도 거의 일정해 안정적이다.
            // 위젯 크기 변화에 덜 민감하도록 ref를 감쇠(pivot 기준 지수<1)시킨다.
            // 감쇠가 커지는 속도를 이미 잡아주므로 별도 절대 상한은 두지 않는다.
            val rawRef = kotlin.math.sqrt(width.toFloat() * height.toFloat())
            val pivot = SIZE_PIVOT_DP * density
            val ref = pivot * Math.pow((rawRef / pivot).toDouble(), SIZE_DAMP).toFloat()
            val textSm = ref * 0.0310f * (textSizeScale.toFloat() / 100f)
            val textXs = ref * 0.0320f * (textSizeScale.toFloat() / 100f)

            val todayColPaint = Paint().apply { color = todayColColor; style = Paint.Style.FILL }
            val gridPaint     = Paint().apply { color = gridColor; strokeWidth = 0.8f }

            val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor; textAlign = Paint.Align.CENTER; textSize = textSm
            }
            val todayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isDarkMode) Color.WHITE else Color.parseColor("#555555")
                textAlign = Paint.Align.CENTER; textSize = textSm; isFakeBoldText = true
            }
            val timeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor; textAlign = Paint.Align.RIGHT; textSize = textXs
            }
            val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val blockTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = textXs; isFakeBoldText = true
            }

            val weekEnd = weekStart.plusDays(6)
            if (today >= weekStart && today <= weekEnd) {
                val todayDow = today.dayOfWeek.value
                if (todayDow in 1..5) {
                    val x = timeColW + (todayDow - 1) * colW
                    canvas.drawRect(x, 0f, x + colW, height.toFloat(), todayColPaint)
                }
            }

            val dayLabels = listOf("월", "화", "수", "목", "금")
            for (i in 0..4) {
                val date    = weekStart.plusDays((i + 1).toLong())
                val cx      = timeColW + i * colW + colW / 2f
                val cy      = headerH * 0.60f
                val isToday = date == today
                val label   = "${dayLabels[i]} ${date.monthValue}/${date.dayOfMonth}"
                if (isToday) {
                    canvas.drawText(label, cx, cy + textSm * 0.26f, todayTextPaint)
                } else {
                    canvas.drawText(label, cx, cy + textSm * 0.26f, headerTextPaint)
                }
            }

            // 맨 바깥 세로선(i=5, 오른쪽 끝)·가로선(h=totalH, 아래 끝)은 그리지 않는다.
            // 라운딩된 모서리를 따라가지 못하고 가장자리에 직선 테두리처럼 남기 때문.
            for (i in 0..4) {
                canvas.drawLine(timeColW + i * colW, headerH, timeColW + i * colW, height.toFloat(), gridPaint)
            }
            for (h in 0 until totalH) {
                val y = headerH + h * hourH
                canvas.drawLine(timeColW, y, width.toFloat(), y, gridPaint)
            }
            for (h in 0..totalH) {
                val y = headerH + h * hourH + textXs * 0.88f
                canvas.drawText("${startH + h}", timeColW - 7f, y, timeTextPaint)
            }

            val titleFm = Paint.FontMetrics()
            blockTextPaint.getFontMetrics(titleFm)
            val titleLineH = titleFm.descent - titleFm.ascent

            val profPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                alpha = 210
                textSize = textXs * 0.88f
            }
            val profFm = Paint.FontMetrics()
            profPaint.getFontMetrics(profFm)
            val profLineH = profFm.descent - profFm.ascent

            blockPaint.color = customColor
            blockPaint.alpha = blockAlpha

            val layoutMap = buildWidgetLayoutMap(classes, timeColW, colW, headerH, hourH, startH, totalH)

            for (cls in classes) {
                val layout = layoutMap[cls] ?: continue

                blockPaint.color = ClassColorPrefs.blockColorFor(
                    cls.title, cls.date, cls.startTime,
                    customColor, invertedKeys, examHighlight, isDarkMode
                )
                blockPaint.alpha = blockAlpha
                canvas.drawRoundRect(RectF(layout.x, layout.y1, layout.x + layout.bw, layout.y1 + layout.bh), 3f, 3f, blockPaint)

                // 교수 이름 공간을 미리 예약해 제목 클리핑 높이 결정
                val hasProfessor = cls.professor.isNotEmpty()
                val profSpace = if (hasProfessor) profLineH + 4f else 0f
                val titleMaxH = (layout.bh - 6f - profSpace).coerceAtLeast(titleLineH)

                val textLayout = android.text.StaticLayout.Builder.obtain(
                    cls.title, 0, cls.title.length,
                    android.text.TextPaint(blockTextPaint),
                    (layout.bw - 6f).toInt().coerceAtLeast(1)
                )
                    .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                    .build()

                canvas.save()
                canvas.translate(layout.x + 3f, layout.y1 + 3f)
                canvas.clipRect(0f, 0f, layout.bw - 6f, titleMaxH)
                textLayout.draw(canvas)
                canvas.restore()

                if (hasProfessor) {
                    val actualTitleH = textLayout.height.toFloat().coerceAtMost(titleMaxH)
                    val profY = layout.y1 + 3f + actualTitleH + 4f
                    if (profY + profLineH < layout.y1 + layout.bh) {
                        canvas.drawText(cls.professor, layout.x + 3f, profY - profFm.ascent, profPaint)
                    }
                }
            }
            return bitmap
        }
    }
}