package com.cnumed.mlms.pdfconvert

import org.w3c.dom.Element
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** 도형 로컬 좌표(pt, 왼쪽 위 원점, y 아래로)의 경로 명령 */
internal sealed class PathCmd {
    data class Move(val x: Double, val y: Double) : PathCmd()
    data class Line(val x: Double, val y: Double) : PathCmd()
    data class Cubic(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val x: Double, val y: Double) : PathCmd()
    object Close : PathCmd()
}

/** fill: "norm" | "none" | "lighten" | "lightenLess" | "darken" | "darkenLess" */
internal class GeomPath(val cmds: List<PathCmd>, val fill: String, val stroke: Boolean)

internal class Geometry(val paths: List<GeomPath>, val textRect: DoubleArray /* l,t,r,b */)

/**
 * DrawingML 도형 기하 엔진.
 * presetShapeDefinitions.xml(ECMA-376 부록, Apache POI 배포본)의 수식(guide)을 그대로 계산하므로
 * 187개 프리셋 도형과 사용자 정의 도형(custGeom)을 모두 같은 코드로 그린다.
 * 수식·arcTo 각도 변환은 Apache POI의 sl.draw.geom 구현을 참고했다.
 */
internal object GeometryEngine {

    @Volatile private var presets: Map<String, Element>? = null

    fun loadPresets(bytes: ByteArray) {
        if (presets != null) return
        val root = Xml.parse(bytes)
        presets = root.elements().associateBy { it.ln }
    }

    fun preset(name: String): Element? = presets?.get(name)

    /**
     * @param spPr 도형의 spPr(prstGeom/custGeom 포함). null이면 사각형.
     */
    fun build(spPr: Element?, w: Double, h: Double): Geometry {
        val prst = spPr?.child("prstGeom")
        val cust = spPr?.child("custGeom")
        return when {
            cust != null -> evaluate(cust, null, w, h)
            prst != null -> {
                val name = prst.attr("prst") ?: "rect"
                val def = preset(name)
                if (def == null || name == "rect") rect(w, h) else evaluate(def, prst.child("avLst"), w, h)
            }
            else -> rect(w, h)
        }
    }

    fun rect(w: Double, h: Double) = Geometry(
        listOf(GeomPath(listOf(PathCmd.Move(0.0, 0.0), PathCmd.Line(w, 0.0), PathCmd.Line(w, h), PathCmd.Line(0.0, h), PathCmd.Close), "norm", true)),
        doubleArrayOf(0.0, 0.0, w, h)
    )

    private fun evaluate(def: Element, overrideAv: Element?, w: Double, h: Double): Geometry {
        val vars = HashMap<String, Double>(64)
        builtins(vars, w, h)
        def.child("avLst")?.children("gd")?.forEach { evalGuide(it, vars) }
        overrideAv?.children("gd")?.forEach { evalGuide(it, vars) }
        def.child("gdLst")?.children("gd")?.forEach { evalGuide(it, vars) }

        val paths = ArrayList<GeomPath>()
        def.child("pathLst")?.children("path")?.forEach { p ->
            val pw = p.attr("w")?.toDoubleOrNull()?.takeIf { it > 0 }
            val ph = p.attr("h")?.toDoubleOrNull()?.takeIf { it > 0 }
            val sx = if (pw != null) w / pw else 1.0
            val sy = if (ph != null) h / ph else 1.0
            val cmds = ArrayList<PathCmd>()
            var cx = 0.0; var cy = 0.0; var startX = 0.0; var startY = 0.0
            fun v(s: String?): Double = value(s, vars)
            for (c in p.elements()) {
                when (c.ln) {
                    "moveTo" -> c.child("pt")?.let {
                        cx = v(it.attr("x")) * sx; cy = v(it.attr("y")) * sy
                        startX = cx; startY = cy
                        cmds.add(PathCmd.Move(cx, cy))
                    }
                    "lnTo" -> c.child("pt")?.let {
                        cx = v(it.attr("x")) * sx; cy = v(it.attr("y")) * sy
                        cmds.add(PathCmd.Line(cx, cy))
                    }
                    "cubicBezTo" -> {
                        val pts = c.children("pt").map { doubleArrayOf(v(it.attr("x")) * sx, v(it.attr("y")) * sy) }
                        if (pts.size == 3) {
                            cmds.add(PathCmd.Cubic(pts[0][0], pts[0][1], pts[1][0], pts[1][1], pts[2][0], pts[2][1]))
                            cx = pts[2][0]; cy = pts[2][1]
                        }
                    }
                    "quadBezTo" -> {
                        val pts = c.children("pt").map { doubleArrayOf(v(it.attr("x")) * sx, v(it.attr("y")) * sy) }
                        if (pts.size == 2) {
                            val (qx, qy) = pts[0][0] to pts[0][1]
                            val (ex, ey) = pts[1][0] to pts[1][1]
                            cmds.add(PathCmd.Cubic(cx + 2.0 / 3 * (qx - cx), cy + 2.0 / 3 * (qy - cy),
                                ex + 2.0 / 3 * (qx - ex), ey + 2.0 / 3 * (qy - ey), ex, ey))
                            cx = ex; cy = ey
                        }
                    }
                    "arcTo" -> {
                        val wr = v(c.attr("wR")) * sx
                        val hr = v(c.attr("hR")) * sy
                        val st = v(c.attr("stAng")) / 60000.0
                        val sw = v(c.attr("swAng")) / 60000.0
                        val end = arc(cmds, cx, cy, wr, hr, st, sw)
                        cx = end[0]; cy = end[1]
                    }
                    "close" -> { cmds.add(PathCmd.Close); cx = startX; cy = startY }
                }
            }
            if (cmds.isNotEmpty()) {
                paths.add(GeomPath(cmds, p.attr("fill") ?: "norm", p.bool("stroke") ?: true))
            }
        }
        val r = def.child("rect")
        val textRect = if (r != null) doubleArrayOf(
            value(r.attr("l"), vars), value(r.attr("t"), vars), value(r.attr("r"), vars), value(r.attr("b"), vars)
        ) else doubleArrayOf(0.0, 0.0, w, h)
        return Geometry(paths, textRect)
    }

