package com.cnumed.mlms.pdfconvert

import org.w3c.dom.Element
import kotlin.math.max

// ───────────────────────── 스타일 모델 ─────────────────────────

internal class RunProps(
    var size: Double? = null,
    var bold: Boolean? = null,
    var italic: Boolean? = null,
    var underline: String? = null,
    var strike: String? = null,
    var baseline: Double? = null,
    var cap: String? = null,
    var spc: Double? = null,
    var color: Rgba? = null,
    var latin: String? = null,
    var ea: String? = null,
    var cs: String? = null,
    var sym: String? = null,
    var highlight: Rgba? = null,
    var hlink: Boolean = false,
    var kern: Double? = null,
) {
    fun copy() = RunProps(size, bold, italic, underline, strike, baseline, cap, spc, color, latin, ea, cs, sym, highlight, hlink, kern)

    fun merge(o: RunProps) {
        o.size?.let { size = it }; o.bold?.let { bold = it }; o.italic?.let { italic = it }
        o.underline?.let { underline = it }; o.strike?.let { strike = it }; o.baseline?.let { baseline = it }
        o.cap?.let { cap = it }; o.spc?.let { spc = it }; o.color?.let { color = it }
        o.latin?.let { latin = it }; o.ea?.let { ea = it }; o.cs?.let { cs = it }; o.sym?.let { sym = it }
        o.highlight?.let { highlight = it }; if (o.hlink) hlink = true; o.kern?.let { kern = it }
    }

    fun merge(rPr: Element?, cc: ColorContext) {
        rPr ?: return
        rPr.attr("sz")?.toDoubleOrNull()?.let { size = it / 100.0 }
        rPr.bool("b")?.let { bold = it }
        rPr.bool("i")?.let { italic = it }
        rPr.attr("u")?.let { underline = it }
        rPr.attr("strike")?.let { strike = it }
        rPr.attr("baseline")?.toDoubleOrNull()?.let { baseline = it / 100000.0 }
        rPr.attr("cap")?.let { cap = it }
        rPr.attr("spc")?.toDoubleOrNull()?.let { spc = it / 100.0 }
        rPr.attr("kern")?.toDoubleOrNull()?.let { kern = it / 100.0 }
        for (c in rPr.elements()) when (c.ln) {
            "latin" -> latin = c.attr("typeface")
            "ea" -> ea = c.attr("typeface")
            "cs" -> cs = c.attr("typeface")
            "sym" -> sym = c.attr("typeface")
            "solidFill" -> cc.colorIn(c)?.let { color = it }
            "gradFill" -> c.child("gsLst")?.elements()?.firstOrNull()?.let { cc.colorIn(it) }?.let { color = it }
            "noFill" -> color = Rgba(0.0, 0.0, 0.0, 0.0)
            "highlight" -> cc.colorIn(c)?.let { highlight = it }
            "hlinkClick" -> if (c.rAttr("id") != null) hlink = true
        }
    }
}

internal class Bullet(val type: String /* none|char|auto */, val char: String = "•", val autoType: String = "arabicPeriod", val startAt: Int = 1)

internal class ParaProps {
    var algn = "l"
    var marL = 0.0
    var indent = 0.0
    var lnPct: Double? = 1.0
    var lnPts: Double? = null
    var befPct: Double? = null; var befPts: Double? = null
    var aftPct: Double? = null; var aftPts: Double? = null
    var defTab = 72.0
    var tabs: List<Double> = emptyList()
    var bullet: Bullet = Bullet("none")
    var buFont: String? = null          // null = 텍스트 글꼴 따름
    var buColor: Rgba? = null           // null = 텍스트 색 따름
    var buSzPct: Double? = null; var buSzPts: Double? = null
    var latinLnBrk = false
    var hangingPunct = true
    val run = RunProps()

    fun merge(pPr: Element?, cc: ColorContext) {
        pPr ?: return
        pPr.attr("algn")?.let { algn = it }
        pPr.long("marL")?.let { marL = emu(it) }
        pPr.long("indent")?.let { indent = emu(it) }
        pPr.long("defTabSz")?.let { if (it > 0) defTab = emu(it) }
        pPr.bool("latinLnBrk")?.let { latinLnBrk = it }
        pPr.bool("hangingPunct")?.let { hangingPunct = it }
        for (c in pPr.elements()) when (c.ln) {
            "lnSpc" -> { c.child("spcPct")?.let { lnPct = it.pct("val"); lnPts = null }; c.child("spcPts")?.let { lnPts = (it.long("val") ?: 0) / 100.0; lnPct = null } }
            "spcBef" -> { c.child("spcPct")?.let { befPct = it.pct("val"); befPts = null }; c.child("spcPts")?.let { befPts = (it.long("val") ?: 0) / 100.0; befPct = null } }
            "spcAft" -> { c.child("spcPct")?.let { aftPct = it.pct("val"); aftPts = null }; c.child("spcPts")?.let { aftPts = (it.long("val") ?: 0) / 100.0; aftPct = null } }
            "buNone" -> bullet = Bullet("none")
            "buChar" -> bullet = Bullet("char", c.attr("char") ?: "•")
            "buBlip" -> bullet = Bullet("char", "•")
            "buAutoNum" -> bullet = Bullet("auto", autoType = c.attr("type") ?: "arabicPeriod", startAt = c.int("startAt") ?: 1)
            "buFontTx" -> buFont = null
            "buFont" -> buFont = c.attr("typeface")
            "buClrTx" -> buColor = null
            "buClr" -> buColor = cc.colorIn(c)
            "buSzTx" -> { buSzPct = null; buSzPts = null }
            "buSzPct" -> { buSzPct = c.pct("val"); buSzPts = null }
            "buSzPts" -> { buSzPts = (c.long("val") ?: 0) / 100.0; buSzPct = null }
            "tabLst" -> tabs = c.children("tab").mapNotNull { t -> t.long("pos")?.let { emu(it) } }.sorted()
            "defRPr" -> run.merge(c, cc)
        }
    }
}

