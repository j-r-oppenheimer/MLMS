package com.cnumed.mlms.ui.settings.color

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 가로 무지개 색조 슬라이더 (0°..360°). 드래그하면 hue를 콜백으로 알린다.
 */
class HueSliderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var hue = 0f
    var onChanged: ((hue: Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.parseColor("#55000000")
    }
    private var shader: Shader? = null

    fun setHue(h: Float) { hue = h; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0) return
        val colors = IntArray(361) { Color.HSVToColor(floatArrayOf(it.toFloat(), 1f, 1f)) }
        shader = LinearGradient(0f, 0f, w.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.shader = shader
        val r = h / 2f
        canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
        paint.shader = null

        val cx = (hue / 360f * w).coerceIn(r, w - r)
        val cy = h / 2f
        canvas.drawCircle(cx, cy, r * 0.72f, selFill)
        canvas.drawCircle(cx, cy, r * 0.72f, selStroke)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        if (w <= 0f) return false
        hue = (event.x / w * 360f).coerceIn(0f, 360f)
        onChanged?.invoke(hue)
        invalidate()
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }
}
