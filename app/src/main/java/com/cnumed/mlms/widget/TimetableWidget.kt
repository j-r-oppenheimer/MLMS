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
        const val PREFS_WIDGET     = "widget_cache"
        const val KEY_WEEK_OFFSET  = "widget_week_offset"
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
                drawLoadingBitmap(w, h, weekStart, isDarkMode, bgAlpha)
            } else {
                drawTimetable(w, h, weekStart, filteredClasses, isDarkMode, bgAlpha, textSize, customColorHex, blockAlpha)
            }

            val prevPi = android.app.PendingIntent.getBroadcast(
                context, 1, Intent(ACTION_PREV_WEEK).setPackage(context.packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val nextPi = android.app.PendingIntent.getBroadcast(
                context, 2, Intent(ACTION_NEXT_WEEK).setPackage(context.packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val views = RemoteViews(context.packageName, R.layout.widget_timetable).apply {
                setImageViewBitmap(R.id.widget_image, bitmap)
                setOnClickPendingIntent(R.id.widget_btn_prev, prevPi)
                setOnClickPendingIntent(R.id.widget_btn_next, nextPi)
                if (bgAlpha >= 255 && !isDarkMode) {
                    setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_background)
                } else {
                    setInt(R.id.widget_root, "setBackgroundColor", Color.TRANSPARENT)
                }
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
                    val s  = ceilTo30Min(cls.startTime.hour, cls.startTime.minute) - startH * 60
                    val e  = ceilTo30Min(cls.endTime.hour, cls.endTime.minute) - startH * 60
                    val y1 = headerH + s / 60f * hourH
                    val y2 = headerH + e.coerceAtMost(totalH * 60) / 60f * hourH
                    val bh = (y2 - y1 - 1f).coerceAtLeast(0f)
                    if (bh >= 3f) result[cls] = WidgetBlockLayout(x, slotW.coerceAtLeast(2f), y1, bh)
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
            weekStart: LocalDate,
            isDarkMode: Boolean,
            bgAlpha: Int
        ): Bitmap {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val bgR = if (isDarkMode) 0 else 255
            val bgG = if (isDarkMode) 0 else 255
            val bgB = if (isDarkMode) 0 else 255
            canvas.drawRect(
                0f, 0f, width.toFloat(), height.toFloat(),
                Paint().apply { color = Color.argb(bgAlpha, bgR, bgG, bgB) }
            )
            val textColor = if (isDarkMode) Color.parseColor("#AAAAAA") else Color.parseColor("#666666")
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textAlign = Paint.Align.CENTER
                textSize = height * 0.035f
            }
            canvas.drawText("시간표 불러오는 중…", width / 2f, height / 2f, textPaint)
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                alpha = 160
                textAlign = Paint.Align.CENTER
                textSize = height * 0.025f
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
            weekStart: LocalDate,
            classes: List<WidgetClass>,
            isDarkMode: Boolean,
            bgAlpha: Int,
            textSizeScale: Int,
            customColorHex: String,
            blockAlpha: Int
        ): Bitmap {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val today  = LocalDate.now()

            val customColor = try {
                Color.parseColor(customColorHex)
            } catch (e: Exception) {
                Color.parseColor("#8D9DB6")
            }

            val bgR = if (isDarkMode) 0 else 255
            val bgG = if (isDarkMode) 0 else 255
            val bgB = if (isDarkMode) 0 else 255

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

            val textSm = height * 0.027f * (textSizeScale.toFloat() / 100f)
            val textXs = height * 0.028f * (textSizeScale.toFloat() / 100f)

            val bgPaint      = Paint().apply { color = Color.argb(bgAlpha, bgR, bgG, bgB) }
            val todayColPaint = Paint().apply { color = todayColColor; style = Paint.Style.FILL }
            val gridPaint     = Paint().apply { color = gridColor; strokeWidth = 0.8f }

            val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor; textAlign = Paint.Align.CENTER; textSize = textSm
            }
            val todayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isDarkMode) Color.WHITE else Color.parseColor("#333333")
                textAlign = Paint.Align.CENTER; textSize = textSm; isFakeBoldText = true
            }
            val timeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor; textAlign = Paint.Align.RIGHT; textSize = textXs
            }
            val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val blockTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = textXs; isFakeBoldText = true
            }

            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

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

            for (i in 0..5) {
                canvas.drawLine(timeColW + i * colW, headerH, timeColW + i * colW, height.toFloat(), gridPaint)
            }
            for (h in 0..totalH) {
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