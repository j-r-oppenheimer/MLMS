package com.cnumed.mlms.ui.settings.color

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 채도(가로) × 명도(세로) 사각형. 현재 색조(hue)에 맞춰 그라디언트를 그리고,
 * 드래그하면 채도·명도를 콜백으로 알린다. HTML 컬러 피커의 사각형 영역에 해당.
 */
class SaturationValueView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var hue = 0f
    private var sat = 1f
    private var value = 1f

    /** (채도, 명도) 변경 콜백. 0f..1f */
    var onChanged: ((sat: Float, value: Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.WHITE
    }
    private val selOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7f; color = Color.parseColor("#55000000")
    }
    private var shader: Shader? = null

    init {
        // ComposeShader가 하드웨어 가속에서 API 27 이하 제약이 있어 소프트웨어 레이어로 안전하게 그린다.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setHue(h: Float) { hue = h; rebuild(); invalidate() }
    fun setSatVal(s: Float, v: Float) { sat = s; value = v; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    private fun rebuild() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        val satShader = LinearGradient(0f, 0f, w, 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP)
        val valShader = LinearGradient(0f, 0f, 0f, h, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
        shader = ComposeShader(satShader, valShader, PorterDuff.Mode.MULTIPLY)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.shader = shader
        val radius = 12f
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, paint)
        paint.shader = null

        val cx = (sat * w).coerceIn(0f, w)
        val cy = ((1f - value) * h).coerceIn(0f, h)
        canvas.drawCircle(cx, cy, 15f, selOuter)
        canvas.drawCircle(cx, cy, 15f, selInner)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return false
        sat = (event.x / w).coerceIn(0f, 1f)
        value = 1f - (event.y / h).coerceIn(0f, 1f)
        onChanged?.invoke(sat, value)
        invalidate()
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }
}
