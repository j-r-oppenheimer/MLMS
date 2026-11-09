package com.cnumed.mlms.pdfconvert

import org.w3c.dom.Element
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

internal sealed class Fill {
    object None : Fill()
    class Solid(val color: Rgba) : Fill()
    class Gradient(val stops: List<GradStop>, val angle: Double, val scaled: Boolean, val path: String?, val focus: DoubleArray) : Fill()
    /** srcRect/fillRect: l,t,r,b 비율 */
    class Picture(val part: String, val alpha: Double, val srcRect: DoubleArray, val tile: Element?, val stretchRect: DoubleArray?) : Fill()
    class Pattern(val fg: Rgba, val bg: Rgba, val prst: String) : Fill()
    object Group : Fill()
}

internal class ArrowEnd(val type: String, val w: Double, val len: Double)

internal class LineSpec(
    var width: Double = 0.75,
    var fill: Fill? = null,
    var dash: String? = null,
    var cap: Int = 0,
    var join: Int = 1,
    var head: ArrowEnd? = null,
    var tail: ArrowEnd? = null,
    var compound: String? = null,
) {
    fun copy() = LineSpec(width, fill, dash, cap, join, head, tail, compound)
    fun color(): Rgba? = when (val f = fill) {
        is Fill.Solid -> f.color
        is Fill.Gradient -> f.stops.firstOrNull()?.color
        is Fill.Pattern -> f.fg
        else -> null
    }

    fun dashArray(): FloatArray? {
        val w = max(width, 0.5).toFloat()
        val pat: FloatArray = when (dash) {
            null, "solid" -> return null
            "sysDot" -> floatArrayOf(1f, 1f)
            "sysDash" -> floatArrayOf(3f, 1f)
            "dash" -> floatArrayOf(4f, 3f)
            "dashDot" -> floatArrayOf(4f, 3f, 1f, 3f)
            "lgDash" -> floatArrayOf(8f, 3f)
            "lgDashDot" -> floatArrayOf(8f, 3f, 1f, 3f)
            "lgDashDotDot" -> floatArrayOf(8f, 3f, 1f, 3f, 1f, 3f)
            "sysDashDot" -> floatArrayOf(3f, 1f, 1f, 1f)
            "sysDashDotDot" -> floatArrayOf(3f, 1f, 1f, 1f, 1f, 1f)
            "dot" -> floatArrayOf(1f, 3f)
            else -> floatArrayOf(4f, 3f)
        }
        return FloatArray(pat.size) { pat[it] * w }
    }
}

/** 채우기/선/배경 해석 (테마 스타일 참조 포함) */
internal class StyleResolver(val cc: ColorContext, val theme: Theme) {

    /** container(spPr, bgPr, tcPr …) 안의 채우기 요소. 없으면 null(상속) */
    fun fillIn(container: Element?, part: String, phClr: Rgba? = null): Fill? {
        container ?: return null
        for (c in container.elements()) {
            parseFill(c, part, phClr)?.let { return it }
        }
        return null
    }

    fun parseFill(c: Element, part: String, phClr: Rgba?): Fill? = when (c.ln) {
        "noFill" -> Fill.None
        "solidFill" -> cc.colorIn(c, phClr)?.let { Fill.Solid(it) } ?: Fill.None
        "gradFill" -> {
            val stops = c.child("gsLst")?.children("gs")?.mapNotNull { gs ->
                val col = cc.colorIn(gs, phClr) ?: return@mapNotNull null
                GradStop((gs.long("pos") ?: 0) / 100000.0, col)
            } ?: emptyList()
            val lin = c.child("lin")
            val path = c.child("path")
            val ftr = path?.child("fillToRect")
            val focus = doubleArrayOf(ftr?.pct("l") ?: 0.5, ftr?.pct("t") ?: 0.5, ftr?.pct("r") ?: 0.5, ftr?.pct("b") ?: 0.5)
            if (stops.isEmpty()) Fill.None
            else Fill.Gradient(stops, (lin?.long("ang") ?: 0) / 60000.0, lin?.bool("scaled") ?: false, path?.attr("path"), focus)
        }
        "blipFill" -> {
            val blip = c.child("blip")
            val rId = blip?.rAttr("embed")
            if (rId == null) Fill.None else {
                val alpha = blip.child("alphaModFix")?.pct("amt") ?: 1.0
                val sr = c.child("srcRect")
                val src = doubleArrayOf(sr?.pct("l") ?: 0.0, sr?.pct("t") ?: 0.0, sr?.pct("r") ?: 0.0, sr?.pct("b") ?: 0.0)
                val fr = c.child("stretch")?.child("fillRect")
                val stretch = fr?.let { doubleArrayOf(it.pct("l") ?: 0.0, it.pct("t") ?: 0.0, it.pct("r") ?: 0.0, it.pct("b") ?: 0.0) }
                Fill.Picture("$part#$rId", alpha, src, c.child("tile"), stretch)
            }
        }
        "pattFill" -> {
            val fg = cc.colorIn(c.child("fgClr"), phClr) ?: Rgba.BLACK
            val bg = cc.colorIn(c.child("bgClr"), phClr) ?: Rgba.WHITE
            Fill.Pattern(fg, bg, c.attr("prst") ?: "pct50")
        }
        "grpFill" -> Fill.Group
        else -> null
    }