internal class BodyProps {
    var lIns = 7.2; var tIns = 3.6; var rIns = 7.2; var bIns = 3.6
    var anchor = "t"
    var wrap = true
    var vert = "horz"
    var rot = 0.0
    var upright = false
    var fontScale = 1.0
    var lnReduction = 0.0
    var numCol = 1
    var spcCol = 0.0
    var anchorCtr = false

    fun merge(bp: Element?) {
        bp ?: return
        bp.long("lIns")?.let { lIns = emu(it) }; bp.long("tIns")?.let { tIns = emu(it) }
        bp.long("rIns")?.let { rIns = emu(it) }; bp.long("bIns")?.let { bIns = emu(it) }
        bp.attr("anchor")?.let { anchor = it }
        bp.attr("wrap")?.let { wrap = it != "none" }
        bp.attr("vert")?.let { vert = it }
        bp.long("rot")?.let { rot = it / 60000.0 }
        bp.bool("upright")?.let { upright = it }
        bp.int("numCol")?.let { numCol = max(1, it) }
        bp.long("spcCol")?.let { spcCol = emu(it) }
        bp.bool("anchorCtr")?.let { anchorCtr = it }
        when {
            bp.child("normAutofit") != null -> {
                val na = bp.child("normAutofit")!!
                fontScale = na.pct("fontScale") ?: 1.0
                lnReduction = na.pct("lnSpcReduction") ?: 0.0
            }
            bp.child("noAutofit") != null || bp.child("spAutoFit") != null -> { fontScale = 1.0; lnReduction = 0.0 }
        }
    }
}

internal class TextEnv(val fonts: FontManager, val cc: ColorContext, val theme: Theme, val slideNumber: Int)

/**
 * 텍스트 스타일 상속 체인.
 * base: 프레젠테이션 기본 스타일 / 마스터 txStyles / 마스터·레이아웃 개체 틀 lstStyle (낮은 우선순위 → 높은 순)
 * fontRef: 도형 스타일(p:style/fontRef)의 색·글꼴 — base보다 우선
 * own: 도형 자신의 lstStyle
 */
internal class TextStyleChain(val base: List<Element>, val fontRef: RunProps?, val own: Element?)

// ───────────────────────── 레이아웃 모델 ─────────────────────────

private class Glyph(
    val cp: Int,
    val text: String,
    val width: Double,
    val style: RunProps,
    val font: FontChoice?,
    val size: Double,
    val isSpace: Boolean,
    val isTab: Boolean = false,
    val isBreak: Boolean = false,
) {
    var x = 0.0 // 라인 내 위치(레이아웃 후)
    var w = width
}

private class Line(val glyphs: List<Glyph>, val firstOfPara: Boolean, val lastOfPara: Boolean, val endsWithBreak: Boolean) {
    var asc = 0.0; var desc = 0.0; var height = 0.0; var baseline = 0.0
    /** 줄 위쪽에서 기준선까지 거리 */
    var baseOff = 0.0
    var startX = 0.0; var avail = 0.0
}

private class ParaLayout(val props: ParaProps, val lines: List<Line>, val bulletText: String?, val bulletFont: FontChoice?,
                         val bulletSize: Double, val bulletColor: Rgba, val bulletX: Double, val spcBef: Double, val spcAft: Double)

internal object TextRenderer {

    fun hasText(txBody: Element?): Boolean {
        txBody ?: return false
        return txBody.children("p").any { p -> p.elements().any { (it.ln == "r" || it.ln == "fld") && (it.child("t")?.textContent ?: "").isNotEmpty() } }
    }

