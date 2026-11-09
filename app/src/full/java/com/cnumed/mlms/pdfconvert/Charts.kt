package com.cnumed.mlms.pdfconvert

import org.w3c.dom.Element
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 간단한 차트 렌더러 (막대/세로막대, 꺾은선, 영역, 원형/도넛).
 * PowerPoint는 차트의 대체 이미지를 저장하지 않으므로 캐시된 값(numCache/strCache)으로 직접 그린다.
 */
internal class ChartRenderer(private val p: Painter, private val cc: ColorContext, private val env: TextEnv, private val fonts: FontManager) {

    private class Series(val name: String, val cats: List<String>, val vals: List<Double?>, val color: Rgba?, val pointColors: Map<Int, Rgba>)

    fun tryDraw(pkg: OpcPackage, chartPart: String, m: Affine, w: Double, h: Double) {
        val root = pkg.xml(chartPart) ?: return
        val chart = root.child("chart") ?: return
        val plot = chart.child("plotArea") ?: return
        p.saved {
            p.transform(m)
            // 차트 영역 배경
            root.child("spPr")?.let { sp -> StyleResolver(cc, env.theme).fillIn(sp, chartPart) }?.let { f ->
                if (f is Fill.Solid) { p.setFill(f.color); p.rect(0.0, 0.0, w, h); p.fill() }
            }
            var top = 6.0
            val titleText = chart.child("title")?.let { t -> richText(t) } ?: ""
            if (titleText.isNotEmpty() && chart.child("autoTitleDeleted")?.bool("val") != true) {
                label(titleText, 14.0, Rgba(0.25, 0.25, 0.25), w / 2, top + 14.0, 1)
                top += 24.0
            }
            val types = plot.elements().filter { it.ln.endsWith("Chart") }
            val first = types.firstOrNull() ?: return@saved
            val series = types.flatMap { parseSeries(it) }
            if (series.isEmpty()) return@saved
            val legendPos = chart.child("legend")?.child("legendPos")?.attr("val")
            val hasLegend = chart.child("legend") != null
            var left = 8.0; var right = w - 8.0; var bottom = h - 6.0
            val isPie = first.ln == "pieChart" || first.ln == "doughnutChart" || first.ln == "pie3DChart"
            val legendItems: List<Pair<String, Rgba>> = if (isPie) {
                val s = series[0]
                s.cats.indices.map { i -> s.cats[i] to (s.pointColors[i] ?: accent(i)) }
            } else series.mapIndexed { i, s -> s.name to (s.color ?: accent(i)) }
            if (hasLegend) {
                when (legendPos) {
                    "b", null -> { bottom -= 18.0; drawLegendRow(legendItems, w / 2, bottom + 12.0) }
                    "t" -> { drawLegendRow(legendItems, w / 2, top + 8.0); top += 18.0 }
                    "l" -> { drawLegendCol(legendItems, left, (top + bottom) / 2); left += legendWidth(legendItems) + 12 }
                    else -> { right -= legendWidth(legendItems) + 12; drawLegendCol(legendItems, right + 8, (top + bottom) / 2) }
                }
            }
            if (isPie) drawPie(series[0], first, left, top, right, bottom)
            else drawAxes(types, series, plot, left, top, right, bottom)
        }
    }

    private fun richText(t: Element): String {
        val rich = t.path("tx", "rich") ?: return ""
        return rich.children("p").joinToString(" ") { p -> p.children("r").joinToString("") { it.child("t")?.textContent ?: "" } }.trim()
    }

    private fun accent(i: Int): Rgba {
        val base = cc.schemeColor("accent${i % 6 + 1}") ?: Rgba(0.27, 0.45, 0.77)
        return if (i < 6) base else Rgba(base.r * 0.7, base.g * 0.7, base.b * 0.7)
    }

    private fun strCache(e: Element?): List<String> {
        val cache = e?.let { it.path("strRef", "strCache") ?: it.path("numRef", "numCache") ?: it.child("strLit") ?: it.child("numLit") ?: it.path("multiLvlStrRef", "multiLvlStrCache", "lvl") } ?: return emptyList()
        val n = cache.child("ptCount")?.int("val") ?: 0
        val arr = Array(n) { "" }
        cache.children("pt").forEach { pt -> val i = pt.int("idx") ?: return@forEach; if (i in arr.indices) arr[i] = pt.child("v")?.textContent ?: "" }
        return arr.toList()
    }