    /** OOXML 시각 각도 → 타원의 매개변수 각도 (라디안) */
    private fun paramAngle(deg: Double, wr: Double, hr: Double): Double {
        val t = Math.toRadians(deg)
        if (wr == 0.0 || hr == 0.0) return t
        return atan2(wr * sin(t), hr * cos(t))
    }

    private fun arc(cmds: MutableList<PathCmd>, x0: Double, y0: Double, wr: Double, hr: Double, stDeg: Double, swDeg: Double): DoubleArray {
        if (swDeg == 0.0 || (wr == 0.0 && hr == 0.0)) return doubleArrayOf(x0, y0)
        val a0 = paramAngle(stDeg, wr, hr)
        val cxe = x0 - wr * cos(a0)
        val cye = y0 - hr * sin(a0)
        // 끝 각도: 시각 각도를 변환 후 스윕 방향/회전수에 맞게 보정
        val sign = if (swDeg > 0) 1.0 else -1.0
        val full = kotlin.math.floor(abs(swDeg) / 360.0)
        val rem = swDeg - sign * full * 360.0
        var d = 0.0
        if (rem != 0.0) {
            d = paramAngle(stDeg + rem, wr, hr) - a0
            if (rem > 0) { while (d <= 0) d += 2 * PI; while (d > 2 * PI) d -= 2 * PI }
            else { while (d >= 0) d -= 2 * PI; while (d < -2 * PI) d += 2 * PI }
        }
        val a1 = a0 + d + sign * full * 2 * PI
        val segs = max(1, ceil(abs(a1 - a0) / (PI / 2)).toInt())
        val step = (a1 - a0) / segs
        var a = a0
        repeat(segs) {
            val b = a + step
            val k = 4.0 / 3.0 * tan((b - a) / 4)
            val p0x = cxe + wr * cos(a); val p0y = cye + hr * sin(a)
            val p3x = cxe + wr * cos(b); val p3y = cye + hr * sin(b)
            cmds.add(PathCmd.Cubic(
                p0x - k * wr * sin(a), p0y + k * hr * cos(a),
                p3x + k * wr * sin(b), p3y - k * hr * cos(b),
                p3x, p3y
            ))
            a = b
        }
        return doubleArrayOf(cxe + wr * cos(a1), cye + hr * sin(a1))
    }

    private fun builtins(m: HashMap<String, Double>, w: Double, h: Double) {
        val ss = min(w, h)
        m["w"] = w; m["h"] = h; m["l"] = 0.0; m["t"] = 0.0; m["r"] = w; m["b"] = h
        m["hc"] = w / 2; m["vc"] = h / 2
        m["ss"] = ss; m["ls"] = max(w, h)
        for (d in intArrayOf(2, 3, 4, 5, 6, 8, 10, 12, 16, 32)) {
            m["wd$d"] = w / d; m["hd$d"] = h / d; m["ssd$d"] = ss / d
        }
        m["cd2"] = 10800000.0; m["cd4"] = 5400000.0; m["cd8"] = 2700000.0
        m["3cd4"] = 16200000.0; m["3cd8"] = 8100000.0; m["5cd8"] = 13500000.0; m["7cd8"] = 18900000.0
    }

    private fun value(s: String?, vars: Map<String, Double>): Double {
        if (s == null) return 0.0
        return vars[s] ?: s.toDoubleOrNull() ?: 0.0
    }

    private fun evalGuide(gd: Element, vars: HashMap<String, Double>) {
        val name = gd.attr("name") ?: return
        val f = (gd.attr("fmla") ?: return).trim().split(Regex("\\s+"))
        fun a(i: Int) = value(f.getOrNull(i), vars)
        val rad = PI / 180.0 / 60000.0
        val r = when (f[0]) {
            "val" -> a(1)
            "*/" -> if (a(3) == 0.0) 0.0 else a(1) * a(2) / a(3)
            "+-" -> a(1) + a(2) - a(3)
            "+/" -> if (a(3) == 0.0) 0.0 else (a(1) + a(2)) / a(3)
            "?:" -> if (a(1) > 0) a(2) else a(3)
            "abs" -> abs(a(1))
            "at2" -> atan2(a(2), a(1)) / rad
            "cat2" -> a(1) * cos(atan2(a(3), a(2)))
            "sat2" -> a(1) * sin(atan2(a(3), a(2)))
            "cos" -> a(1) * cos(a(2) * rad)
            "sin" -> a(1) * sin(a(2) * rad)
            "tan" -> a(1) * tan(a(2) * rad)
            "max" -> max(a(1), a(2))
            "min" -> min(a(1), a(2))
            "mod" -> sqrt(a(1) * a(1) + a(2) * a(2) + a(3) * a(3))
            "pin" -> when { a(2) < a(1) -> a(1); a(2) > a(3) -> a(3); else -> a(2) }
            "sqrt" -> sqrt(max(0.0, a(1)))
            else -> 0.0
        }
        vars[name] = if (r.isNaN()) 0.0 else r
    }
}