    /**
     * 텍스트 상자를 그린다. (x,y,w,h)는 현재 좌표계에서의 텍스트 영역(인셋 적용 전).
     * @return 레이아웃된 텍스트 높이(인셋 포함)
     */
    fun draw(p: Painter?, env: TextEnv, txBody: Element, body: BodyProps, chain: TextStyleChain,
             x: Double, y: Double, w: Double, h: Double): Double {
        val vertical = body.vert == "vert" || body.vert == "eaVert" || body.vert == "wordArtVert" || body.vert == "mongolianVert"
        val vertical270 = body.vert == "vert270"
        val (bw, bh) = if (vertical || vertical270) h to w else w to h
        val innerW = max(1.0, bw - body.lIns - body.rIns)
        val innerH = bh - body.tIns - body.bIns
        val cols = body.numCol
        val colW = if (cols > 1) max(1.0, (innerW - body.spcCol * (cols - 1)) / cols) else innerW
        val paras = layout(env, txBody, body, chain, colW)
        if (paras.isEmpty()) return 0.0

        // 세로 위치 계산
        var cy = 0.0
        val placed = ArrayList<Pair<ParaLayout, Line>>()
        paras.forEachIndexed { pi, pl ->
            if (pi > 0) cy += pl.spcBef
            pl.lines.forEach { ln -> ln.baseline = cy + ln.baseOff; cy += ln.height; placed.add(pl to ln) }
            if (pi < paras.size - 1) cy += pl.spcAft
        }
        // 마지막 줄의 아래쪽 줄간격 여분은 텍스트 높이에 넣지 않는다 (PowerPoint 실측)
        val lastLine = placed.lastOrNull()?.second
        val total = if (lastLine != null) cy - max(0.0, lastLine.height - lastLine.baseOff - lastLine.desc) else cy
        if (p == null) return total + body.tIns + body.bIns

        // 다단: 높이를 넘으면 다음 단으로
        val colOf = IntArray(placed.size)
        val colShift = DoubleArray(placed.size)
        if (cols > 1 && innerH > 0) {
            var col = 0; var offset = 0.0
            placed.forEachIndexed { i, (_, ln) ->
                val top = ln.baseline - ln.baseOff - offset
                if (top + ln.height > innerH + 0.5 && col < cols - 1 && top > 0) { col++; offset += top }
                colOf[i] = col; colShift[i] = offset
            }
        }
        val usedH = if (cols > 1) innerH else total
        val top = when (body.anchor) {
            "ctr" -> body.tIns + (innerH - usedH) / 2
            "b" -> body.tIns + innerH - usedH
            else -> body.tIns
        }

        p.saved {
            // 텍스트 상자 좌표계로 이동(세로쓰기는 회전)
            when {
                vertical -> p.transform(Affine.translate(x + w, y) * Affine.rotate(90.0))
                vertical270 -> p.transform(Affine.translate(x, y + h) * Affine.rotate(-90.0))
                else -> p.transform(Affine.translate(x, y))
            }
            if (body.rot != 0.0) p.transform(Affine.translate(bw / 2, bh / 2) * Affine.rotate(body.rot) * Affine.translate(-bw / 2, -bh / 2))
            placed.forEachIndexed { i, (pl, ln) ->
                val dx = body.lIns + colOf[i] * (colW + body.spcCol)
                drawLine(p, pl, ln, dx, top + ln.baseline - colShift[i])
            }
        }
        return total + body.tIns + body.bIns
    }

    private fun drawLine(p: Painter, pl: ParaLayout, ln: Line, dx: Double, baseline: Double) {
        if (ln.firstOfPara && pl.bulletText != null && pl.bulletFont != null && ln.glyphs.isNotEmpty()) {
            p.text(pl.bulletFont.face, pl.bulletSize, dx + pl.bulletX, baseline, pl.bulletText, pl.bulletColor,
                pl.bulletFont.synthBold, pl.bulletFont.synthItalic, 0.0)
        }
        // 같은 스타일의 연속 글자를 묶어 한 번에 출력
        var i = 0
        val gs = ln.glyphs
        while (i < gs.size) {
            val g = gs[i]
            if (g.font == null || g.isTab || g.isBreak) { i++; continue }
            var j = i + 1
            val sb = StringBuilder(g.text)
            val parts = ArrayList<Any>()
            val spc = g.style.spc ?: 0.0
            while (j < gs.size) {
                val n = gs[j]
                if (n.font !== g.font || n.style !== g.style || n.size != g.size || n.isTab || n.isBreak) break
                // 글꼴 전진폭으로 예상한 위치와 실제(커닝·양쪽 정렬 반영) 위치의 차이
                val prev = gs[j - 1]
                val expected = prev.x + g.font.face.advance(prev.text.codePointAt(0)) * g.size + spc
                val delta = n.x - expected
                if (kotlin.math.abs(delta) > 0.001) { parts.add(sb.toString()); sb.setLength(0); parts.add(delta) }
                sb.append(n.text); j++
            }
            parts.add(sb.toString())
            val st = g.style
            val color = st.color ?: Rgba.BLACK
            // baseline은 원래 글자 크기 기준 비율 (g.size는 이미 위/아래 첨자용으로 줄인 크기)
            val shift = (st.baseline ?: 0.0) * g.size * SUPER_SCALE
            val by = baseline - shift
            val x0 = dx + ln.startX + g.x
            val last = gs[j - 1]
            val runW = last.x + last.w - g.x
            st.highlight?.let { hl ->
                p.setFill(hl); p.rect(x0, baseline - ln.asc, runW, ln.asc + ln.desc); p.fill()
            }
            if (color.a > 0) {
                if (parts.any { it is String && it.isNotBlank() }) {
                    p.textPositioned(g.font.face, g.size, x0, by, parts.filter { it !is String || it.isNotEmpty() }, color,
                        g.font.synthBold, g.font.synthItalic, spc)
                }
                val ul = st.underline
                if (ul != null && ul != "none") {
                    val f = g.font.face
                    val th = max(0.5, f.underlineThickness * g.size) * (if (ul.startsWith("heavy") || ul == "thick") 2 else 1)
                    p.setFill(color)
                    p.rect(x0, by - f.underlinePos * g.size - th / 2, trimTrailingSpaces(gs, i, j, runW), th)
                    p.fill()
                    if (ul == "dbl") { p.rect(x0, by - f.underlinePos * g.size + th * 1.5, trimTrailingSpaces(gs, i, j, runW), th); p.fill() }
                }
                val sk = st.strike
                if (sk != null && sk != "noStrike") {
                    val th = max(0.5, g.size * 0.05)
                    p.setFill(color)
                    p.rect(x0, by - g.size * 0.3 - th / 2, trimTrailingSpaces(gs, i, j, runW), th); p.fill()
                    if (sk == "dblStrike") { p.rect(x0, by - g.size * 0.3 + th * 1.5, runW, th); p.fill() }
                }
            }
            i = j
        }
    }

