package com.cnumed.mlms.ui.timetable

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.cnumed.mlms.domain.model.ClassItem
import java.time.LocalDate
import java.time.LocalTime

class TimetableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var classes: List<ClassItem> = emptyList()
    private var weekStart: LocalDate = LocalDate.now()
    private val today: LocalDate = LocalDate.now()

    private var onClassClick: ((ClassItem) -> Unit)? = null
    fun setOnClassClickListener(listener: (ClassItem) -> Unit) { onClassClick = listener }

    private var blockColor: Int = Color.parseColor("#8D9DB6")
    private var isDarkMode: Boolean = false
    private var examHighlightEnabled: Boolean = true

    private val examKeywords = listOf("시험", "중간", "기말", "평가")

    private fun isExamClass(title: String) = examKeywords.any { it in title }

    private fun examColor(base: Int, dark: Boolean): Int {
        val r = Color.red(base); val g = Color.green(base); val b = Color.blue(base)
        return if (dark)
            Color.rgb(r + ((255 - r) * 0.20f).toInt(), g + ((255 - g) * 0.20f).toInt(), b + ((255 - b) * 0.20f).toInt())
        else
            Color.rgb((r * 0.85f).toInt(), (g * 0.85f).toInt(), (b * 0.85f).toInt())
    }

    private val dp get() = context.resources.displayMetrics.density
    private val timeColW get() = 28f * dp
    private val headerH get() = 32f * dp
    private val startHour = 9
    private val endHour = 19
    private val totalHours = endHour - startHour
    // 실제 뷰 높이에서 동적 계산 — 화면을 꽉 채움
    private val hourH: Float
        get() = if (height > 0) ((height - headerH) / totalHours).coerceAtLeast(30f * dp)
                else 60f * dp

    private val bgPaint = Paint()
    private val gridPaint = Paint().apply { strokeWidth = 1f }
    private val halfGridPaint = Paint().apply { strokeWidth = 0.5f }
    private val headerBgPaint = Paint()

    private val todayCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val todayHeaderTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val timeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EEEEEE") }

    private fun updateThemeColors() {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        isDarkMode = (uiMode == Configuration.UI_MODE_NIGHT_YES)

        bgPaint.color = if (isDarkMode) Color.parseColor("#121212") else Color.WHITE
        gridPaint.color = if (isDarkMode) Color.parseColor("#333333") else Color.parseColor("#E8E8E8")
        halfGridPaint.color = if (isDarkMode) Color.parseColor("#222222") else Color.parseColor("#F2F2F2")
        headerBgPaint.color = if (isDarkMode) Color.parseColor("#1E1E1E") else Color.parseColor("#FAFAFA")
        headerTextPaint.color = if (isDarkMode) Color.parseColor("#DDDDDD") else Color.parseColor("#444444")
        timeTextPaint.color = if (isDarkMode) Color.parseColor("#888888") else Color.parseColor("#AAAAAA")
    }

    fun setData(classes: List<ClassItem>, weekStart: LocalDate) {
        this.classes = classes
        this.weekStart = weekStart

        val prefs = context.getSharedPreferences("class_settings", Context.MODE_PRIVATE)
        val colorHex = prefs.getString("block_color", "#8D9DB6") ?: "#8D9DB6"

        blockColor = try {
            Color.parseColor(colorHex)
        } catch (e: Exception) {
            Color.parseColor("#8D9DB6")
        }

        examHighlightEnabled = prefs.getBoolean("exam_highlight", true)

        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast((timeColW + 5 * 60 * dp).toInt())
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> MeasureSpec.getSize(heightMeasureSpec)
            else -> (headerH + 60f * dp * totalHours).toInt()
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        updateThemeColors()

        val colW = (width - timeColW) / 5f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawTodayHighlight(canvas, colW)
        drawHeader(canvas, colW)
        drawTimeAxis(canvas)
        drawGrid(canvas, colW)
        drawClasses(canvas, colW)
    }

    private fun drawTodayHighlight(canvas: Canvas, colW: Float) {
        val col = todayColumnIndex()
        if (col < 0) return
        val x = timeColW + col * colW

        val highlightAlpha = if (isDarkMode) 40 else 25
        val highlightColor = Color.argb(
            highlightAlpha,
            Color.red(blockColor),
            Color.green(blockColor),
            Color.blue(blockColor)
        )
        val paint = Paint().apply { color = highlightColor }

        canvas.drawRect(x, headerH, x + colW, height.toFloat(), paint)
    }

    private fun drawHeader(canvas: Canvas, colW: Float) {
        canvas.drawRect(0f, 0f, width.toFloat(), headerH, headerBgPaint)
        val dayLabels = listOf("월", "화", "수", "목", "금")
        headerTextPaint.textSize = 11f * dp
        todayHeaderTextPaint.textSize = 11f * dp

        todayCirclePaint.color = blockColor

        for (i in 0..4) {
            val date = weekStart.plusDays((i + 1).toLong())
            val cx = timeColW + i * colW + colW / 2f
            val cy = headerH / 2f
            val isToday = date == today
            val label = "${dayLabels[i]} ${date.monthValue}/${date.dayOfMonth}"

            if (isToday) {
                // 1. 텍스트 너비 측정
                val textWidth = todayHeaderTextPaint.measureText(label)
                // 2. 텍스트 너비에 비례하여 배경 사각형 크기 결정 (양옆 패딩 약 6dp씩 추가)
                val bgW = textWidth + 12f * dp
                val bgH = 22f * dp // 높이는 고정 혹은 텍스트 크기에 비례

                val rect = RectF(
                    cx - bgW / 2f,
                    cy - bgH / 2f,
                    cx + bgW / 2f,
                    cy + bgH / 2f
                )

                // 3. 원 대신 둥근 사각형 그리기 (모서리를 반지름만큼 주면 캡슐 모양이 됨)
                canvas.drawRoundRect(rect, bgH / 2f, bgH / 2f, todayCirclePaint)
                canvas.drawText(label, cx, cy + 3.5f * dp, todayHeaderTextPaint)
            } else {
                canvas.drawText(label, cx, cy + 3.5f * dp, headerTextPaint)
            }
        }
    }

    private fun drawTimeAxis(canvas: Canvas) {
        timeTextPaint.textSize = 11f * dp
        for (h in startHour..endHour) {
            val y = headerH + (h - startHour) * hourH
            canvas.drawText("$h", timeColW - 6f * dp, y + 4f * dp, timeTextPaint)
        }
    }

    private fun drawGrid(canvas: Canvas, colW: Float) {
        for (i in 0..5) {
            val x = timeColW + i * colW
            canvas.drawLine(x, headerH, x, height.toFloat(), gridPaint)
        }
        for (h in startHour..endHour) {
            val y = headerH + (h - startHour) * hourH
            canvas.drawLine(timeColW, y, width.toFloat(), y, gridPaint)
            if (h < endHour) {
                val y2 = y + hourH / 2f
                canvas.drawLine(timeColW, y2, width.toFloat(), y2, halfGridPaint)
            }
        }
    }

    private data class BlockLayout(val x: Float, val w: Float, val y1: Float, val h: Float)

    /** 같은 요일에서 두 수업의 시간이 겹치는지 확인 */
    private fun timesOverlap(s1: LocalTime, e1: LocalTime, s2: LocalTime, e2: LocalTime) =
        s1 < e2 && s2 < e1

    /**
     * 겹치는 수업을 감지해 열 너비를 n등분 배치하는 레이아웃 맵 생성.
     * 겹치지 않으면 전체 너비를 그대로 사용.
     */
    private fun buildLayoutMap(colW: Float): Map<ClassItem, BlockLayout> {
        val result = mutableMapOf<ClassItem, BlockLayout>()
        val byDay = classes.groupBy { it.dayOfWeek }

        for ((day, dayClasses) in byDay) {
            val col = day - 1
            if (col < 0 || col > 4) continue
            val colX = timeColW + col * colW

            // 시작 시간 → 제목 순으로 정렬 후 인터벌 파티셔닝으로 lane 배정
            val sorted = dayClasses.sortedWith(compareBy({ it.startTime }, { it.title }))
            val laneEndTimes = mutableListOf<LocalTime>()
            val laneOf = mutableMapOf<ClassItem, Int>()

            for (cls in sorted) {
                var lane = laneEndTimes.indexOfFirst { !it.isAfter(cls.startTime) }
                if (lane == -1) {
                    lane = laneEndTimes.size
                    laneEndTimes.add(cls.endTime)
                } else {
                    laneEndTimes[lane] = cls.endTime
                }
                laneOf[cls] = lane
            }

            // 각 수업마다 자신과 겹치는 수업들의 최대 lane + 1 = 총 lane 수
            for (cls in dayClasses) {
                val totalLanes = (dayClasses
                    .filter { other -> timesOverlap(cls.startTime, cls.endTime, other.startTime, other.endTime) }
                    .mapNotNull { laneOf[it] }
                    .maxOrNull() ?: 0) + 1

                val lane = laneOf[cls] ?: 0
                val padding = 2f * dp
                val gap = if (totalLanes > 1) 1f * dp else 0f
                val slotW = (colW - 2 * padding - gap * (totalLanes - 1)) / totalLanes
                val x = colX + padding + lane * (slotW + gap)
                val y1 = timeToY(cls.startTime)
                val y2 = timeToY(cls.endTime)
                val h = (y2 - y1 - 2f * dp).coerceAtLeast(0f)
                result[cls] = BlockLayout(x, slotW.coerceAtLeast(4f * dp), y1, h)
            }
        }
        return result
    }

    private fun drawClasses(canvas: Canvas, colW: Float) {
        titlePaint.textSize = 11f * dp
        subTextPaint.textSize = 10f * dp
        blockPaint.color = blockColor

        val textPaint = TextPaint(titlePaint)
        val layoutMap = buildLayoutMap(colW)

        for (cls in classes) {
            val layout = layoutMap[cls] ?: continue
            if (layout.h < 4f * dp) continue

            // 1. 블록 배경 그리기
            blockPaint.color = if (examHighlightEnabled && isExamClass(cls.title))
                examColor(blockColor, isDarkMode) else blockColor
            canvas.drawRoundRect(RectF(layout.x, layout.y1, layout.x + layout.w, layout.y1 + layout.h), 6f * dp, 6f * dp, blockPaint)

            // 블록 영역 클리핑 (넘치는 텍스트는 말줄임 없이 그냥 잘림)
            canvas.save()
            canvas.clipRect(layout.x, layout.y1, layout.x + layout.w, layout.y1 + layout.h)

            val tx = layout.x + 6f * dp
            val maxW = (layout.w - 12f * dp).coerceAtLeast(1f)
            var currentY = layout.y1 + 6f * dp

            // 2. 과목 이름 (멀티라인)
            val titleLayout = StaticLayout.Builder.obtain(
                cls.title, 0, cls.title.length, textPaint, maxW.toInt()
            )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()

            canvas.save()
            canvas.translate(tx, currentY)
            titleLayout.draw(canvas)
            canvas.restore()

            currentY += titleLayout.height + 4f * dp

            // 3. 교수 이름
            if (layout.h > (currentY - layout.y1) + 10f * dp && cls.professor.isNotEmpty()) {
                canvas.drawText(cls.professor, tx, currentY + subTextPaint.textSize, subTextPaint)
                currentY += subTextPaint.textSize + 4f * dp
            }

            // 4. 시간
            if (layout.h > (currentY - layout.y1) + 10f * dp) {
                canvas.drawText("${cls.startTime}~${cls.endTime}", tx, currentY + subTextPaint.textSize, subTextPaint)
            }

            canvas.restore()
        }
    }

    private fun timeToY(t: LocalTime): Float {
        val mins = (t.hour - startHour) * 60 + t.minute
        return headerH + mins / 60f * hourH
    }

    private fun todayColumnIndex(): Int {
        val weekEnd = weekStart.plusDays(6)
        if (today < weekStart || today > weekEnd) return -1
        val dow = today.dayOfWeek.value
        return if (dow in 1..5) dow - 1 else -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onClassClick == null) return super.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true  // DOWN 소비해야 UP이 전달됨
            MotionEvent.ACTION_UP -> {
                val tx = event.x
                val ty = event.y
                val colW = (width - timeColW) / 5f
                val layoutMap = buildLayoutMap(colW)
                for (cls in classes) {
                    val layout = layoutMap[cls] ?: continue
                    if (layout.h < 4f * dp) continue
                    if (tx in layout.x..(layout.x + layout.w) && ty in layout.y1..(layout.y1 + layout.h)) {
                        onClassClick?.invoke(cls)
                        return true
                    }
                }
            }
        }
        return true
    }

}