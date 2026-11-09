package com.cnumed.mlms.pdfconvert

import org.w3c.dom.Element
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** sRGB 0..1 + 알파 */
internal data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double = 1.0) {
    fun withAlpha(alpha: Double) = copy(a = alpha.coerceIn(0.0, 1.0))
    fun mulAlpha(f: Double) = copy(a = (a * f).coerceIn(0.0, 1.0))

    companion object {
        val BLACK = Rgba(0.0, 0.0, 0.0)
        val WHITE = Rgba(1.0, 1.0, 1.0)
        fun hex(s: String): Rgba? {
            val h = s.trim().removePrefix("#")
            if (h.length != 6) return null
            val v = h.toIntOrNull(16) ?: return null
            return Rgba(((v shr 16) and 255) / 255.0, ((v shr 8) and 255) / 255.0, (v and 255) / 255.0)
        }
    }
}

internal class Theme(root: Element?) {
    val colors = HashMap<String, Rgba>()
    var majorLatin = "Calibri Light"; var minorLatin = "Calibri"
    var majorEa = ""; var minorEa = ""
    var majorCs = ""; var minorCs = ""
    val majorScripts = HashMap<String, String>()
    val minorScripts = HashMap<String, String>()
    val fillStyles = ArrayList<Element>()
    val lineStyles = ArrayList<Element>()
    val bgFillStyles = ArrayList<Element>()

    init {
        val te = root?.child("themeElements")
        te?.child("clrScheme")?.elements()?.forEach { c ->
            val col = c.elements().firstOrNull() ?: return@forEach
            val rgb = when (col.ln) {
                "srgbClr" -> col.attr("val")?.let { Rgba.hex(it) }
                "sysClr" -> (col.attr("lastClr") ?: if (col.attr("val") == "window") "FFFFFF" else "000000").let { Rgba.hex(it) }
                else -> null
            }
            if (rgb != null) colors[c.ln] = rgb
        }
        te?.child("fontScheme")?.let { fs ->
            fs.child("majorFont")?.let { mf ->
                mf.child("latin")?.attr("typeface")?.let { majorLatin = it }
                majorEa = mf.child("ea")?.attr("typeface") ?: ""
                majorCs = mf.child("cs")?.attr("typeface") ?: ""
                mf.children("font").forEach { f -> majorScripts[f.attr("script") ?: ""] = f.attr("typeface") ?: "" }
            }
            fs.child("minorFont")?.let { mf ->
                mf.child("latin")?.attr("typeface")?.let { minorLatin = it }
                minorEa = mf.child("ea")?.attr("typeface") ?: ""
                minorCs = mf.child("cs")?.attr("typeface") ?: ""
                mf.children("font").forEach { f -> minorScripts[f.attr("script") ?: ""] = f.attr("typeface") ?: "" }
            }
        }
        te?.child("fmtScheme")?.let { fmt ->
            fmt.child("fillStyleLst")?.elements()?.let { fillStyles.addAll(it) }
            fmt.child("lnStyleLst")?.elements()?.let { lineStyles.addAll(it) }
            fmt.child("bgFillStyleLst")?.elements()?.let { bgFillStyles.addAll(it) }
        }
    }

    /** +mj-lt 같은 테마 글꼴 토큰 해석. 한글 문서는 ea가 비고 script="Hang"에 들어있는 경우가 많다. */
    fun resolveTypeface(face: String?): String? {
        if (face == null) return null
        return when (face) {
            "+mj-lt" -> majorLatin
            "+mn-lt" -> minorLatin
            "+mj-ea" -> majorEa.ifEmpty { majorScripts["Hang"] ?: "" }.ifEmpty { null }
            "+mn-ea" -> minorEa.ifEmpty { minorScripts["Hang"] ?: "" }.ifEmpty { null }
            "+mj-cs" -> majorCs.ifEmpty { null }
            "+mn-cs" -> minorCs.ifEmpty { null }
            else -> face.ifEmpty { null }
        }
    }
}

/** 색 해석 컨텍스트: 테마 + 슬라이드의 색 매핑(clrMap) */
internal class ColorContext(val theme: Theme, private val clrMap: Map<String, String>) {