    private fun trimTrailingSpaces(gs: List<Glyph>, i: Int, j: Int, w: Double): Double {
        var k = j - 1; var ww = w
        while (k > i && gs[k].isSpace && k == gs.size - 1) { ww -= gs[k].w; k-- }
        return ww
    }

    private const val SUPER_SCALE = 1.5

    /** 자동 맞춤 축소 크기: PowerPoint는 축소된 글자 크기를 정수 pt로 반올림한다 (실측) */
    private fun scaled(size: Double, fs: Double): Double =
        if (fs >= 0.9999) size else max(1.0, Math.round(size * fs).toDouble())

    // ───────────────────────── 레이아웃 ─────────────────────────

    private fun layout(env: TextEnv, txBody: Element, body: BodyProps, chain: TextStyleChain, width: Double): List<ParaLayout> {
        val out = ArrayList<ParaLayout>()
        val autoCounters = HashMap<Int, Int>()
        var lastAutoKey: String? = null
        val paras = txBody.children("p")
        paras.forEachIndexed { pIndex, pe ->
            val pPr = pe.child("pPr")
            val lvl = (pPr?.int("lvl") ?: 0).coerceIn(0, 8)
            val pp = ParaProps()
            fun lvlEl(lst: Element?): List<Element> = lst?.let { l -> listOfNotNull(l.child("defPPr"), l.child("lvl${lvl + 1}pPr")) } ?: emptyList()
            chain.base.forEach { lst -> lvlEl(lst).forEach { pp.merge(it, env.cc) } }
            chain.fontRef?.let { pp.run.merge(it) }
            lvlEl(chain.own).forEach { pp.merge(it, env.cc) }
            pp.merge(pPr, env.cc)

            val fs = body.fontScale
            val glyphs = ArrayList<Glyph>()
            var firstRun: RunProps? = null
            for (c in pe.elements()) {
                when (c.ln) {
                    "r", "fld" -> {
                        val rp = pp.run.copy(); rp.merge(c.child("rPr"), env.cc)
                        if (rp.hlink && c.child("rPr")?.child("solidFill") == null) {
                            env.cc.schemeColor("hlink")?.let { rp.color = it }
                            if (rp.underline == null) rp.underline = "sng"
                        }
                        var t = c.child("t")?.textContent ?: ""
                        if (c.ln == "fld" && c.attr("type") == "slidenum") t = env.slideNumber.toString()
                        if (t.isEmpty()) continue
                        if (firstRun == null) firstRun = rp
                        appendText(env, glyphs, t, rp, fs)
                    }
                    "br" -> {
                        val rp = pp.run.copy(); rp.merge(c.child("rPr"), env.cc)
                        val sz = scaled(rp.size ?: 18.0, fs)
                        glyphs.add(Glyph('\n'.code, "", 0.0, rp, primaryFont(env, rp, 'A'.code), sz, isSpace = false, isBreak = true))
                    }
                }
            }
            val endRp = pp.run.copy().also { it.merge(pe.child("endParaRPr"), env.cc) }
            val baseRp = firstRun ?: endRp
            val firstSize = scaled(baseRp.size ?: 18.0, fs)

            // 글머리
            var bulletText: String? = null
            var bulletFont: FontChoice? = null
            var bulletSize = firstSize
            var bulletColor = baseRp.color ?: Rgba.BLACK
            if (glyphs.isNotEmpty() && pp.bullet.type != "none") {
                bulletSize = pp.buSzPts ?: (firstSize * (pp.buSzPct ?: 1.0))
                pp.buColor?.let { bulletColor = it }
                val buFace = pp.buFont?.let { env.theme.resolveTypeface(it) }
                if (pp.bullet.type == "char") {
                    val raw = pp.bullet.char
                    val mapped = SymbolFonts.map(buFace, raw)
                    val pick = pickBullet(env, buFace ?: baseRp.latin?.let { env.theme.resolveTypeface(it) }, mapped)
                    bulletText = pick?.first; bulletFont = pick?.second
                } else {
                    val key = "$lvl:${pp.bullet.autoType}"
                    val n = if (lastAutoKey == key) (autoCounters[lvl] ?: pp.bullet.startAt) + 1 else pp.bullet.startAt
                    autoCounters[lvl] = n
                    val s = AutoNumber.format(pp.bullet.autoType, n)
                    val pick = pickBullet(env, env.theme.resolveTypeface(baseRp.latin ?: "+mn-lt"), s)
                    bulletText = pick?.first; bulletFont = pick?.second
                }
            }
            if (pp.bullet.type == "auto" && glyphs.isNotEmpty()) lastAutoKey = "$lvl:${pp.bullet.autoType}"
            else if (glyphs.isNotEmpty()) { // 번호 없는 문단이 끼면 같은/하위 수준 번호를 새로 시작
                autoCounters.keys.filter { it >= lvl }.forEach { autoCounters.remove(it) }; lastAutoKey = null
            }

            // 줄 나누기
            val bulletW = if (bulletText != null && bulletFont != null) measure(bulletFont, bulletText, bulletSize) else 0.0
            val firstStart: Double
            val bulletX: Double
            if (bulletText != null) {
                bulletX = pp.marL + pp.indent
                firstStart = if (pp.indent < 0 && bulletX + bulletW <= pp.marL) pp.marL else bulletX + bulletW + bulletSize * 0.25
            } else { bulletX = 0.0; firstStart = pp.marL + pp.indent }
            val lines = breakLines(glyphs, body.wrap, width, firstStart.coerceAtLeast(0.0), pp.marL, pp, endRp, env, fs)

            // 줄 높이 — PowerPoint 실측: '한 줄' 간격은 글꼴과 무관하게 글자 크기의 1.2배이고,
            // 기준선은 (1.2×크기 − 글꼴 하강값). 줄 간격을 늘리면 늘어난 양의 약 70%가 줄 위쪽에 붙는다.
            val reduction = body.lnReduction
            lines.forEach { ln ->
                var asc = 0.0; var desc = 0.0; var maxSize = 0.0
                ln.glyphs.forEach { g ->
                    val f = g.font?.face ?: return@forEach
                    asc = max(asc, f.ascent * g.size); desc = max(desc, f.descent * g.size)
                    maxSize = max(maxSize, if (g.style.baseline != null && g.style.baseline != 0.0) g.size * SUPER_SCALE else g.size)
                }
                if (asc == 0.0) {
                    val sz = scaled(endRp.size ?: 18.0, fs)
                    val f = primaryFont(env, endRp, 'A'.code)?.face
                    asc = (f?.ascent ?: 0.9) * sz; desc = (f?.descent ?: 0.25) * sz; maxSize = sz
                }
                ln.asc = asc; ln.desc = desc
                val single = 1.2 * maxSize
                if (pp.lnPts != null) {
                    ln.height = pp.lnPts!!
                    ln.baseOff = ln.height - desc * (ln.height / single).coerceAtMost(1.0)
                } else {
                    val pct = max(0.1, (pp.lnPct ?: 1.0) - reduction)
                    ln.height = single * pct
                    ln.baseOff = if (pct >= 1.0) single - desc + (pct - 1.0) * single * 0.7 else single * pct - desc
                }
            }
            fun spacing(pct: Double?, pts: Double?): Double =
                pts ?: pct?.let { it * firstSize * 1.2 * (1 - reduction).coerceAtLeast(0.2) } ?: 0.0
            out.add(ParaLayout(pp, lines, bulletText, bulletFont, bulletSize, bulletColor, bulletX,
                if (pIndex == 0) 0.0 else spacing(pp.befPct, pp.befPts), spacing(pp.aftPct, pp.aftPts)))
        }
        return out
    }