    private fun numCache(e: Element?): List<Double?> {
        val cache = e?.let { it.path("numRef", "numCache") ?: it.child("numLit") } ?: return emptyList()
        val n = cache.child("ptCount")?.int("val") ?: 0
        val arr = arrayOfNulls<Double>(n)
        cache.children("pt").forEach { pt -> val i = pt.int("idx") ?: return@forEach; if (i in arr.indices) arr[i] = pt.child("v")?.textContent?.toDoubleOrNull() }
        return arr.toList()
    }

    private fun parseSeries(type: Element): List<Series> = type.children("ser").map { ser ->
        val name = strCache(ser.child("tx")).firstOrNull() ?: ser.child("tx")?.child("v")?.textContent ?: ""
        val cats = strCache(ser.child("cat")).ifEmpty { strCache(ser.child("xVal")) }
        val vals = numCache(ser.child("val")).ifEmpty { numCache(ser.child("yVal")) }
        val color = ser.child("spPr")?.let { sp -> cc.colorIn(sp.child("solidFill")) ?: cc.colorIn(sp.child("ln")?.child("solidFill")) }
        val pts = ser.children("dPt").mapNotNull { d -> val i = d.child("idx")?.int("val") ?: return@mapNotNull null; cc.colorIn(d.child("spPr")?.child("solidFill"))?.let { i to it } }.toMap()
        Series(name, cats, vals, color, pts)
    }