    fun schemeColor(name: String): Rgba? {
        val mapped = clrMap[name] ?: when (name) {
            "bg1" -> "lt1"; "tx1" -> "dk1"; "bg2" -> "lt2"; "tx2" -> "dk2"
            else -> name
        }
        return theme.colors[mapped]
    }

    /** a:srgbClr 등의 색 요소 하나를 해석한다. */
    fun resolve(el: Element, phClr: Rgba? = null): Rgba? {
        val base: Rgba = when (el.ln) {
            "srgbClr" -> el.attr("val")?.let { Rgba.hex(it) }
            "schemeClr" -> {
                val v = el.attr("val") ?: return null
                if (v == "phClr") phClr ?: Rgba.BLACK else schemeColor(v)
            }
            "sysClr" -> (el.attr("lastClr") ?: if (el.attr("val") == "window") "FFFFFF" else "000000").let { Rgba.hex(it) }
            "prstClr" -> PRESET_COLORS[el.attr("val") ?: ""]?.let { Rgba.hex(it) }
            "scrgbClr" -> {
                fun lin(p: Double) = if (p <= 0.0031308) p * 12.92 else 1.055 * p.pow(1 / 2.4) - 0.055
                Rgba(lin(el.pct("r") ?: 0.0), lin(el.pct("g") ?: 0.0), lin(el.pct("b") ?: 0.0))
            }
            "hslClr" -> hslToRgb((el.long("hue") ?: 0) / 60000.0, el.pct("sat") ?: 0.0, el.pct("lum") ?: 0.0, 1.0)
            else -> null
        } ?: return null
        return applyMods(base, el)
    }

    /** 부모 요소(solidFill 등) 안의 첫 색 요소를 해석 */
    fun colorIn(parent: Element?, phClr: Rgba? = null): Rgba? {
        parent ?: return null
        for (c in parent.elements()) if (c.ln in COLOR_TAGS) return resolve(c, phClr)
        return null
    }

    private fun applyMods(c0: Rgba, el: Element): Rgba {
        var c = c0
        for (m in el.elements()) {
            val v = m.pct("val") ?: 0.0
            c = when (m.ln) {
                "alpha" -> c.withAlpha(v)
                "alphaMod" -> c.withAlpha(c.a * v)
                "alphaOff" -> c.withAlpha(c.a + v)
                "lumMod", "lumOff", "satMod", "satOff", "sat", "lum", "hueMod", "hueOff", "hue" -> {
                    val hsl = rgbToHsl(c)
                    var (h, s, l) = hsl
                    when (m.ln) {
                        "lumMod" -> l *= v
                        "lumOff" -> l += v
                        "lum" -> l = v
                        "satMod" -> s *= v
                        "satOff" -> s += v
                        "sat" -> s = v
                        "hueMod" -> h *= v
                        "hueOff" -> h += (m.long("val") ?: 0) / 60000.0
                        "hue" -> h = (m.long("val") ?: 0) / 60000.0
                    }
                    hslToRgb(h, s.coerceIn(0.0, 1.0), l.coerceIn(0.0, 1.0), c.a)
                }
                // tint/shade는 선형 RGB 공간에서 적용한다(LibreOffice/PowerPoint 동작)
                "shade" -> linMap(c) { it * v }
                "tint" -> linMap(c) { 1 - (1 - it) * v }
                "inv" -> Rgba(1 - c.r, 1 - c.g, 1 - c.b, c.a)
                "gray" -> { val y = 0.299 * c.r + 0.587 * c.g + 0.114 * c.b; Rgba(y, y, y, c.a) }
                "comp" -> { val (h, s, l) = rgbToHsl(c); hslToRgb(h + 180, s, l, c.a) }
                "red" -> c.copy(r = v); "green" -> c.copy(g = v); "blue" -> c.copy(b = v)
                "redMod" -> c.copy(r = (c.r * v).coerceIn(0.0, 1.0))
                "greenMod" -> c.copy(g = (c.g * v).coerceIn(0.0, 1.0))
                "blueMod" -> c.copy(b = (c.b * v).coerceIn(0.0, 1.0))
                else -> c
            }
        }
        return c
    }