    private fun pickBullet(env: TextEnv, face: String?, s: String): Pair<String, FontChoice>? {
        var text = s
        for (attempt in 0..1) {
            val cp = text.codePointAt(0)
            val ch = env.fonts.choices(face, false, false).firstOrNull { it.face.hasGlyph(cp) }
            if (ch != null) {
                // 모든 글자가 이 글꼴에 있어야 한 번에 출력 가능
                if (text.codePoints().allMatch { it == ' '.code || ch.face.hasGlyph(it) }) return text to ch
            }
            text = SymbolFonts.fallbackChar(text)
        }
        val ch = env.fonts.choices(face, false, false).firstOrNull { it.face.hasGlyph('•'.code) } ?: return null
        return "•" to ch
    }

    private fun measure(f: FontChoice, s: String, size: Double): Double {
        var w = 0.0
        s.codePoints().forEach { w += f.face.advance(it) * size }
        return w
    }

    private fun primaryFont(env: TextEnv, rp: RunProps, cp: Int): FontChoice? {
        val face = env.theme.resolveTypeface(if (isEastAsian(cp)) rp.ea ?: rp.latin else rp.latin) ?: env.theme.minorLatin
        return env.fonts.choices(face, rp.bold == true, rp.italic == true).firstOrNull { it.face.hasGlyph(cp) }
            ?: env.fonts.choices(face, rp.bold == true, rp.italic == true).firstOrNull()
    }

