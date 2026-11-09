package com.cnumed.mlms.pdfconvert

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.shading.PDShading
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** 2D 아핀 변환 (x' = a x + c y + e, y' = b x + d y + f) */
internal data class Affine(val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0, val d: Double = 1.0, val e: Double = 0.0, val f: Double = 0.0) {
    operator fun times(o: Affine) = Affine(
        a * o.a + c * o.b, b * o.a + d * o.b,
        a * o.c + c * o.d, b * o.c + d * o.d,
        a * o.e + c * o.f + e, b * o.e + d * o.f + f
    )
    fun apply(x: Double, y: Double) = doubleArrayOf(a * x + c * y + e, b * x + d * y + f)
    val isIdentity get() = this == IDENTITY

    companion object {
        val IDENTITY = Affine()
        fun translate(x: Double, y: Double) = Affine(e = x, f = y)
        fun scale(sx: Double, sy: Double) = Affine(a = sx, d = sy)
        /** 시계방향(y 아래 좌표계) 회전, 도 단위 */
        fun rotate(deg: Double): Affine { val r = Math.toRadians(deg); return Affine(cos(r), sin(r), -sin(r), cos(r)) }
    }
}

/** 그라디언트 정지점 */
internal class GradStop(val pos: Double, val color: Rgba)

/**
 * PDPageContentStream 래퍼. 슬라이드와 같은 좌표계(pt, 왼쪽 위 원점, y 아래)로 그린다.
 */
internal class Painter(private val cs: PDPageContentStream, pageHeight: Double) {
    private val gsCache = HashMap<String, PDExtendedGraphicsState>()
    private val alphaStack = ArrayList<DoubleArray>()
    private var fillAlpha = 1.0
    private var strokeAlpha = 1.0

    init {
        cs.transform(Matrix(1f, 0f, 0f, -1f, 0f, pageHeight.toFloat()))
    }

    fun save() { cs.saveGraphicsState(); alphaStack.add(doubleArrayOf(fillAlpha, strokeAlpha)) }
    fun restore() {
        cs.restoreGraphicsState()
        alphaStack.removeLastOrNull()?.let { fillAlpha = it[0]; strokeAlpha = it[1] }
    }

    inline fun <T> saved(block: () -> T): T { save(); try { return block() } finally { restore() } }

    fun transform(m: Affine) {
        if (!m.isIdentity) cs.transform(Matrix(m.a.toFloat(), m.b.toFloat(), m.c.toFloat(), m.d.toFloat(), m.e.toFloat(), m.f.toFloat()))
    }