    companion object {
        val COLOR_TAGS = setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr", "hslClr")

        private fun toLin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        private fun toSrgb(v: Double) = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
        private inline fun linMap(c: Rgba, f: (Double) -> Double) = Rgba(
            toSrgb(f(toLin(c.r)).coerceIn(0.0, 1.0)), toSrgb(f(toLin(c.g)).coerceIn(0.0, 1.0)),
            toSrgb(f(toLin(c.b)).coerceIn(0.0, 1.0)), c.a
        )

        fun rgbToHsl(c: Rgba): Triple<Double, Double, Double> {
            val mx = max(c.r, max(c.g, c.b)); val mn = min(c.r, min(c.g, c.b))
            val l = (mx + mn) / 2
            if (mx == mn) return Triple(0.0, 0.0, l)
            val d = mx - mn
            val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
            val h = when (mx) {
                c.r -> ((c.g - c.b) / d + (if (c.g < c.b) 6 else 0))
                c.g -> ((c.b - c.r) / d + 2)
                else -> ((c.r - c.g) / d + 4)
            } * 60
            return Triple(h, s, l)
        }

        fun hslToRgb(h0: Double, s: Double, l: Double, a: Double): Rgba {
            val h = ((h0 % 360) + 360) % 360 / 360
            if (s == 0.0) return Rgba(l, l, l, a)
            val q = if (l < 0.5) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            fun hue(t0: Double): Double {
                var t = t0
                if (t < 0) t += 1; if (t > 1) t -= 1
                return when {
                    t < 1.0 / 6 -> p + (q - p) * 6 * t
                    t < 0.5 -> q
                    t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                    else -> p
                }
            }
            return Rgba(hue(h + 1.0 / 3), hue(h), hue(h - 1.0 / 3), a)
        }

        val PRESET_COLORS = mapOf(
            "black" to "000000", "white" to "FFFFFF", "red" to "FF0000", "green" to "008000",
            "blue" to "0000FF", "yellow" to "FFFF00", "cyan" to "00FFFF", "magenta" to "FF00FF",
            "gray" to "808080", "grey" to "808080", "darkGray" to "A9A9A9", "dkGray" to "A9A9A9",
            "lightGray" to "D3D3D3", "ltGray" to "D3D3D3", "silver" to "C0C0C0", "maroon" to "800000",
            "navy" to "000080", "olive" to "808000", "purple" to "800080", "teal" to "008080",
            "orange" to "FFA500", "pink" to "FFC0CB", "brown" to "A52A2A", "lime" to "00FF00",
            "gold" to "FFD700", "darkBlue" to "00008B", "dkBlue" to "00008B", "darkRed" to "8B0000",
            "dkRed" to "8B0000", "darkGreen" to "006400", "dkGreen" to "006400", "lightBlue" to "ADD8E6",
            "ltBlue" to "ADD8E6", "lightGreen" to "90EE90", "ltGreen" to "90EE90", "skyBlue" to "87CEEB",
            "violet" to "EE82EE", "indigo" to "4B0082", "beige" to "F5F5DC", "coral" to "FF7F50",
            "crimson" to "DC143C", "khaki" to "F0E68C", "lavender" to "E6E6FA", "salmon" to "FA8072",
            "tan" to "D2B48C", "tomato" to "FF6347", "turquoise" to "40E0D0", "wheat" to "F5DEB3",
            "orchid" to "DA70D6", "plum" to "DDA0DD", "steelBlue" to "4682B4", "royalBlue" to "4169E1",
            "darkOrange" to "FF8C00", "dkOrange" to "FF8C00", "forestGreen" to "228B22", "seaGreen" to "2E8B57",
            "slateGray" to "708090", "dimGray" to "696969", "whiteSmoke" to "F5F5F5", "ivory" to "FFFFF0",
            "aqua" to "00FFFF", "fuchsia" to "FF00FF", "chocolate" to "D2691E", "firebrick" to "B22222",
        )
    }
}