    private fun appendText(env: TextEnv, out: MutableList<Glyph>, raw: String, rp: RunProps, fs: Double) {
        var text = when (rp.cap) { "all", "small" -> raw.uppercase(); else -> raw }
        val symFace = rp.sym?.let { env.theme.resolveTypeface(it) }
        val latinFace = env.theme.resolveTypeface(rp.latin) ?: env.theme.minorLatin
        val eaFace = env.theme.resolveTypeface(rp.ea) ?: env.theme.resolveTypeface("+mn-ea")
        if (SymbolFonts.isSymbolFont(latinFace)) text = SymbolFonts.mapString(latinFace, text)
        var size = scaled(rp.size ?: 18.0, fs)
        if (rp.cap == "small") size *= 0.8
        if (rp.baseline != null && rp.baseline != 0.0) size /= SUPER_SCALE
        var i = 0
        while (i < text.length) {
            var cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '\r'.code) continue
            if (cp == 0x0B || cp == '\n'.code) {
                out.add(Glyph('\n'.code, "", 0.0, rp, null, size, isSpace = false, isBreak = true)); continue
            }
            if (cp == '\t'.code) { out.add(Glyph(cp, "", 0.0, rp, null, size, isSpace = true, isTab = true)); continue }
            if (cp in 0xF020..0xF0FF && symFace != null) cp = SymbolFonts.map(symFace, String(Character.toChars(cp))).codePointAt(0)
            if (cp < 0x20 || cp == 0xFEFF) continue
            if (cp == 0xA0) cp = ' '.code
            val face = if (isEastAsian(cp)) eaFace ?: latinFace else latinFace
            val choices = env.fonts.choices(face, rp.bold == true, rp.italic == true)
            var chosen = choices.firstOrNull { it.face.hasGlyph(cp) }
            var s = String(Character.toChars(cp))
            if (chosen == null) {
                val alt = SymbolFonts.fallbackChar(s)
                if (alt != s) { chosen = choices.firstOrNull { it.face.hasGlyph(alt.codePointAt(0)) }; if (chosen != null) s = alt }
            }
            val isSpace = cp == ' '.code || cp == 0x3000
            if (chosen == null && !isSpace) continue
            val f = chosen ?: choices.firstOrNull()
            val w = (f?.face?.advance(s.codePointAt(0)) ?: 0.25) * size + (rp.spc ?: 0.0)
            // 커닝: kern 속성(이 크기 이상에서 커닝)이 있고 앞 글자와 같은 글꼴·크기일 때
            val prev = out.lastOrNull()
            val kt = rp.kern
            if (prev != null && f != null && kt != null && kt > 0 && size >= kt && prev.font === f && prev.size == size && !prev.isBreak && !prev.isTab) {
                prev.w += f.face.kern(prev.text.codePointAt(0), s.codePointAt(0)) * size
            }
            out.add(Glyph(cp, s, w, rp, f, size, isSpace))
        }
    }

    private fun breakLines(glyphs: List<Glyph>, wrap: Boolean, width: Double, firstStart: Double, restStart: Double,
                           pp: ParaProps, endRp: RunProps, env: TextEnv, fs: Double): List<Line> {
        val lines = ArrayList<Line>()
        if (glyphs.isEmpty()) {
            val ln = Line(emptyList(), true, true, false); ln.startX = firstStart; ln.avail = width - firstStart
            return listOf(ln)
        }
        var start = 0
        var first = true
        while (start < glyphs.size) {
            val sx = if (first) firstStart else restStart
            val avail = max(1.0, width - sx)
            var x = 0.0
            var lastBreak = -1        // 이 인덱스 뒤에서 끊을 수 있음
            var end = start
            var forced = false
            while (end < glyphs.size) {
                val g = glyphs[end]
                if (g.isBreak) { forced = true; break }
                if (g.isTab) {
                    val abs = sx + x
                    val next = pp.tabs.firstOrNull { it > abs + 0.01 } ?: ((kotlin.math.floor((abs) / pp.defTab) + 1) * pp.defTab)
                    g.w = max(0.0, next - abs)
                }
                g.x = x
                val hangs = pp.hangingPunct && g.cp in HANGING
                if (wrap && !g.isSpace && !hangs && x + g.w > avail + 0.01 && end > start) {
                    // 넘침: 마지막 끊김 위치에서 자른다
                    if (lastBreak >= start) end = lastBreak + 1
                    break
                }
                x += g.w
                if (canBreakAfter(glyphs, end, pp.latinLnBrk)) lastBreak = end
                end++
            }
            if (end == start) end = start + 1 // 한 글자도 안 들어가면 강제로 하나
            val lineGlyphs = glyphs.subList(start, minOf(end, glyphs.size))
            val nextStart = if (forced && end < glyphs.size && glyphs[end].isBreak) end + 1 else end
            val isLast = nextStart >= glyphs.size
            val ln = Line(lineGlyphs, first, isLast, forced)
            ln.startX = sx; ln.avail = avail
            // 위치 재계산(줄 시작 기준) + 정렬
            var cx = 0.0
            lineGlyphs.forEach { g -> g.x = cx; cx += g.w }
            align(ln, pp.algn)
            lines.add(ln)
            // 강제 줄바꿈이 문단 끝이면 빈 줄 하나 추가
            if (forced && nextStart >= glyphs.size) {
                val br = glyphs[end]
                val empty = Line(listOf(br), false, true, false); empty.startX = restStart; empty.avail = width - restStart
                lines.add(empty)
            }
            start = nextStart
            first = false
        }
        return lines
    }

    private fun align(ln: Line, algn: String) {
        val gs = ln.glyphs
        if (gs.isEmpty()) return
        var lastVisible = gs.size - 1
        while (lastVisible >= 0 && (gs[lastVisible].isSpace || gs[lastVisible].isBreak) && !gs[lastVisible].isTab) lastVisible--
        if (lastVisible < 0) return
        val lw = gs[lastVisible].x + gs[lastVisible].w
        val extra = ln.avail - lw
        when (algn) {
            "ctr" -> gs.forEach { it.x += extra / 2 }
            "r" -> gs.forEach { it.x += extra }
            "just", "dist", "thaiDist", "justLow" -> {
                if ((algn == "just" || algn == "justLow") && (ln.lastOfPara || ln.endsWithBreak)) return
                if (extra <= 0) return
                val spaces = (0 until lastVisible).count { gs[it].isSpace }
                if (spaces > 0) {
                    val add = extra / spaces; var acc = 0.0
                    for (k in gs.indices) { gs[k].x += acc; if (k < lastVisible && gs[k].isSpace) { gs[k].w += add; acc += add } }
                } else if (lastVisible > 0) {
                    val add = extra / lastVisible; var acc = 0.0
                    for (k in gs.indices) { gs[k].x += acc; if (k < lastVisible) acc += add }
                }
            }
        }
    }

    private fun canBreakAfter(gs: List<Glyph>, i: Int, latinLnBrk: Boolean): Boolean {
        val c = gs[i].cp
        val n = gs.getOrNull(i + 1)?.cp ?: return true
        if (gs[i].isSpace) return n != ' '.code
        if (n in NO_START) return false
        if (c in NO_END) return false
        if (n in NO_END) return true // 여는 괄호 앞에서는 줄을 바꿀 수 있다
        if (c == '-'.code || c == 0x2014 || c == 0x2013) return !gs[i + 1].isSpace
        if (isCjkBreakable(c) || isCjkBreakable(n)) return true
        // latinLnBrk=1은 한국어판 PowerPoint의 '한글 단어 잘림 허용'(기본 켜짐)에 해당: 한글은 글자 단위로 줄바꿈
        if (latinLnBrk && (isHangul(c) || isHangul(n))) return true
        return false
    }

    private val NO_START = setOf(')', ']', '}', '.', ',', '!', '?', ':', ';', '%', '、', '。', '」', '』', '）', '，', '．', '·', '\'', '"', '’', '”').map { it.code }.toSet()
    /** 줄 끝에서 오른쪽 여백 밖으로 내밀 수 있는 문장부호 (hangingPunct) */
    private val HANGING = setOf('.', ',', '?', '!', ':', ';', ')', ']', '}', '、', '。', '，', '．', '」', '』', '）', '’', '”', '"', '\'').map { it.code }.toSet()
    private val NO_END = setOf('(', '[', '{', '「', '『', '（', '‘', '“').map { it.code }.toSet()

    private fun isHangul(cp: Int) = cp in 0xAC00..0xD7A3 || cp in 0x1100..0x11FF || cp in 0x3130..0x318F

    /** 한자·가나 등 글자 단위로 줄을 바꿀 수 있는 문자 (한글은 어절 단위로 나눈다) */
    private fun isCjkBreakable(cp: Int) = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x3040..0x30FF ||
        cp in 0xF900..0xFAFF || cp in 0x20000..0x2FFFF || cp in 0x3000..0x303F || cp in 0xFF00..0xFFEF

    fun isEastAsian(cp: Int) = cp in 0x1100..0x11FF || cp in 0x2E80..0x9FFF || cp in 0xA960..0xA97F || cp in 0xAC00..0xD7FF ||
        cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFFEF || cp in 0x20000..0x2FFFF || cp in 0x3130..0x318F
}