    // ───────────── 축 차트 ─────────────
    private fun drawAxes(types: List<Element>, series: List<Series>, plot: Element, l0: Double, t0: Double, r0: Double, b0: Double) {
        val first = types.first()
        val horizontalBars = first.ln.startsWith("bar") && first.child("barDir")?.attr("val") == "bar"
        val grouping = first.child("grouping")?.attr("val") ?: "clustered"
        val stacked = grouping == "stacked" || grouping == "percentStacked"
        val percent = grouping == "percentStacked"
        val nCat = series.maxOf { max(it.vals.size, it.cats.size) }
        if (nCat == 0) return
        val cats = (0 until nCat).map { i -> series.firstNotNullOfOrNull { it.cats.getOrNull(i)?.takeIf { s -> s.isNotEmpty() } } ?: "${i + 1}" }
        var vMin = 0.0; var vMax = 0.0
        for (i in 0 until nCat) {
            if (stacked) {
                val pos = series.sumOf { max(0.0, it.vals.getOrNull(i) ?: 0.0) }
                val neg = series.sumOf { min(0.0, it.vals.getOrNull(i) ?: 0.0) }
                vMax = max(vMax, if (percent) 1.0 else pos); vMin = min(vMin, if (percent) 0.0 else neg)
            } else series.forEach { s -> s.vals.getOrNull(i)?.let { vMax = max(vMax, it); vMin = min(vMin, it) } }
        }
        val valAx = plot.child("valAx")
        valAx?.path("scaling", "max")?.attr("val")?.toDoubleOrNull()?.let { vMax = it }
        valAx?.path("scaling", "min")?.attr("val")?.toDoubleOrNull()?.let { vMin = it }
        if (vMax == vMin) vMax = vMin + 1
        val step = niceStep((vMax - vMin) / 5)
        val lo = floor(vMin / step) * step; val hi = ceil(vMax / step) * step
        val fs = 9.0
        val labelW = (generateSequence(lo) { it + step }.takeWhile { it <= hi + step / 2 }.maxOfOrNull { textWidth(fmt(it, percent), fs) } ?: 20.0)
        val gray = Rgba(0.35, 0.35, 0.35)
        val grid = Rgba(0.85, 0.85, 0.85)
        val left = l0 + (if (horizontalBars) cats.maxOf { textWidth(it, fs) }.coerceAtMost((r0 - l0) * 0.3) else labelW) + 6
        val bottom = b0 - fs - 6
        val top = t0 + 4; val right = r0 - 4
        val pw = right - left; val ph = bottom - top
        if (pw <= 0 || ph <= 0) return
        fun vPos(v: Double): Double = if (horizontalBars) left + (v - lo) / (hi - lo) * pw else bottom - (v - lo) / (hi - lo) * ph
        // 눈금선 + 값 레이블
        var v = lo
        while (v <= hi + step / 2) {
            p.setStroke(grid); p.lineStyle(0.75, 0, 0, null)
            val q = vPos(v)
            if (horizontalBars) { p.path(listOf(PathCmd.Move(q, top), PathCmd.Line(q, bottom))); p.stroke(); label(fmt(v, percent), fs, gray, q, bottom + fs + 2, 1) }
            else { p.path(listOf(PathCmd.Move(left, q), PathCmd.Line(right, q))); p.stroke(); label(fmt(v, percent), fs, gray, left - 4, q + fs / 3, 2) }
            v += step
        }
        val band = (if (horizontalBars) ph else pw) / nCat
        // 항목 레이블
        cats.forEachIndexed { i, c ->
            if (horizontalBars) label(c, fs, gray, left - 4, top + band * (i + 0.5) + fs / 3, 2)
            else label(c, fs, gray, left + band * (i + 0.5), bottom + fs + 2, 1, band)
        }
        var serIndex = 0
        for (t in types) {
            val ss = parseSeries(t)
            when {
                t.ln.startsWith("bar") -> {
                    val gap = (t.child("gapWidth")?.int("val") ?: 150) / 100.0
                    val groupW = band / (1 + gap)
                    val barW = if (stacked) groupW else groupW / max(1, ss.size)
                    for (i in 0 until nCat) {
                        var posAcc = 0.0; var negAcc = 0.0
                        val total = if (percent) ss.sumOf { abs(it.vals.getOrNull(i) ?: 0.0) }.takeIf { it > 0 } ?: 1.0 else 1.0
                        ss.forEachIndexed { si, s ->
                            val raw = s.vals.getOrNull(i) ?: return@forEachIndexed
                            val value = raw / total
                            val (a, b) = if (stacked) { if (value >= 0) { val a0 = posAcc; posAcc += value; a0 to posAcc } else { val a0 = negAcc; negAcc += value; a0 to negAcc } } else 0.0 to value
                            val col = s.pointColors[i] ?: s.color ?: accent(serIndex + si)
                            val off = band * i + (band - groupW) / 2 + if (stacked) 0.0 else barW * si
                            p.setFill(col)
                            if (horizontalBars) { val x0 = vPos(min(a, b)); val x1 = vPos(max(a, b)); p.rect(x0, top + off, x1 - x0, barW) }
                            else { val y0 = vPos(max(a, b)); val y1 = vPos(min(a, b)); p.rect(left + off, y0, barW, y1 - y0) }
                            p.fill()
                        }
                    }
                }
                t.ln.startsWith("line") || t.ln.startsWith("area") || t.ln.startsWith("scatter") -> {
                    val area = t.ln.startsWith("area")
                    ss.forEachIndexed { si, s ->
                        val col = s.color ?: accent(serIndex + si)
                        val pts = (0 until nCat).mapNotNull { i -> s.vals.getOrNull(i)?.let { left + band * (i + 0.5) to vPos(it) } }
                        if (pts.size < 1) return@forEachIndexed
                        val cmds = ArrayList<PathCmd>()
                        pts.forEachIndexed { k, (x, y) -> cmds.add(if (k == 0) PathCmd.Move(x, y) else PathCmd.Line(x, y)) }
                        if (area) {
                            cmds.add(PathCmd.Line(pts.last().first, vPos(max(lo, 0.0)))); cmds.add(PathCmd.Line(pts.first().first, vPos(max(lo, 0.0)))); cmds.add(PathCmd.Close)
                            p.setFill(col); p.path(cmds); p.fill()
                        } else {
                            p.setStroke(col); p.lineStyle(2.0, 1, 1, null); p.path(cmds); p.stroke()
                            pts.forEach { (x, y) -> p.setFill(col); p.rect(x - 2.5, y - 2.5, 5.0, 5.0); p.fill() }
                        }
                    }
                }
            }
            serIndex += ss.size
        }
        // 축선
        p.setStroke(Rgba(0.6, 0.6, 0.6)); p.lineStyle(0.75, 0, 0, null)
        if (horizontalBars) { val x = vPos(max(lo, min(0.0, hi))); p.path(listOf(PathCmd.Move(x, top), PathCmd.Line(x, bottom))) }
        else { val y = vPos(max(lo, min(0.0, hi))); p.path(listOf(PathCmd.Move(left, y), PathCmd.Line(right, y))) }
        p.stroke()
    }

    private fun niceStep(raw: Double): Double {
        if (raw <= 0) return 1.0
        val mag = 10.0.pow(floor(log10(raw)))
        val f = raw / mag
        return mag * when { f <= 1 -> 1.0; f <= 2 -> 2.0; f <= 2.5 -> 2.5; f <= 5 -> 5.0; else -> 10.0 }
    }

    private fun fmt(v: Double, percent: Boolean): String {
        if (percent) return "${(v * 100).toInt()}%"
        return if (abs(v - Math.rint(v)) < 1e-9) Math.rint(v).toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')
    }

