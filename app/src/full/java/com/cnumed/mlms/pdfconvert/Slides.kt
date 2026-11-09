package com.cnumed.mlms.pdfconvert

import org.w3c.dom.Element
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal class Xfrm(val x: Double, val y: Double, val w: Double, val h: Double, val rot: Double, val flipH: Boolean, val flipV: Boolean,
                    val chX: Double = 0.0, val chY: Double = 0.0, val chW: Double = 0.0, val chH: Double = 0.0) {
    companion object {
        fun parse(e: Element?): Xfrm? {
            e ?: return null
            val off = e.child("off"); val ext = e.child("ext")
            if (off == null && ext == null) return null
            val chOff = e.child("chOff"); val chExt = e.child("chExt")
            return Xfrm(emu(off?.long("x")), emu(off?.long("y")), emu(ext?.long("cx")), emu(ext?.long("cy")),
                (e.long("rot") ?: 0) / 60000.0, e.bool("flipH") ?: false, e.bool("flipV") ?: false,
                emu(chOff?.long("x")), emu(chOff?.long("y")), emu(chExt?.long("cx")), emu(chExt?.long("cy")))
        }
    }
}

/** 그룹 변환: 자식 좌표 사각형 → 상위 좌표 사각형(map) + 누적 회전/반전 행렬 */
internal class GroupCtx(val matrix: Affine, val mapRect: (DoubleArray) -> DoubleArray, val grpFill: Fill?) {
    companion object { val ROOT = GroupCtx(Affine.IDENTITY, { it }, null) }
}

/** 프레젠테이션 전역 정보 */
internal class PresInfo(val defaultTextStyle: Element?, val tableStyles: Map<String, Element>)

internal enum class SheetKind { MASTER, LAYOUT, SLIDE }

/**
 * 슬라이드 한 장 렌더러. 마스터 → 레이아웃 → 슬라이드 순으로 배경과 도형을 그린다.
 */