// ───────────────────────── 글머리 번호 ─────────────────────────

internal object AutoNumber {
    fun format(type: String, n: Int): String {
        val core = when {
            type.startsWith("arabic") -> n.toString()
            type.startsWith("alphaLc") -> alpha(n).lowercase()
            type.startsWith("alphaUc") -> alpha(n)
            type.startsWith("romanLc") -> roman(n).lowercase()
            type.startsWith("romanUc") -> roman(n)
            type.startsWith("circleNumDb") || type.startsWith("circleNumWdWhite") -> if (n in 1..20) String(Character.toChars(0x2460 + n - 1)) else n.toString()
            type.startsWith("circleNumWdBlack") -> if (n in 1..10) String(Character.toChars(0x2776 + n - 1)) else n.toString()
            type.startsWith("hangulGanada") || type.startsWith("ea1Kor") -> GANADA.getOrElse(n - 1) { n.toString() }
            else -> n.toString()
        }
        return when {
            type.endsWith("ParenBoth") -> "($core)"
            type.endsWith("ParenR") -> "$core)"
            type.endsWith("Period") || type.endsWith("DbPeriod") -> "$core."
            type.endsWith("Minus") -> "- $core -"
            else -> core
        }
    }

    private val GANADA = listOf("가", "나", "다", "라", "마", "바", "사", "아", "자", "차", "카", "타", "파", "하")

    private fun alpha(n0: Int): String {
        var n = n0; val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun roman(n0: Int): String {
        var n = n0
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        val sb = StringBuilder()
        for (i in v.indices) while (n >= v[i]) { sb.append(s[i]); n -= v[i] }
        return sb.toString()
    }
}

// ───────────────────────── Symbol / Wingdings 글꼴 문자 변환 ─────────────────────────

internal object SymbolFonts {
    fun isSymbolFont(face: String?): Boolean {
        val f = normFamily(face ?: return false)
        return f == "symbol" || f.startsWith("wingdings") || f == "webdings"
    }

    fun mapString(face: String?, s: String): String = buildString { s.forEach { append(map(face, it.toString())) } }