    // ───────────── 원형 ─────────────
    private fun drawPie(s: Series, type: Element, l: Double, t: Double, r: Double, b: Double) {
        val vals = s.vals.map { max(0.0, it ?: 0.0) }
        val total = vals.sum().takeIf { it > 0 } ?: return
        val cx = (l + r) / 2; val cy = (t + b) / 2
        val rad = min(r - l, b - t) / 2 - 4
        if (rad <= 0) return
        val hole = if (type.ln == "doughnutChart") (type.child("holeSize")?.int("val") ?: 50) / 100.0 else 0.0
        var ang = (type.child("firstSliceAng")?.int("val") ?: 0).toDouble() - 90.0
        vals.forEachIndexed { i, v ->
            if (v <= 0) return@forEachIndexed
            val sweep = v / total * 360.0
            val cmds = ArrayList<PathCmd>()
            val segs = max(1, ceil(sweep / 30).toInt())
            fun pt(a: Double, rr: Double) = Math.toRadians(a).let { cx + rr * cos(it) to cy + rr * sin(it) }
            if (hole > 0) pt(ang, rad * hole).let { cmds.add(PathCmd.Move(it.first, it.second)) } else cmds.add(PathCmd.Move(cx, cy))
            for (k in 0..segs) pt(ang + sweep * k / segs, rad).let { cmds.add(PathCmd.Line(it.first, it.second)) }
            if (hole > 0) for (k in segs downTo 0) pt(ang + sweep * k / segs, rad * hole).let { cmds.add(PathCmd.Line(it.first, it.second)) }
            cmds.add(PathCmd.Close)
            p.setFill(s.pointColors[i] ?: accent(i)); p.path(cmds); p.fill()
            p.setStroke(Rgba.WHITE); p.lineStyle(1.0, 0, 1, null); p.path(cmds); p.stroke()
            ang += sweep
        }
    }

    // ───────────── 범례 / 글자 ─────────────
    private fun legendWidth(items: List<Pair<String, Rgba>>) = (items.maxOfOrNull { textWidth(it.first, 9.0) } ?: 0.0) + 14

    private fun drawLegendRow(items: List<Pair<String, Rgba>>, cx: Double, y: Double) {
        val widths = items.map { textWidth(it.first, 9.0) + 22 }
        var x = cx - widths.sum() / 2
        items.forEachIndexed { i, (name, col) ->
            p.setFill(col); p.rect(x, y - 7, 7.0, 7.0); p.fill()
            label(name, 9.0, Rgba(0.3, 0.3, 0.3), x + 10, y, 0)
            x += widths[i]
        }
    }

    private fun drawLegendCol(items: List<Pair<String, Rgba>>, x: Double, cy: Double) {
        var y = cy - items.size * 7.0 + 7
        items.forEach { (name, col) ->
            p.setFill(col); p.rect(x, y - 7, 7.0, 7.0); p.fill()
            label(name, 9.0, Rgba(0.3, 0.3, 0.3), x + 10, y, 0)
            y += 14.0
        }
    }

    private fun choicesFor(cp: Int): FontChoice? {
        val face = if (TextRenderer.isEastAsian(cp)) env.theme.resolveTypeface("+mn-ea") ?: env.theme.minorLatin else env.theme.minorLatin
        return fonts.choices(face, false, false).firstOrNull { it.face.hasGlyph(cp) }
    }

    private fun textWidth(s: String, size: Double): Double {
        var w = 0.0
        s.codePoints().forEach { cp -> w += (choicesFor(cp)?.face?.advance(cp) ?: 0.5) * size }
        return w
    }

    /** align: 0=왼쪽, 1=가운데, 2=오른쪽 */
    private fun label(s0: String, size: Double, color: Rgba, x: Double, y: Double, align: Int, maxW: Double = 0.0) {
        var s = s0
        if (maxW > 0) while (s.length > 1 && textWidth(s, size) > maxW) s = s.dropLast(2) + "…"
        val w = textWidth(s, size)
        var cx = when (align) { 1 -> x - w / 2; 2 -> x - w; else -> x }
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val ch = choicesFor(cp)
            val start = i
            i += Character.charCount(cp)
            while (i < s.length && choicesFor(s.codePointAt(i)) === ch) i += Character.charCount(s.codePointAt(i))
            val seg = s.substring(start, i)
            if (ch != null) {
                p.text(ch.face, size, cx, y, seg, color, false, false, 0.0)
                seg.codePoints().forEach { cx += ch.face.advance(it) * size }
            }
        }
    }
}
