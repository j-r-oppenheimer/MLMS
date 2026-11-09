package com.cnumed.mlms.pdfconvert

import org.w3c.dom.Element
import kotlin.math.max

/** DrawingML 표 (a:tbl) 렌더러. 표 스타일(tableStyles.xml)의 부분 스타일과 셀 서식을 합친다. */
internal class TableRenderer(
    private val p: Painter,
    private val env: TextEnv,
    private val styles: StyleResolver,
    private val fills: FillPainter,
    private val pres: PresInfo,
) {
    private class Cell(val el: Element, val row: Int, val col: Int, val rowSpan: Int, val colSpan: Int)

    private class CellStyle {
        var fill: Fill? = null
        val borders = HashMap<String, LineSpec?>() // left/right/top/bottom
        val run = RunProps()
    }

    fun draw(tbl: Element, part: String, frameW: Double, frameH: Double) {
        val tblPr = tbl.child("tblPr")
        val colW = tbl.child("tblGrid")?.children("gridCol")?.map { emu(it.long("w")) } ?: return
        if (colW.isEmpty()) return
        val rows = tbl.children("tr")
        val nRows = rows.size; val nCols = colW.size
        val style: Element? = tblPr?.child("tableStyle")
            ?: tblPr?.child("tableStyleId")?.textContent?.trim()?.let { pres.tableStyles[it] }
        val flags = mapOf(
            "firstRow" to (tblPr?.bool("firstRow") ?: false), "lastRow" to (tblPr?.bool("lastRow") ?: false),
            "firstCol" to (tblPr?.bool("firstCol") ?: false), "lastCol" to (tblPr?.bool("lastCol") ?: false),
            "bandRow" to (tblPr?.bool("bandRow") ?: false), "bandCol" to (tblPr?.bool("bandCol") ?: false),
        )

        // 셀 목록 (병합으로 가려진 셀 제외)
        val cells = ArrayList<Cell>()
        rows.forEachIndexed { ri, tr ->
            tr.children("tc").forEachIndexed { ci, tc ->
                if (tc.bool("hMerge") == true || tc.bool("vMerge") == true) return@forEachIndexed
                cells.add(Cell(tc, ri, ci, max(1, tc.int("rowSpan") ?: 1), max(1, tc.int("gridSpan") ?: 1)))
            }
        }
        val cellStyles = cells.associateWith { resolveStyle(it, style, flags, nRows, nCols, part) }

        // 행 높이: 지정 높이와 내용 높이 중 큰 값
        val rowH = rows.map { emu(it.long("h")) }.toMutableList()
        for (c in cells) {
            if (c.rowSpan != 1) continue
            val w = (c.col until minOf(nCols, c.col + c.colSpan)).sumOf { colW[it] }
            val need = textHeight(c, cellStyles[c]!!, w)
            if (need > rowH[c.row]) rowH[c.row] = need
        }
        val colX = DoubleArray(nCols + 1).also { for (i in 0 until nCols) it[i + 1] = it[i] + colW[i] }
        val rowY = DoubleArray(nRows + 1).also { for (i in 0 until nRows) it[i + 1] = it[i] + rowH[i] }

        // 표 배경
        val bg = style?.child("tblBg")?.let { b -> styles.fillIn(b, part) ?: styles.fillRef(b.child("fillRef"), part) }
            ?: styles.fillIn(tblPr, part)
        if (bg != null && bg !is Fill.None) p.saved { p.rect(0.0, 0.0, colX[nCols], rowY[nRows]); fills.fillCurrentPath(p, bg, colX[nCols], rowY[nRows], false) }

        fun rectOf(c: Cell): DoubleArray {
            val x0 = colX[c.col]; val x1 = colX[minOf(nCols, c.col + c.colSpan)]
            val y0 = rowY[c.row]; val y1 = rowY[minOf(nRows, c.row + c.rowSpan)]
            return doubleArrayOf(x0, y0, x1 - x0, y1 - y0)
        }
        // 1) 채우기
        for (c in cells) {
            val f = cellStyles[c]!!.fill ?: continue
            if (f is Fill.None) continue
            val r = rectOf(c)
            p.saved { p.transform(Affine.translate(r[0], r[1])); p.rect(0.0, 0.0, r[2], r[3]); fills.fillCurrentPath(p, f, r[2], r[3], false) }
        }
        // 2) 텍스트
        for (c in cells) {
            val txBody = c.el.child("txBody") ?: continue
            if (!TextRenderer.hasText(txBody)) continue
            val r = rectOf(c)
            val body = cellBody(c.el)
            p.saved {
                p.rect(r[0], r[1], r[2], r[3]); p.clip()
                TextRenderer.draw(p, env, txBody, body, chain(cellStyles[c]!!, txBody), r[0], r[1], r[2], r[3])
            }
        }
        // 3) 테두리
        for (c in cells) {
            val r = rectOf(c)
            val st = cellStyles[c]!!
            fun edge(key: String, x0: Double, y0: Double, x1: Double, y1: Double) {
                val ln = st.borders[key] ?: return
                val col = ln.color() ?: return
                if (ln.fill is Fill.None || ln.width <= 0 || col.a <= 0) return
                p.saved {
                    p.setStroke(col); p.lineStyle(ln.width, 0, 0, ln.dashArray())
                    p.path(listOf(PathCmd.Move(x0, y0), PathCmd.Line(x1, y1))); p.stroke()
                }
            }
            edge("top", r[0], r[1], r[0] + r[2], r[1])
            edge("bottom", r[0], r[1] + r[3], r[0] + r[2], r[1] + r[3])
            edge("left", r[0], r[1], r[0], r[1] + r[3])
            edge("right", r[0] + r[2], r[1], r[0] + r[2], r[1] + r[3])
            edge("tl2br", r[0], r[1], r[0] + r[2], r[1] + r[3])
            edge("bl2tr", r[0], r[1] + r[3], r[0] + r[2], r[1])
        }
    }

    private fun cellBody(tc: Element): BodyProps {
        val tcPr = tc.child("tcPr")
        return BodyProps().apply {
            lIns = emu(tcPr?.long("marL") ?: 91440); rIns = emu(tcPr?.long("marR") ?: 91440)
            tIns = emu(tcPr?.long("marT") ?: 45720); bIns = emu(tcPr?.long("marB") ?: 45720)
            anchor = tcPr?.attr("anchor") ?: "t"
            tcPr?.attr("vert")?.let { vert = it }
        }
    }

    private fun chain(st: CellStyle, txBody: Element) =
        TextStyleChain(listOfNotNull(pres.defaultTextStyle), st.run, txBody.child("lstStyle"))

    private fun textHeight(c: Cell, st: CellStyle, w: Double): Double {
        val txBody = c.el.child("txBody") ?: return 0.0
        return TextRenderer.draw(null, env, txBody, cellBody(c.el), chain(st, txBody), 0.0, 0.0, w, 0.0)
    }

    private fun resolveStyle(c: Cell, style: Element?, flags: Map<String, Boolean>, nRows: Int, nCols: Int, part: String): CellStyle {
        val st = CellStyle()
        val lastR = nRows - 1; val lastC = nCols - 1
        val r = c.row; val col = c.col
        val r1 = r + c.rowSpan - 1; val c1 = col + c.colSpan - 1
        if (style != null) {
            // (부분 이름, 영역 r0,r1,c0,c1) — 나중 것이 우선
            val parts = ArrayList<Pair<String, IntArray>>()
            parts.add("wholeTbl" to intArrayOf(0, lastR, 0, lastC))
            val fr = if (flags["firstRow"] == true) 1 else 0
            val lr = if (flags["lastRow"] == true) 1 else 0
            val fc = if (flags["firstCol"] == true) 1 else 0
            val lc = if (flags["lastCol"] == true) 1 else 0
            if (flags["bandCol"] == true && col >= fc && col <= lastC - lc) {
                parts.add((if ((col - fc) % 2 == 0) "band1V" else "band2V") to intArrayOf(0, lastR, col, col))
            }
            if (flags["bandRow"] == true && r >= fr && r <= lastR - lr) {
                parts.add((if ((r - fr) % 2 == 0) "band1H" else "band2H") to intArrayOf(r, r, 0, lastC))
            }
            if (lc == 1 && c1 == lastC) parts.add("lastCol" to intArrayOf(0, lastR, lastC, lastC))
            if (fc == 1 && col == 0) parts.add("firstCol" to intArrayOf(0, lastR, 0, 0))
            if (lr == 1 && r1 == lastR) parts.add("lastRow" to intArrayOf(lastR, lastR, 0, lastC))
            if (fr == 1 && r == 0) parts.add("firstRow" to intArrayOf(0, 0, 0, lastC))
            if (fr == 1 && r == 0 && fc == 1 && col == 0) parts.add("nwCell" to intArrayOf(0, 0, 0, 0))
            if (fr == 1 && r == 0 && lc == 1 && c1 == lastC) parts.add("neCell" to intArrayOf(0, 0, lastC, lastC))
            if (lr == 1 && r1 == lastR && fc == 1 && col == 0) parts.add("swCell" to intArrayOf(lastR, lastR, 0, 0))
            if (lr == 1 && r1 == lastR && lc == 1 && c1 == lastC) parts.add("seCell" to intArrayOf(lastR, lastR, lastC, lastC))

            for ((name, reg) in parts) {
                val pe = style.child(name) ?: continue
                pe.child("tcStyle")?.let { ts ->
                    ts.child("fill")?.let { f -> styles.fillIn(f, part)?.let { st.fill = it } }
                    ts.child("fillRef")?.let { fr2 -> styles.fillRef(fr2, part)?.let { st.fill = it } }
                    ts.child("tcBdr")?.let { bdr ->
                        fun side(key: String, inside: String, isEdge: Boolean) {
                            val el = bdr.child(if (isEdge) key else inside) ?: return
                            st.borders[key] = borderOf(el, part)
                        }
                        side("left", "insideV", col == reg[2])
                        side("right", "insideV", c1 == reg[3])
                        side("top", "insideH", r == reg[0])
                        side("bottom", "insideH", r1 == reg[1])
                        bdr.child("tl2br")?.let { st.borders["tl2br"] = borderOf(it, part) }
                        bdr.child("tr2bl")?.let { st.borders["bl2tr"] = borderOf(it, part) }
                    }
                }
                pe.child("tcTxStyle")?.let { tx ->
                    tx.attr("b")?.let { st.run.bold = it == "on" }
                    tx.attr("i")?.let { st.run.italic = it == "on" }
                    tx.child("fontRef")?.let { fr2 ->
                        when (fr2.attr("idx")) { "major" -> { st.run.latin = "+mj-lt"; st.run.ea = "+mj-ea" }; "minor" -> { st.run.latin = "+mn-lt"; st.run.ea = "+mn-ea" } }
                        styles.cc.colorIn(fr2)?.let { st.run.color = it }
                    }
                    tx.child("font")?.let { f ->
                        f.child("latin")?.attr("typeface")?.let { st.run.latin = it }
                        f.child("ea")?.attr("typeface")?.let { st.run.ea = it }
                    }
                    styles.cc.colorIn(tx)?.let { st.run.color = it }
                }
            }
        }
        // 셀 자체 서식
        val tcPr = c.el.child("tcPr")
        if (tcPr != null) {
            styles.fillIn(tcPr, part)?.let { st.fill = it }
            mapOf("lnL" to "left", "lnR" to "right", "lnT" to "top", "lnB" to "bottom", "lnTlToBr" to "tl2br", "lnBlToTr" to "bl2tr").forEach { (tag, key) ->
                tcPr.child(tag)?.let { st.borders[key] = styles.mergeLine(LineSpec(width = 1.0, fill = Fill.Solid(Rgba.BLACK)), it, part) }
            }
        }
        return st
    }

    private fun borderOf(el: Element, part: String): LineSpec? {
        el.child("ln")?.let { return styles.mergeLine(LineSpec(width = 1.0), it, part) }
        el.child("lnRef")?.let { return styles.lineRef(it) }
        return null
    }
}