    /** fillRef idx → 테마 채우기 */
    fun fillRef(ref: Element?, part: String): Fill? {
        ref ?: return null
        val idx = ref.int("idx") ?: return null
        val phClr = cc.colorIn(ref)
        val el = when {
            idx == 0 -> return Fill.None
            idx in 1..999 -> theme.fillStyles.getOrNull(idx - 1)
            idx >= 1001 -> theme.bgFillStyles.getOrNull(idx - 1001)
            else -> null
        } ?: return phClr?.let { Fill.Solid(it) }
        return parseFill(el, "theme", phClr)
    }

    fun lineRef(ref: Element?): LineSpec? {
        ref ?: return null
        val idx = ref.int("idx") ?: return null
        if (idx == 0) return null
        val phClr = cc.colorIn(ref)
        val el = theme.lineStyles.getOrNull(idx - 1) ?: return phClr?.let { LineSpec(fill = Fill.Solid(it)) }
        return mergeLine(LineSpec(), el, "theme", phClr)
    }

    /** a:ln 요소를 base 위에 덮어쓴다 */
    fun mergeLine(base: LineSpec, ln: Element?, part: String, phClr: Rgba? = null): LineSpec {
        ln ?: return base
        val s = base.copy()
        ln.long("w")?.let { s.width = emu(it) }
        ln.attr("cap")?.let { s.cap = when (it) { "rnd" -> 1; "sq" -> 2; else -> 0 } }
        ln.attr("cmpd")?.let { s.compound = it }
        for (c in ln.elements()) {
            when (c.ln) {
                "noFill", "solidFill", "gradFill", "pattFill" -> s.fill = parseFill(c, part, phClr)
                "prstDash" -> s.dash = c.attr("val")
                "custDash" -> s.dash = "dash"
                "round" -> s.join = 1
                "bevel" -> s.join = 2
                "miter" -> s.join = 0
                "headEnd" -> s.head = arrow(c)
                "tailEnd" -> s.tail = arrow(c)
            }
        }
        return s
    }

    private fun arrow(e: Element): ArrowEnd? {
        val t = e.attr("type") ?: return null
        if (t == "none") return null
        fun sz(v: String?) = when (v) { "sm" -> 2.0; "lg" -> 5.0; else -> 3.0 }
        return ArrowEnd(t, sz(e.attr("w")), sz(e.attr("len")))
    }
}

/** 채우기를 현재 경로에 칠한다. 경로는 path()로 이미 그려져 있어야 한다(좌표는 로컬 w×h 상자). */
internal class FillPainter(private val images: ImageLoader, private val pkg: OpcPackage, private val ocr: OcrOverlay? = null) {

    fun fillCurrentPath(p: Painter, fill: Fill, w: Double, h: Double, evenOdd: Boolean, modifier: String = "norm") {
        when (fill) {
            is Fill.Solid -> { p.setFill(modify(fill.color, modifier)); p.fill(evenOdd) }
            is Fill.Pattern -> {
                // 패턴은 전경/배경의 밀도 비율로 근사한다
                val d = patternDensity(fill.prst)
                val c = Rgba(fill.fg.r * d + fill.bg.r * (1 - d), fill.fg.g * d + fill.bg.g * (1 - d), fill.fg.b * d + fill.bg.b * (1 - d),
                    fill.fg.a * d + fill.bg.a * (1 - d))
                p.setFill(modify(c, modifier)); p.fill(evenOdd)
            }
            is Fill.Gradient -> {
                val stops = fill.stops.map { GradStop(it.pos, modify(it.color, modifier)) }
                if (fill.path == null) {
                    p.gradientFill(stops, false, Painter.linearCoords(w, h, fill.angle, fill.scaled), evenOdd)
                } else {
                    val f = fill.focus
                    val cx = w * (f[0] + (1 - f[0] - f[2]) / 2); val cy = h * (f[1] + (1 - f[1] - f[3]) / 2)
                    val r = listOf(0.0 to 0.0, w to 0.0, 0.0 to h, w to h).maxOf { (x, y) -> hypot(x - cx, y - cy) }
                    // 경로형 그라디언트: pos 0 = 중심
                    p.gradientFill(stops, true, doubleArrayOf(cx, cy, 0.0, cx, cy, r), evenOdd)
                }
            }
            is Fill.Picture -> {
                val placed = imageFor(fill.part) ?: run { return }
                p.clip(evenOdd)
                drawPicture(p, placed, fill, w, h)
            }
            else -> {}
        }
    }