internal class SlideRenderer(
    private val pkg: OpcPackage,
    private val p: Painter,
    private val slidePath: String,
    private val slide: Element,
    private val layoutPath: String?,
    private val layout: Element?,
    private val masterPath: String?,
    private val master: Element?,
    private val theme: Theme,
    private val pres: PresInfo,
    private val fonts: FontManager,
    private val images: ImageLoader,
    private val slideNumber: Int,
    private val slideW: Double,
    private val slideH: Double,
    private val platform: ConverterPlatform,
    ocr: OcrOverlay? = null,
) {
    private val cc: ColorContext
    private val styles: StyleResolver
    private val env: TextEnv
    private val fills = FillPainter(images, pkg, ocr)
    private val tables: TableRenderer

    init {
        val masterMap = master?.child("clrMap")?.let { m -> m.attributes.let { a -> (0 until a.length).associate { a.item(it).nodeName to a.item(it).nodeValue } } } ?: emptyMap()
        fun ovr(e: Element?): Map<String, String>? = e?.path("clrMapOvr", "overrideClrMapping")?.let { m ->
            (0 until m.attributes.length).associate { m.attributes.item(it).nodeName to m.attributes.item(it).nodeValue }
        }
        val map = ovr(slide) ?: ovr(layout) ?: masterMap
        cc = ColorContext(theme, map)
        styles = StyleResolver(cc, theme)
        env = TextEnv(fonts, cc, theme, slideNumber)
        tables = TableRenderer(p, env, styles, fills, pres)
    }

    fun render() {
        drawBackground()
        val showMasterOnSlide = slide.bool("showMasterSp") ?: true
        val showMasterOnLayout = layout?.bool("showMasterSp") ?: true
        if (showMasterOnSlide) {
            if (showMasterOnLayout && master != null && masterPath != null) drawTree(master.path("cSld", "spTree"), masterPath, SheetKind.MASTER, GroupCtx.ROOT)
            if (layout != null && layoutPath != null) drawTree(layout.path("cSld", "spTree"), layoutPath, SheetKind.LAYOUT, GroupCtx.ROOT)
        }
        drawTree(slide.path("cSld", "spTree"), slidePath, SheetKind.SLIDE, GroupCtx.ROOT)
    }

    // ───────────── 배경 ─────────────
    private fun drawBackground() {
        val sheets = listOf(slide to slidePath, layout to layoutPath, master to masterPath)
        for ((sheet, part) in sheets) {
            val bg = sheet?.path("cSld", "bg") ?: continue
            val fill: Fill? = bg.child("bgPr")?.let { styles.fillIn(it, part!!) }
                ?: bg.child("bgRef")?.let { styles.fillRef(it, part!!) }
            if (fill != null) {
                // 테마 배경 이미지는 해당 테마 파트 기준으로 관계를 찾는다
                val f = if (fill is Fill.Picture && fill.part.startsWith("theme#")) themePicture(fill) else fill
                p.saved {
                    p.setFill(Rgba.WHITE); p.rect(0.0, 0.0, slideW, slideH); p.fill()
                    p.rect(0.0, 0.0, slideW, slideH)
                    fills.fillCurrentPath(p, f, slideW, slideH, false)
                }
                return
            }
        }
        p.setFill(Rgba.WHITE); p.rect(0.0, 0.0, slideW, slideH); p.fill()
    }

    private fun themePart(): String? = masterPath?.let { mp -> pkg.relsOfType(mp, "/theme").firstOrNull()?.target }

    private fun themePicture(f: Fill.Picture): Fill {
        val tp = themePart() ?: return Fill.None
        return Fill.Picture(f.part.replaceFirst("theme#", "$tp#"), f.alpha, f.srcRect, f.tile, f.stretchRect)
    }

    private fun fixTheme(f: Fill?): Fill? = if (f is Fill.Picture && f.part.startsWith("theme#")) themePicture(f) else f

    // ───────────── 도형 트리 ─────────────
    private fun drawTree(tree: Element?, part: String, kind: SheetKind, g: GroupCtx) {
        tree ?: return
        for (el in tree.elements()) {
            try {
                drawElement(el, part, kind, g)
            } catch (e: Exception) {
                platform.log("shape failed on slide $slideNumber (${el.ln})", e)
            } catch (e: OutOfMemoryError) {
                platform.log("shape OOM on slide $slideNumber (${el.ln})", null)
            }
        }
    }

    private fun drawElement(el: Element, part: String, kind: SheetKind, g: GroupCtx) {
        when (el.ln) {
            "sp", "cxnSp" -> {
                if (kind != SheetKind.SLIDE && ph(el) != null) return
                drawShape(el, part, kind, g)
            }
            "pic" -> {
                if (kind != SheetKind.SLIDE && ph(el) != null) return
                drawPicture(el, part, kind, g)
            }
            "grpSp" -> drawGroup(el, part, kind, g)
            "graphicFrame" -> {
                if (kind != SheetKind.SLIDE && ph(el) != null) return
                drawFrame(el, part, kind, g)
            }
            "AlternateContent" -> {
                val branch = el.child("Fallback") ?: el.child("Choice")
                branch?.let { b -> b.elements().forEach { drawElement(it, part, kind, g) } }
            }
        }
    }

    private fun nv(el: Element): Element? = el.elements().firstOrNull { it.ln.startsWith("nv") }
    private fun hidden(el: Element) = nv(el)?.child("cNvPr")?.bool("hidden") == true
    private fun ph(el: Element): Element? = nv(el)?.path("nvPr", "ph")

    // ───────────── 개체 틀 상속 ─────────────
    private fun phType(ph: Element) = ph.attr("type") ?: "obj"

    private fun findPh(sheet: Element?, ph: Element, byIdx: Boolean): Element? {
        val tree = sheet?.path("cSld", "spTree") ?: return null
        val type = phType(ph)
        val idx = ph.attr("idx")
        val all = ArrayList<Pair<Element, Element>>()
        fun collect(t: Element) {
            for (c in t.elements()) {
                if (c.ln == "grpSp") collect(c)
                else ph(c)?.let { all.add(c to it) }
            }
        }
        collect(tree)
        fun norm(t: String) = when (t) { "obj", "body" -> "body"; "ctrTitle", "title" -> "title"; else -> t }
        if (byIdx && idx != null) {
            all.firstOrNull { it.second.attr("idx") == idx }?.let { return it.first }
        }
        all.firstOrNull { phType(it.second) == type }?.let { return it.first }
        return all.firstOrNull { norm(phType(it.second)) == norm(type) }?.first
    }

    /** [마스터 개체 틀, 레이아웃 개체 틀] (있는 것만) */
    private fun inherited(el: Element, kind: SheetKind): List<Element> {
        if (kind != SheetKind.SLIDE) return emptyList()
        val ph = ph(el) ?: return emptyList()
        val lay = findPh(layout, ph, true)
        val mPh = (lay?.let { ph(it) }) ?: ph
        val mas = findPh(master, mPh, false)
        return listOfNotNull(mas, lay)
    }

    private fun masterTxStyle(ph: Element?): Element? {
        val ts = master?.child("txStyles") ?: return null
        if (ph == null) return null
        return when (phType(ph)) {
            "title", "ctrTitle" -> ts.child("titleStyle")
            "body", "obj", "subTitle", "tbl", "chart", "dgm", "media", "clipArt", "pic" -> ts.child("bodyStyle")
            else -> ts.child("otherStyle")
        }
    }

    // ───────────── 일반 도형 ─────────────
    private fun shapeRect(xf: Xfrm, g: GroupCtx): DoubleArray = g.mapRect(doubleArrayOf(xf.x, xf.y, xf.w, xf.h))

    private fun shapeMatrix(r: DoubleArray, xf: Xfrm, g: GroupCtx, forText: Boolean = false): Affine {
        val (x, y, w, h) = r.toList()
        var m = g.matrix * Affine.translate(x + w / 2, y + h / 2) * Affine.rotate(xf.rot)
        if (!forText) m = m * Affine.scale(if (xf.flipH) -1.0 else 1.0, if (xf.flipV) -1.0 else 1.0)
        else if (xf.flipV) m = m * Affine.rotate(180.0)
        return m * Affine.translate(-w / 2, -h / 2)
    }

    private fun drawShape(el: Element, part: String, kind: SheetKind, g: GroupCtx, txXfrmOverride: Xfrm? = null) {
        if (hidden(el)) return
        val inh = inherited(el, kind)
        val chain = inh + el // 낮은 우선순위 → 높은 순
        val spPrs = chain.mapNotNull { it.child("spPr") }
        val xf = spPrs.asReversed().firstNotNullOfOrNull { Xfrm.parse(it.child("xfrm")) } ?: return
        val r = shapeRect(xf, g)
        val w = r[2]; val h = r[3]
        val geomSpPr = spPrs.asReversed().firstOrNull { it.child("prstGeom") != null || it.child("custGeom") != null }
        val geom = GeometryEngine.build(geomSpPr, w, h)
        val styleEl = chain.asReversed().firstNotNullOfOrNull { it.child("style") }

        // 채우기
        var fill: Fill? = null
        for (sp in spPrs.asReversed()) { fill = styles.fillIn(sp, partOf(sp, el, part, kind)); if (fill != null) break }
        if (fill == null) fill = fixTheme(styles.fillRef(styleEl?.child("fillRef"), part))
        if (fill is Fill.Group) fill = g.grpFill
        // 선
        var line: LineSpec? = styles.lineRef(styleEl?.child("lnRef"))
        for (sp in spPrs) sp.child("ln")?.let { line = styles.mergeLine(line ?: LineSpec(), it, part) }
        if (el.ln == "cxnSp" && line == null) line = null

        p.saved {
            p.transform(shapeMatrix(r, xf, g))
            drawGeometry(geom, fill, line, w, h)
        }

        // 텍스트
        val txBody = el.child("txBody") ?: return
        if (!TextRenderer.hasText(txBody)) return
        val body = BodyProps()
        val myPh = ph(el)
        val masterPh = inh.firstOrNull()
        // 텍스트 상자 기본 인셋은 마스터 bodyPr → 레이아웃 → 슬라이드 순으로 덮어쓴다
        chain.forEach { body.merge(it.child("txBody")?.child("bodyPr")) }
        val base = ArrayList<Element>()
        if (myPh == null || kind != SheetKind.SLIDE) {
            pres.defaultTextStyle?.let { base.add(it) }
        } else {
            masterTxStyle(masterPh?.let { ph(it) } ?: myPh)?.let { base.add(it) }
        }
        inh.forEach { it.child("txBody")?.child("lstStyle")?.let(base::add) }
        val fontRef = styleEl?.child("fontRef")?.let { fr ->
            RunProps().apply {
                cc.colorIn(fr)?.let { color = it }
                when (fr.attr("idx")) { "major" -> { latin = "+mj-lt"; ea = "+mj-ea" }; "minor" -> { latin = "+mn-lt"; ea = "+mn-ea" } }
            }
        }
        val tchain = TextStyleChain(base, fontRef, txBody.child("lstStyle"))

        if (txXfrmOverride != null) {
            val tr = shapeRect(txXfrmOverride, g)
            p.saved {
                p.transform(shapeMatrix(tr, txXfrmOverride, g, forText = true))
                TextRenderer.draw(p, env, txBody, body, tchain, 0.0, 0.0, tr[2], tr[3])
            }
            return
        }
        val tRect = geom.textRect
        p.saved {
            if (body.upright) p.transform(g.matrix * Affine.translate(r[0], r[1])) else p.transform(shapeMatrix(r, xf, g, forText = true))
            TextRenderer.draw(p, env, txBody, body, tchain, tRect[0], tRect[1], tRect[2] - tRect[0], tRect[3] - tRect[1])
        }
    }

    /** spPr가 상속된 개체 틀에서 왔으면 그 파트의 관계로 이미지를 찾아야 한다 */
    private fun partOf(spPr: Element, el: Element, part: String, kind: SheetKind): String {
        if (kind != SheetKind.SLIDE) return part
        var n: org.w3c.dom.Node? = spPr
        while (n != null) {
            if (n === slide) return slidePath
            if (n === layout) return layoutPath ?: part
            if (n === master) return masterPath ?: part
            n = n.parentNode
        }
        if (spPr.ownerDocument === layout?.ownerDocument) return layoutPath ?: part
        if (spPr.ownerDocument === master?.ownerDocument) return masterPath ?: part
        return part
    }

    private fun drawGeometry(geom: Geometry, fill: Fill?, line: LineSpec?, w: Double, h: Double) {
        for (gp in geom.paths) {
            if (gp.fill != "none" && fill != null && fill !is Fill.None) {
                p.saved {
                    p.path(gp.cmds)
                    fills.fillCurrentPath(p, fill, w, h, true, gp.fill)
                }
            }
        }
        val ln = line ?: return
        val color = ln.color() ?: return
        if (ln.fill is Fill.None || color.a <= 0) return
        for (gp in geom.paths) {
            if (!gp.stroke) continue
            p.saved {
                p.setStroke(color)
                p.lineStyle(max(ln.width, 0.25), ln.cap, ln.join, ln.dashArray())
                p.path(gp.cmds)
                p.stroke()
            }
        }
        // 화살표 머리
        if (ln.head != null || ln.tail != null) {
            val gp = geom.paths.firstOrNull { it.stroke } ?: return
            val ends = FillPainter.endpoints(gp.cmds) ?: return
            ln.head?.let { arrowHead(it, ends.first, ln, color) }
            ln.tail?.let { arrowHead(it, ends.second, ln, color) }
        }
    }

    private fun arrowHead(a: ArrowEnd, pt: DoubleArray, ln: LineSpec, color: Rgba) {
        val lw = max(ln.width, 0.75)
        val len = a.len * lw; val wid = a.w * lw
        val (x, y, ang) = Triple(pt[0], pt[1], pt[2])
        val cs = cos(ang); val sn = sin(ang)
        fun pt(l: Double, s: Double) = doubleArrayOf(x - cs * l - sn * s, y - sn * l + cs * s)
        p.saved {
            p.setFill(color); p.setStroke(color)
            when (a.type) {
                "oval" -> {
                    val r = wid / 2
                    val k = 0.5523 * r
                    p.path(listOf(PathCmd.Move(x + r, y), PathCmd.Cubic(x + r, y + k, x + k, y + r, x, y + r),
                        PathCmd.Cubic(x - k, y + r, x - r, y + k, x - r, y), PathCmd.Cubic(x - r, y - k, x - k, y - r, x, y - r),
                        PathCmd.Cubic(x + k, y - r, x + r, y - k, x + r, y), PathCmd.Close))
                    p.fill()
                }
                "diamond" -> {
                    val a1 = pt(len / 2, -wid / 2); val a2 = pt(len, 0.0); val a3 = pt(len / 2, wid / 2)
                    val tip = pt(0.0, 0.0)
                    p.path(listOf(PathCmd.Move(tip[0] - cs * -len / 2, tip[1] - sn * -len / 2), PathCmd.Line(a1[0] + cs * len / 2, a1[1] + sn * len / 2),
                        PathCmd.Line(a2[0] + cs * len / 2, a2[1] + sn * len / 2), PathCmd.Line(a3[0] + cs * len / 2, a3[1] + sn * len / 2), PathCmd.Close))
                    p.fill()
                }
                "arrow" -> {
                    val l = pt(len, -wid / 2); val r = pt(len, wid / 2)
                    p.lineStyle(lw, 1, 1, null)
                    p.path(listOf(PathCmd.Move(l[0], l[1]), PathCmd.Line(x, y), PathCmd.Line(r[0], r[1])))
                    p.stroke()
                }
                else -> { // triangle, stealth
                    val l = pt(len, -wid / 2); val r = pt(len, wid / 2)
                    val cmds = ArrayList<PathCmd>()
                    cmds.add(PathCmd.Move(x, y)); cmds.add(PathCmd.Line(l[0], l[1]))
                    if (a.type == "stealth") pt(len * 0.6, 0.0).let { cmds.add(PathCmd.Line(it[0], it[1])) }
                    cmds.add(PathCmd.Line(r[0], r[1])); cmds.add(PathCmd.Close)
                    p.path(cmds); p.fill()
                }
            }
        }
    }

    // ───────────── 그림 ─────────────
    private fun drawPicture(el: Element, part: String, kind: SheetKind, g: GroupCtx) {
        if (hidden(el)) return
        val inh = inherited(el, kind)
        val chain = inh + el
        val spPrs = chain.mapNotNull { it.child("spPr") }
        val xf = spPrs.asReversed().firstNotNullOfOrNull { Xfrm.parse(it.child("xfrm")) } ?: return
        val blipFill = el.child("blipFill") ?: return
        val fill = styles.parseFill(blipFill, part, null) as? Fill.Picture ?: return
        val r = shapeRect(xf, g)
        val w = r[2]; val h = r[3]
        val geomSpPr = spPrs.asReversed().firstOrNull { it.child("prstGeom") != null || it.child("custGeom") != null }
        val geom = GeometryEngine.build(geomSpPr, w, h)
        val placed = fills.imageFor(fill.part)
        p.saved {
            p.transform(shapeMatrix(r, xf, g))
            if (placed != null) {
                p.saved {
                    geom.paths.filter { it.fill != "none" }.forEach { p.path(it.cmds) }
                    if (geom.paths.none { it.fill != "none" }) p.rect(0.0, 0.0, w, h)
                    p.clip(true)
                    fills.drawPicture(p, placed, fill, w, h)
                }
            }
            val styleEl = el.child("style")
            var line: LineSpec? = styles.lineRef(styleEl?.child("lnRef"))
            for (sp in spPrs) sp.child("ln")?.let { line = styles.mergeLine(line ?: LineSpec(), it, part) }
            if (line != null) drawGeometry(geom, null, line, w, h)
        }
    }

    // ───────────── 그룹 ─────────────
    private fun drawGroup(el: Element, part: String, kind: SheetKind, g: GroupCtx) {
        if (hidden(el)) return
        val gsp = el.child("grpSpPr")
        val xf = Xfrm.parse(gsp?.child("xfrm"))
        val inner: GroupCtx = if (xf == null) g else {
            val r = shapeRect(xf, g)
            val sx = if (xf.chW > 0) r[2] / xf.chW else 1.0
            val sy = if (xf.chH > 0) r[3] / xf.chH else 1.0
            val rot = g.matrix * Affine.translate(r[0] + r[2] / 2, r[1] + r[3] / 2) * Affine.rotate(xf.rot) *
                Affine.scale(if (xf.flipH) -1.0 else 1.0, if (xf.flipV) -1.0 else 1.0) * Affine.translate(-(r[0] + r[2] / 2), -(r[1] + r[3] / 2))
            val chX = xf.chX; val chY = xf.chY
            GroupCtx(rot, { c -> doubleArrayOf(r[0] + (c[0] - chX) * sx, r[1] + (c[1] - chY) * sy, c[2] * sx, c[3] * sy) },
                styles.fillIn(gsp, part)?.let { if (it is Fill.Group) g.grpFill else it } ?: g.grpFill)
        }
        drawTree(el, part, kind, inner)
    }

    // ───────────── 그래픽 프레임(표, SmartArt, OLE, 차트) ─────────────
    private fun drawFrame(el: Element, part: String, kind: SheetKind, g: GroupCtx) {
        if (hidden(el)) return
        val inh = inherited(el, kind)
        val xf = (listOf(el) + inh.asReversed()).firstNotNullOfOrNull { Xfrm.parse(it.child("xfrm")) } ?: return
        val gd = el.path("graphic", "graphicData") ?: return
        val uri = gd.attr("uri") ?: ""
        val r = shapeRect(xf, g)
        when {
            uri.endsWith("/table") -> gd.child("tbl")?.let { tbl ->
                p.saved {
                    p.transform(shapeMatrix(r, xf, g))
                    tables.draw(tbl, part, r[2], r[3])
                }
            }
            uri.endsWith("/diagram") -> drawDiagram(gd, part, r, xf, g)
            uri.endsWith("/ole") || uri.endsWith("/oleObject") -> {
                val ole = gd.elements().firstNotNullOfOrNull { c ->
                    if (c.ln == "AlternateContent") (c.child("Fallback") ?: c.child("Choice"))?.child("oleObj") else if (c.ln == "oleObj") c else null
                }
                val pic = ole?.child("pic") ?: return
                val blipFill = pic.child("blipFill") ?: return
                val fill = styles.parseFill(blipFill, part, null) as? Fill.Picture ?: return
                val placed = fills.imageFor(fill.part) ?: return
                p.saved {
                    p.transform(shapeMatrix(r, xf, g))
                    p.rect(0.0, 0.0, r[2], r[3]); p.clip()
                    fills.drawPicture(p, placed, fill, r[2], r[3])
                }
            }
            uri.endsWith("/chart") -> drawChartFallback(gd, part, r, xf, g)
        }
    }

    /** SmartArt: PowerPoint가 저장해 둔 그리기 결과(drawingN.xml)를 그대로 그린다 */
    private fun drawDiagram(gd: Element, part: String, r: DoubleArray, xf: Xfrm, g: GroupCtx) {
        val relIds = gd.child("relIds") ?: return
        val dataPart = pkg.relTarget(part, relIds.rAttr("dm"))
        var drawingPart: String? = null
        if (dataPart != null) {
            val data = pkg.xmlUncached(dataPart)
            val extRel = data?.let { d ->
                val all = d.getElementsByTagNameNS("*", "dataModelExt")
                if (all.length > 0) (all.item(0) as Element).attr("relId") else null
            }
            drawingPart = pkg.relTarget(part, extRel)
        }
        if (drawingPart == null) {
            val drawings = pkg.relsOfType(part, "/diagramDrawing")
            if (drawings.size == 1) drawingPart = drawings[0].target
        }
        val drawing = drawingPart?.let { pkg.xml(it) } ?: return
        val tree = drawing.child("spTree") ?: return
        val inner = GroupCtx(g.matrix * Affine.translate(r[0] + r[2] / 2, r[1] + r[3] / 2) * Affine.rotate(xf.rot) * Affine.translate(-(r[0] + r[2] / 2), -(r[1] + r[3] / 2)),
            { c -> doubleArrayOf(r[0] + c[0], r[1] + c[1], c[2], c[3]) }, g.grpFill)
        drawDiagramTree(tree, drawingPart, inner)
    }

    private fun drawDiagramTree(tree: Element, part: String, g: GroupCtx) {
        for (c in tree.elements()) {
            try {
                when (c.ln) {
                    "sp" -> drawShape(c, part, SheetKind.MASTER, g, Xfrm.parse(c.child("txXfrm")))
                    "pic" -> drawPicture(c, part, SheetKind.MASTER, g)
                    "grpSp" -> {
                        val xf = Xfrm.parse(c.child("grpSpPr")?.child("xfrm"))
                        if (xf == null) drawDiagramTree(c, part, g) else {
                            val rr = shapeRect(xf, g)
                            val sx = if (xf.chW > 0) rr[2] / xf.chW else 1.0
                            val sy = if (xf.chH > 0) rr[3] / xf.chH else 1.0
                            drawDiagramTree(c, part, GroupCtx(g.matrix, { q -> doubleArrayOf(rr[0] + (q[0] - xf.chX) * sx, rr[1] + (q[1] - xf.chY) * sy, q[2] * sx, q[3] * sy) }, g.grpFill))
                        }
                    }
                }
            } catch (e: Exception) { platform.log("diagram shape failed", e) }
        }
    }

    /** 차트: 차트 파트에 대체 이미지가 연결돼 있으면 그것을 쓴다(없으면 생략) */
    private fun drawChartFallback(gd: Element, part: String, r: DoubleArray, xf: Xfrm, g: GroupCtx) {
        val chartPart = pkg.relTarget(part, gd.child("chart")?.rAttr("id")) ?: return
        val img = pkg.relsOfType(chartPart, "/image").firstOrNull()?.target ?: run {
            ChartRenderer(p, cc, env, fonts).tryDraw(pkg, chartPart, shapeMatrix(r, xf, g), r[2], r[3])
            return
        }
        val placed = images.load(img) { pkg.bytes(img) } ?: return
        p.saved {
            p.transform(shapeMatrix(r, xf, g))
            p.image(placed.image, 0.0, 0.0, r[2], r[3])
        }
    }
}