    private fun alpha(fill: Double?, stroke: Double?) {
        val fa = fill ?: fillAlpha; val sa = stroke ?: strokeAlpha
        if (fa == fillAlpha && sa == strokeAlpha) return
        val key = "%.3f/%.3f".format(fa, sa)
        val gs = gsCache.getOrPut(key) {
            PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = fa.toFloat(); strokingAlphaConstant = sa.toFloat() }
        }
        cs.setGraphicsStateParameters(gs)
        fillAlpha = fa; strokeAlpha = sa
    }

    fun setFill(c: Rgba) {
        cs.setNonStrokingColor(c.r.toFloat().coerceIn(0f, 1f), c.g.toFloat().coerceIn(0f, 1f), c.b.toFloat().coerceIn(0f, 1f))
        alpha(c.a, null)
    }

    fun setStroke(c: Rgba) {
        cs.setStrokingColor(c.r.toFloat().coerceIn(0f, 1f), c.g.toFloat().coerceIn(0f, 1f), c.b.toFloat().coerceIn(0f, 1f))
        alpha(null, c.a)
    }

    fun path(cmds: List<PathCmd>, m: Affine = Affine.IDENTITY) {
        for (c in cmds) when (c) {
            is PathCmd.Move -> m.apply(c.x, c.y).let { cs.moveTo(it[0].toFloat(), it[1].toFloat()) }
            is PathCmd.Line -> m.apply(c.x, c.y).let { cs.lineTo(it[0].toFloat(), it[1].toFloat()) }
            is PathCmd.Cubic -> {
                val p1 = m.apply(c.x1, c.y1); val p2 = m.apply(c.x2, c.y2); val p = m.apply(c.x, c.y)
                cs.curveTo(p1[0].toFloat(), p1[1].toFloat(), p2[0].toFloat(), p2[1].toFloat(), p[0].toFloat(), p[1].toFloat())
            }
            PathCmd.Close -> cs.closePath()
        }
    }

    fun rect(x: Double, y: Double, w: Double, h: Double) = cs.addRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())

    fun fill(evenOdd: Boolean = false) = if (evenOdd) cs.fillEvenOdd() else cs.fill()
    fun stroke() = cs.stroke()
    fun clip(evenOdd: Boolean = false) = if (evenOdd) cs.clipEvenOdd() else cs.clip()

    fun lineStyle(width: Double, cap: Int, join: Int, dash: FloatArray?) {
        cs.setLineWidth(width.toFloat())
        cs.setLineCapStyle(cap)
        cs.setLineJoinStyle(join)
        if (join == 0) cs.setMiterLimit(10f)
        cs.setLineDashPattern(dash ?: FloatArray(0), 0f)
    }

    /** 현재 경로로 클립한 뒤 축/방사형 그라디언트를 칠한다. coords는 현재 사용자 좌표계 기준 */
    fun gradientFill(stops: List<GradStop>, radial: Boolean, coords: DoubleArray, evenOdd: Boolean = false) {
        if (stops.isEmpty()) return
        val sorted = stops.sortedBy { it.pos }
        val avgAlpha = sorted.map { it.color.a }.average()
        clip(evenOdd)
        alpha(avgAlpha, null)
        val fn = stitching(sorted)
        val dict = COSDictionary().apply {
            setInt(COSName.SHADING_TYPE, if (radial) 3 else 2)
            setItem(COSName.COLORSPACE, COSName.DEVICERGB)
            setItem(COSName.COORDS, COSArray().apply { coords.forEach { add(COSFloat(it.toFloat())) } })
            setItem(COSName.FUNCTION, fn)
            setItem(COSName.EXTEND, COSArray().apply { add(COSBoolean.TRUE); add(COSBoolean.TRUE) })
        }
        cs.shadingFill(PDShading.create(dict))
    }

    private fun rgbArr(c: Rgba) = COSArray().apply {
        add(COSFloat(c.r.toFloat())); add(COSFloat(c.g.toFloat())); add(COSFloat(c.b.toFloat()))
    }

    private fun stitching(stops: List<GradStop>): COSDictionary {
        val list = ArrayList(stops)
        if (list.first().pos > 0) list.add(0, GradStop(0.0, list.first().color))
        if (list.last().pos < 1) list.add(GradStop(1.0, list.last().color))
        val funcs = COSArray(); val bounds = COSArray(); val encode = COSArray()
        for (i in 0 until list.size - 1) {
            funcs.add(COSDictionary().apply {
                setInt(COSName.FUNCTION_TYPE, 2)
                setItem(COSName.DOMAIN, COSArray().apply { add(COSInteger.ZERO); add(COSInteger.ONE) })
                setItem(COSName.C0, rgbArr(list[i].color)); setItem(COSName.C1, rgbArr(list[i + 1].color))
                setInt(COSName.N, 1)
            })
            if (i > 0) bounds.add(COSFloat(list[i].pos.coerceIn(0.0, 1.0).toFloat()))
            encode.add(COSInteger.ZERO); encode.add(COSInteger.ONE)
        }
        return COSDictionary().apply {
            setInt(COSName.FUNCTION_TYPE, 3)
            setItem(COSName.DOMAIN, COSArray().apply { add(COSInteger.ZERO); add(COSInteger.ONE) })
            setItem(COSName.FUNCTIONS, funcs); setItem(COSName.BOUNDS, bounds); setItem(COSName.ENCODE, encode)
        }
    }

    /** 이미지 전체를 (x,y,w,h) 사각형에 그린다 */
    fun image(img: PDImageXObject, x: Double, y: Double, w: Double, h: Double, alpha: Double = 1.0) {
        alpha(alpha, null)
        cs.drawImage(img, Matrix(w.toFloat(), 0f, 0f, -h.toFloat(), x.toFloat(), (y + h).toFloat()))
    }

    fun text(face: LoadedFace, size: Double, x: Double, y: Double, s: String, color: Rgba,
             synthBold: Boolean, synthItalic: Boolean, charSpacing: Double) {
        val font = face.pdFont() ?: return
        setFill(color)
        cs.beginText()
        cs.setFont(font, size.toFloat())
        if (charSpacing != 0.0) cs.setCharacterSpacing(charSpacing.toFloat())
        if (synthBold) {
            setStroke(color)
            cs.setLineWidth((size * 0.035).toFloat())
            cs.setRenderingMode(RenderingMode.FILL_STROKE)
        }
        val shear = if (synthItalic) 0.2f else 0f
        cs.setTextMatrix(Matrix(1f, 0f, shear, -1f, x.toFloat(), y.toFloat()))
        cs.showText(s)
        if (synthBold) cs.setRenderingMode(RenderingMode.FILL)
        if (charSpacing != 0.0) cs.setCharacterSpacing(0f)
        cs.endText()
    }

    /**
     * 글자별 위치를 지정해 출력(TJ). parts는 String(글자들)과 Double(다음 글자 앞 간격 보정, pt)의 나열.
     * 커닝·양쪽 정렬 간격을 정확히 반영한다.
     */
    fun textPositioned(face: LoadedFace, size: Double, x: Double, y: Double, parts: List<Any>, color: Rgba,
                       synthBold: Boolean, synthItalic: Boolean, charSpacing: Double) {
        val font = face.pdFont() ?: return
        setFill(color)
        cs.beginText()
        cs.setFont(font, size.toFloat())
        if (charSpacing != 0.0) cs.setCharacterSpacing(charSpacing.toFloat())
        if (synthBold) {
            setStroke(color)
            cs.setLineWidth((size * 0.035).toFloat())
            cs.setRenderingMode(RenderingMode.FILL_STROKE)
        }
        cs.setTextMatrix(Matrix(1f, 0f, if (synthItalic) 0.2f else 0f, -1f, x.toFloat(), y.toFloat()))
        val arr = parts.map { if (it is Double) (-it / size * 1000.0).toFloat() else it }.toTypedArray()
        cs.showTextWithPositioning(arr)
        if (synthBold) cs.setRenderingMode(RenderingMode.FILL)
        if (charSpacing != 0.0) cs.setCharacterSpacing(0f)
        cs.endText()
    }

    /** 보이지 않는 글자(OCR 텍스트 층): 검색·복사만 되고 화면에는 그려지지 않는다 */
    fun invisibleText(face: LoadedFace, size: Double, x: Double, y: Double, s: String, hScalePct: Double) {
        val font = face.pdFont() ?: return
        cs.beginText()
        cs.setFont(font, size.toFloat())
        cs.setRenderingMode(RenderingMode.NEITHER)
        cs.setHorizontalScaling(hScalePct.toFloat())
        cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, x.toFloat(), y.toFloat()))
        cs.showText(s)
        cs.setHorizontalScaling(100f)
        cs.setRenderingMode(RenderingMode.FILL)
        cs.endText()
    }

    companion object {
        /** 사각형(로컬 좌표) 안에서 각도 θ인 선형 그라디언트 축의 시작/끝 */
        fun linearCoords(w: Double, h: Double, angleDeg: Double, scaled: Boolean): DoubleArray {
            val r = Math.toRadians(angleDeg)
            var dx = cos(r); var dy = sin(r)
            if (scaled && w > 0 && h > 0) { dx *= w; dy *= h; val l = hypot(dx, dy); dx /= l; dy /= l }
            val cx = w / 2; val cy = h / 2
            // 네 모서리를 축에 투영해 0..1 범위를 정한다
            val proj = listOf(0.0 to 0.0, w to 0.0, 0.0 to h, w to h).map { (x, y) -> (x - cx) * dx + (y - cy) * dy }
            val mn = proj.min(); val mx = max(proj.max(), mn + 1e-6)
            return doubleArrayOf(cx + dx * mn, cy + dy * mn, cx + dx * mx, cy + dy * mx)
        }
    }
}