    fun imageFor(partRef: String): PlacedImage? {
        val (part, rId) = partRef.split('#').let { it[0] to it.getOrNull(1) }
        val target = pkg.relTarget(part, rId) ?: return null
        return images.load(target) { pkg.bytes(target) }
    }

    fun drawPicture(p: Painter, placed: PlacedImage, fill: Fill.Picture, w: Double, h: Double) {
        val tile = fill.tile
        if (tile != null) {
            val img = placed.image
            val sx = tile.pct("sx") ?: 1.0; val sy = tile.pct("sy") ?: 1.0
            val tw = img.width * 0.75 * sx; val th = img.height * 0.75 * sy
            if (tw < 0.5 || th < 0.5) return
            val ox = emu(tile.long("tx")); val oy = emu(tile.long("ty"))
            var yy = oy % th - th
            var count = 0
            while (yy < h && count < 4000) {
                var xx = ox % tw - tw
                while (xx < w && count < 4000) { p.image(img, xx, yy, tw, th, fill.alpha); xx += tw; count++ }
                yy += th
            }
            return
        }
        // fillRect(늘이기 영역) → srcRect(자르기) 순서로 대상 사각형 계산
        val fr = fill.stretchRect ?: doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val bx = w * fr[0]; val by = h * fr[1]
        val bw = w * (1 - fr[0] - fr[2]); val bh = h * (1 - fr[1] - fr[3])
        val s = fill.srcRect
        val visW = 1 - s[0] - s[2]; val visH = 1 - s[1] - s[3]
        if (visW <= 0 || visH <= 0) return
        val iw = bw / visW; val ih = bh / visH
        val ix = bx - s[0] * iw; val iy = by - s[1] * ih
        val pl = placed.place
        p.image(placed.image, ix + pl[0] * iw, iy + pl[1] * ih, pl[2] * iw, pl[3] * ih, fill.alpha)
        ocr?.draw(p, placed.key, ix + pl[0] * iw, iy + pl[1] * ih, pl[2] * iw, pl[3] * ih, w, h)
    }

    companion object {
        fun modify(c: Rgba, m: String): Rgba = when (m) {
            "darken" -> Rgba(c.r * 0.6, c.g * 0.6, c.b * 0.6, c.a)
            "darkenLess" -> Rgba(c.r * 0.8, c.g * 0.8, c.b * 0.8, c.a)
            "lighten" -> Rgba(c.r + (1 - c.r) * 0.4, c.g + (1 - c.g) * 0.4, c.b + (1 - c.b) * 0.4, c.a)
            "lightenLess" -> Rgba(c.r + (1 - c.r) * 0.2, c.g + (1 - c.g) * 0.2, c.b + (1 - c.b) * 0.2, c.a)
            else -> c
        }

        fun patternDensity(prst: String): Double {
            Regex("pct(\\d+)").find(prst)?.let { return it.groupValues[1].toDouble() / 100.0 }
            return when {
                prst.startsWith("lt") -> 0.25
                prst.startsWith("dk") -> 0.6
                prst.startsWith("wd") -> 0.5
                prst.startsWith("dash") || prst.startsWith("sm") -> 0.3
                else -> 0.4
            }
        }

        /** 선 끝 방향 계산용: 경로의 시작/끝 점과 접선 */
        fun endpoints(cmds: List<PathCmd>): Pair<DoubleArray, DoubleArray>? {
            if (cmds.size < 2) return null
            val first = cmds.first() as? PathCmd.Move ?: return null
            val second = cmds[1]
            val (sx, sy) = when (second) {
                is PathCmd.Line -> second.x to second.y
                is PathCmd.Cubic -> if (second.x1 == first.x && second.y1 == first.y) second.x2 to second.y2 else second.x1 to second.y1
                else -> return null
            }
            val lastCmd = cmds.lastOrNull { it !is PathCmd.Close } ?: return null
            var prevX = first.x; var prevY = first.y
            for (c in cmds) { if (c === lastCmd) break; when (c) { is PathCmd.Move -> { prevX = c.x; prevY = c.y }; is PathCmd.Line -> { prevX = c.x; prevY = c.y }; is PathCmd.Cubic -> { prevX = c.x; prevY = c.y }; else -> {} } }
            val (ex, ey, tx, ty) = when (lastCmd) {
                is PathCmd.Line -> listOf(lastCmd.x, lastCmd.y, prevX, prevY)
                is PathCmd.Cubic -> if (lastCmd.x2 == lastCmd.x && lastCmd.y2 == lastCmd.y) listOf(lastCmd.x, lastCmd.y, lastCmd.x1, lastCmd.y1) else listOf(lastCmd.x, lastCmd.y, lastCmd.x2, lastCmd.y2)
                else -> return null
            }
            val headAng = atan2(first.y - sy, first.x - sx)
            val tailAng = atan2(ey - ty, ex - tx)
            return doubleArrayOf(first.x, first.y, headAng) to doubleArrayOf(ex, ey, tailAng)
        }
    }
}