    fun map(face: String?, s: String): String {
        if (s.isEmpty()) return s
        val f = normFamily(face ?: return s)
        var c = s[0].code
        if (c in 0xF000..0xF0FF) c -= 0xF000
        val table = when {
            f == "symbol" -> SYMBOL
            f == "wingdings" -> WINGDINGS
            f == "wingdings2" -> WINGDINGS2
            f == "wingdings3" -> WINGDINGS3
            else -> return s
        }
        table[c]?.let { return it.toString() }
        return if (f == "symbol") c.toChar().toString() else "•"
    }

    /** 글리프가 없을 때 비슷한 모양의 흔한 문자 */
    fun fallbackChar(s: String): String = when (s) {
        "➢", "➤", "►", "➔", "➜", "➨", "⇨" -> "▶"
        "▶" -> "►"
        "✓", "✔" -> "√"
        "✗", "✘" -> "×"
        "❖", "⬥", "◆" -> "◆"
        "❑", "❒", "◻" -> "□"
        "▪" -> "■"
        "⬧" -> "◆"
        "●" -> "•"
        else -> s
    }

    private val SYMBOL: Map<Int, Char> = run {
        val m = HashMap<Int, Char>()
        val upper = "ΑΒΧΔΕΦΓΗΙϑΚΛΜΝΟΠΘΡΣΤΥςΩΞΨΖ"
        val lower = "αβχδεφγηιϕκλμνοπθρστυϖωξψζ"
        for (i in 0 until 26) { m[0x41 + i] = upper[i]; m[0x61 + i] = lower[i] }
        mapOf(0x22 to '∀', 0x24 to '∃', 0x27 to '∋', 0x2A to '∗', 0x2D to '−', 0x40 to '≅', 0x5C to '∴', 0x5E to '⊥',
            0x60 to '‾', 0x7E to '∼', 0xA1 to 'ϒ', 0xA2 to '′', 0xA3 to '≤', 0xA4 to '⁄', 0xA5 to '∞', 0xA6 to 'ƒ',
            0xA7 to '♣', 0xA8 to '♦', 0xA9 to '♥', 0xAA to '♠', 0xAB to '↔', 0xAC to '←', 0xAD to '↑', 0xAE to '→',
            0xAF to '↓', 0xB0 to '°', 0xB1 to '±', 0xB2 to '″', 0xB3 to '≥', 0xB4 to '×', 0xB5 to '∝', 0xB6 to '∂',
            0xB7 to '•', 0xB8 to '÷', 0xB9 to '≠', 0xBA to '≡', 0xBB to '≈', 0xBC to '…', 0xC0 to 'ℵ', 0xC5 to '⊕',
            0xC6 to '∅', 0xC7 to '∩', 0xC8 to '∪', 0xC9 to '⊃', 0xCA to '⊇', 0xCB to '⊄', 0xCC to '⊂', 0xCD to '⊆',
            0xCE to '∈', 0xCF to '∉', 0xD0 to '∠', 0xD1 to '∇', 0xD5 to '∏', 0xD6 to '√', 0xD7 to '⋅', 0xD8 to '¬',
            0xD9 to '∧', 0xDA to '∨', 0xDB to '⇔', 0xDC to '⇐', 0xDD to '⇑', 0xDE to '⇒', 0xDF to '⇓', 0xE0 to '◊',
            0xE5 to '∑', 0xF2 to '∫').forEach { (k, v) -> m[k] = v }
        m
    }

    private val WINGDINGS: Map<Int, Char> = mapOf(
        0x6C to '●', 0x6D to '❍', 0x6E to '■', 0x6F to '□', 0x70 to '◻', 0x71 to '❑', 0x72 to '❒', 0x73 to '⬧',
        0x74 to '⧫', 0x75 to '◆', 0x76 to '❖', 0x77 to '⬥', 0xA7 to '▪', 0xA8 to '◻', 0x9F to '•', 0xA1 to '○',
        0xA2 to '⭕', 0xA4 to '◉', 0xAB to '★', 0xD8 to '➢', 0xD7 to '➢', 0xE0 to '→', 0xE8 to '➔', 0xF0 to '⇨',
        0xFC to '✓', 0xFB to '✗', 0xFD to '☒', 0xFE to '☑', 0x46 to '☞', 0x4A to '☺', 0x4C to '☹',
        0x9E to '·', 0xE2 to '↑', 0xE3 to '↓', 0xDF to '←', 0xEF to '⇦', 0x22 to '✂', 0x28 to '☎', 0x3F to '✍',
        0xA6 to '◘', 0xAA to '✦', 0xAE to '✶', 0xB2 to '✧', 0xB5 to '⌘',
    )

    private val WINGDINGS2: Map<Int, Char> = mapOf(
        0x50 to '✓', 0x4F to '✗', 0x52 to '☑', 0x51 to '☒', 0x97 to '●', 0x98 to '●', 0xA2 to '■', 0xA3 to '□',
        0xF0 to '◆', 0xF5 to '◆', 0x9F to '•', 0xA0 to '■', 0x2A to '☐',
    )

    private val WINGDINGS3: Map<Int, Char> = mapOf(
        0x75 to '►', 0x74 to '◄', 0x70 to '▲', 0x71 to '▼', 0x7D to '▶', 0x7C to '◀', 0xC6 to '➔', 0xC7 to '⬅',
        0x67 to '➔', 0x5B to '⇦', 0x5C to '⇨',
    )
}
