package com.cnumed.mlms.pdfconvert

import com.tom_roush.fontbox.ttf.CmapLookup
import com.tom_roush.fontbox.ttf.TTFParser
import com.tom_roush.fontbox.ttf.TrueTypeCollection
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun normFamily(s: String): String =
    s.lowercase().filterNot { it == ' ' || it == '-' || it == '_' || it == ' ' }

/** 글꼴 파일 하나(한 스타일) */
internal class FaceSource(
    val key: String,
    val families: Set<String>,
    val bold: Boolean,
    val italic: Boolean,
    val load: () -> TrueTypeFont?,
)

/** 문서에 실제로 올라간 글꼴 */
internal class LoadedFace(val source: FaceSource, val ttf: TrueTypeFont, private val doc: PDDocument, private val platform: ConverterPlatform) {
    private val cmap: CmapLookup? = runCatching { ttf.getUnicodeCmapLookup(false) }.getOrNull()
    private val upm = ttf.unitsPerEm.toDouble().takeIf { it > 0 } ?: 1000.0
    val ascent: Double
    val descent: Double
    val underlinePos: Double
    val underlineThickness: Double
    private var pd: PDType0Font? = null
    private var pdFailed = false
    private val widthCache = HashMap<Int, Double>()
    private val gpos: GposKerning? = runCatching { ttf.tableMap["GPOS"]?.let { GposKerning.parse(ttf.getTableBytes(it)) } }.getOrNull()
    private val kernTable = if (gpos != null) null else runCatching { ttf.kerning?.getHorizontalKerningSubtable() }.getOrNull()

    init {
        val os2 = runCatching { ttf.oS2Windows }.getOrNull()
        val hhea = runCatching { ttf.horizontalHeader }.getOrNull()
        var asc = (os2?.winAscent ?: 0).toDouble()
        var desc = (os2?.winDescent ?: 0).toDouble()
        if (asc <= 0) { asc = (hhea?.ascender ?: 800).toDouble(); desc = -(hhea?.descender ?: -200).toDouble() }
        ascent = asc / upm
        descent = desc / upm
        val post = runCatching { ttf.postScript }.getOrNull()
        underlinePos = (post?.underlinePosition?.toDouble() ?: -100.0) / upm
        underlineThickness = ((post?.underlineThickness?.toDouble() ?: 50.0).takeIf { it > 0 } ?: 50.0) / upm
    }

    fun hasGlyph(cp: Int): Boolean = (cmap?.getGlyphId(cp) ?: 0) > 0

    /** em 단위 전진폭 */
    fun advance(cp: Int): Double = widthCache.getOrPut(cp) {
        val gid = cmap?.getGlyphId(cp) ?: 0
        runCatching { ttf.getAdvanceWidth(gid) / upm }.getOrDefault(0.5)
    }

    /** 두 글자 사이 커닝(em 단위, 음수 = 좁힘). kern 표가 없으면 0 */
    fun kern(left: Int, right: Int): Double {
        if (gpos == null && kernTable == null) return 0.0
        val l = cmap?.getGlyphId(left) ?: return 0.0
        val r = cmap.getGlyphId(right)
        if (l == 0 || r == 0) return 0.0
        gpos?.let { return it.kern(l, r) / upm }
        return runCatching { kernTable!!.getKerning(l, r) / upm }.getOrDefault(0.0)
    }

    /** PDF용 글꼴(서브셋 임베딩). 임베딩이 금지된 글꼴이면 null */
    fun pdFont(): PDType0Font? {
        if (pd == null && !pdFailed) {
            pd = runCatching { PDType0Font.load(doc, ttf, true) }
                .onFailure { platform.log("font embed failed: ${source.key}", it); pdFailed = true }
                .getOrNull()
        }
        return pd
    }

    val usable: Boolean get() = cmap != null && pdFont() != null
}

/** 텍스트 조각 하나를 그릴 글꼴 + 합성 굵게/기울임 여부 */
internal class FontChoice(val face: LoadedFace, val synthBold: Boolean, val synthItalic: Boolean)

/**
 * 글꼴 선택 순서
 *  1) PPTX에 포함(embed)된 글꼴
 *  2) 사용자가 설정에서 추가한 글꼴
 *  3) 번들 글꼴 — 이름이 같은 것, 또는 폭이 같은(metric-compatible) 대체 글꼴
 *     Calibri→Carlito, Cambria→Caladea, Arial→Liberation Sans, Times New Roman→Liberation Serif,
 *     Courier New→Liberation Mono, 바탕·명조 계열→나눔명조, 굴림·돋움 계열→나눔고딕
 *  4) 맑은 고딕 (최종 폴백) → 기타 번들 → 기기 내장 글꼴 — 글자 단위로 글리프가 있는 것을 찾는다
 */
internal class FontManager(
    private val doc: PDDocument,
    private val platform: ConverterPlatform,
    embedded: List<FaceSource>,
) {
    private val sources = ArrayList<FaceSource>()
    private val byFamily = HashMap<String, MutableList<FaceSource>>()
    private val loaded = HashMap<String, LoadedFace?>()
    private val choiceCache = HashMap<String, List<FontChoice>>()
    private val systemFallbacks: List<FaceSource>

    init {
        embedded.forEach(::register)
        platform.userFonts().forEach { info ->
            register(FaceSource("user:${info.file.name}:${info.psName}", info.families.map(::normFamily).toSet(), info.bold, info.italic) {
                loadFile(info.file, info.psName)
            })
        }
        BUNDLED.forEach { b ->
            register(FaceSource("asset:${b.path}", b.families.map(::normFamily).toSet(), b.bold, b.italic) {
                platform.openAsset(b.path)?.use { TTFParser().parse(it) }
            })
        }
        systemFallbacks = platform.systemFontFiles().map { f ->
            FaceSource("sys:${f.name}", emptySet(), false, false) { loadFile(f, null) }
        }
    }

    private fun register(s: FaceSource) {
        sources.add(s)
        s.families.forEach { byFamily.getOrPut(it) { ArrayList() }.add(s) }
    }

    private fun loadFile(f: File, psName: String?): TrueTypeFont? {
        if (psName != null || f.name.endsWith(".ttc", true)) {
            val coll = TrueTypeCollection(f)
            var found: TrueTypeFont? = null
            coll.processAllFonts { ttf -> if (found == null && (psName == null || ttf.name == psName)) found = ttf }
            return found
        }
        return TTFParser().parse(f)
    }

    private fun face(s: FaceSource): LoadedFace? = loaded.getOrPut(s.key) {
        runCatching { s.load()?.let { LoadedFace(s, it, doc, platform) } }
            .onFailure { platform.log("font load failed: ${s.key}", it) }
            .getOrNull()?.takeIf { it.usable }
    }

    /** 한 패밀리에서 요청 스타일에 가장 가까운 면 */
    private fun pick(family: String, bold: Boolean, italic: Boolean, heavy: Boolean): FontChoice? {
        val list = byFamily[family] ?: return null
        val ranked = list.sortedBy { (if (it.bold != bold) 2 else 0) + (if (it.italic != italic) 1 else 0) }
        for (s in ranked) {
            val f = face(s) ?: continue
            return FontChoice(f, synthBold = (bold || heavy) && !s.bold, synthItalic = italic && !s.italic)
        }
        return null
    }

    /**
     * 요청 글꼴 이름에 대해 [주 글꼴, 폴백들…] 순서의 후보 목록.
     * 렌더러는 글자마다 앞에서부터 글리프가 있는 첫 후보를 쓴다.
     */
    fun choices(requested: String?, bold: Boolean, italic: Boolean): List<FontChoice> {
        val key = "${requested ?: ""}|$bold|$italic"
        return choiceCache.getOrPut(key) {
            val req = normFamily(requested ?: "")
            val heavy = HEAVY_HINTS.any { req.contains(it) }
            val fams = LinkedHashSet<String>()
            if (req.isNotEmpty()) {
                fams.add(req)
                // "맑은 고딕 Bold" 같이 스타일이 이름에 붙은 경우
                STYLE_SUFFIXES.forEach { suf -> if (req.endsWith(suf) && req.length > suf.length) fams.add(req.removeSuffix(suf)) }
                fams.addAll(substitutes(req))
            }
            fams.addAll(FINAL_FALLBACKS)
            val out = ArrayList<FontChoice>()
            val seen = HashSet<String>()
            for (fam in fams) {
                val c = pick(fam, bold, italic, heavy && fam != req) ?: continue
                if (seen.add(c.face.source.key)) out.add(c)
            }
            for (s in systemFallbacks) {
                val f = face(s) ?: continue
                if (seen.add(s.key)) out.add(FontChoice(f, bold, italic))
            }
            out
        }
    }

    companion object {
        private class Bundled(val path: String, val families: List<String>, val bold: Boolean = false, val italic: Boolean = false)

        private val MALGUN = listOf("Malgun Gothic", "맑은 고딕")
        private val NANUM_GOTHIC = listOf("NanumGothic", "나눔고딕")
        private val NANUM_MYEONGJO = listOf("NanumMyeongjo", "나눔명조")

        private fun four(prefix: String, fam: List<String>) = listOf(
            Bundled("fonts/$prefix-Regular.ttf", fam),
            Bundled("fonts/$prefix-Bold.ttf", fam, bold = true),
            Bundled("fonts/$prefix-Italic.ttf", fam, italic = true),
            Bundled("fonts/$prefix-BoldItalic.ttf", fam, bold = true, italic = true),
        )

        private val BUNDLED: List<Bundled> = listOf(
            Bundled("fonts/ms/malgun.ttf", MALGUN),
            Bundled("fonts/ms/malgunbd.ttf", MALGUN, bold = true),
            Bundled("fonts/ms/malgunsl.ttf", listOf("Malgun Gothic Semilight", "맑은 고딕 Semilight")),
            Bundled("fonts/NanumGothic-Regular.ttf", NANUM_GOTHIC),
            Bundled("fonts/NanumGothic-Bold.ttf", NANUM_GOTHIC, bold = true),
            Bundled("fonts/NanumGothic-ExtraBold.ttf", listOf("NanumGothic ExtraBold", "나눔고딕 ExtraBold", "NanumGothicExtraBold")),
            Bundled("fonts/NanumGothic-ExtraBold.ttf", listOf("NanumGothic Heavy"), bold = true),
            Bundled("fonts/NanumMyeongjo-Regular.ttf", NANUM_MYEONGJO),
            Bundled("fonts/NanumMyeongjo-Bold.ttf", NANUM_MYEONGJO, bold = true),
            Bundled("fonts/NanumMyeongjo-ExtraBold.ttf", listOf("NanumMyeongjo ExtraBold", "나눔명조 ExtraBold", "NanumMyeongjoExtraBold")),
        ) + four("Carlito", listOf("Carlito")) +
            four("Caladea", listOf("Caladea")) +
            four("LiberationSans", listOf("Liberation Sans")) +
            four("LiberationSerif", listOf("Liberation Serif")) +
            four("LiberationMono", listOf("Liberation Mono"))

        private val STYLE_SUFFIXES = listOf("bold", "regular", "light", "medium", "semibold", "italic")
        private val HEAVY_HINTS = listOf("black", "heavy", "extrabold", "ultrabold", "헤드라인", "견고딕", "견명조", "굵은", "엑스트라볼드")

        private val SANS = "liberationsans"; private val SERIF = "liberationserif"; private val MONO = "liberationmono"
        private val NG = "나눔고딕"; private val NM = "나눔명조"; private val MG = "맑은고딕"

        private val FINAL_FALLBACKS = listOf(MG, SANS, NG, NM, "carlito", SERIF)

        private val ALIASES: Map<String, List<String>> = buildMap {
            fun put(targets: List<String>, vararg names: String) = names.forEach { put(normFamily(it), targets) }
            put(listOf("carlito", SANS), "Calibri", "Calibri Light", "Calibri Body", "Calibri Bold")
            put(listOf("caladea", SERIF), "Cambria", "Cambria Math")
            put(listOf(SANS), "Arial", "Arial MT", "Helvetica", "Helvetica Neue", "Arial Unicode MS", "Arial Narrow", "Arial Black",
                "Segoe UI", "Segoe UI Light", "Segoe UI Semibold", "Segoe UI Semilight", "Verdana", "Tahoma", "Trebuchet MS",
                "Gill Sans MT", "Gill Sans", "Century Gothic", "Franklin Gothic Book", "Franklin Gothic Medium", "Futura",
                "Lucida Sans", "Lucida Sans Unicode", "Microsoft Sans Serif", "Open Sans", "Roboto", "Candara", "Corbel",
                "Lato", "Montserrat", "Source Sans Pro", "Avenir", "Avenir Next", "Myriad Pro", "Frutiger", "Univers")
            put(listOf(SERIF), "Times New Roman", "Times", "Georgia", "Garamond", "Book Antiqua", "Palatino Linotype", "Palatino",
                "Century Schoolbook", "Bookman Old Style", "Constantia", "Century", "Baskerville", "Bodoni MT", "Minion Pro")
            put(listOf(MONO), "Courier New", "Courier", "Consolas", "Lucida Console", "Monaco", "Menlo", "Source Code Pro")
            put(listOf(NG, MG), "굴림", "Gulim", "굴림체", "GulimChe", "돋움", "Dotum", "돋움체", "DotumChe",
                "나눔바른고딕", "NanumBarunGothic", "나눔스퀘어", "NanumSquare", "나눔스퀘어라운드", "NanumSquareRound",
                "나눔스퀘어 ExtraBold", "NanumSquare ExtraBold", "나눔바른고딕 Light", "NanumGothicCoding", "나눔고딕코딩",
                "함초롬돋움", "HCR Dotum", "한컴돋움", "HY중고딕", "HY견고딕", "HY헤드라인M", "HY그래픽M", "휴먼고딕",
                "Noto Sans KR", "Noto Sans CJK KR", "본고딕", "Source Han Sans KR", "Pretendard", "Spoqa Han Sans",
                "Spoqa Han Sans Neo", "에스코어 드림", "S-Core Dream", "G마켓 산스", "Gmarket Sans", "서울남산체", "서울한강체",
                "KoPub돋움체 Medium", "KoPub돋움체 Bold", "KoPub돋움체 Light", "KoPubDotum Medium", "a고딕", "윤고딕", "Apple SD Gothic Neo")
            put(listOf(NM, MG), "바탕", "Batang", "바탕체", "BatangChe", "궁서", "Gungsuh", "궁서체", "GungsuhChe",
                "함초롬바탕", "HCR Batang", "한컴바탕", "HY신명조", "HY견명조", "신명조", "휴먼명조", "한양신명조",
                "Noto Serif KR", "Noto Serif CJK KR", "본명조", "Source Han Serif KR", "KoPub바탕체 Medium", "KoPub바탕체 Light",
                "KoPub바탕체 Bold", "KoPubBatang Medium", "나눔명조 옛한글")
            put(listOf(MG, NG), "MS Gothic", "MS PGothic", "Meiryo", "Yu Gothic", "SimHei", "Microsoft YaHei",
                "Microsoft JhengHei", "新細明體", "PMingLiU", "MingLiU", "SimSun", "MS Mincho", "MS PMincho")
        }

        /** 번들 글꼴 이름 또는 알려진 대체 글꼴이 있는 이름인지 */
        fun hasBundledOrAlias(name: String): Boolean {
            val n = normFamily(name)
            return ALIASES.containsKey(n) || BUNDLED.any { b -> b.families.any { normFamily(it) == n } } || SymbolFonts.isSymbolFont(name)
        }

        fun substitutes(req: String): List<String> {
            ALIASES[req]?.let { return it }
            // 이름 규칙으로 계열 추정
            val serifKo = listOf("명조", "바탕", "궁서", "myeongjo", "batang", "mincho", "song", "ming")
            val sansKo = listOf("고딕", "돋움", "굴림", "gothic", "dotum", "gulim", "헤드라인")
            val hasHangul = req.any { it in '가'..'힣' || it in 'ㄱ'..'ㆎ' }
            return when {
                serifKo.any { req.contains(it) } -> listOf(NM, MG)
                sansKo.any { req.contains(it) } -> listOf(NG, MG)
                hasHangul -> listOf(MG)
                listOf("mono", "courier", "code", "console").any { req.contains(it) } -> listOf(MONO)
                listOf("times", "roman", "serif", "garamond", "georgia", "book", "century", "bodoni", "baskerville", "minion", "palatino")
                    .any { req.contains(it) } && !req.contains("sans") -> listOf(SERIF)
                else -> listOf(SANS)
            }
        }

        /**
         * PPTX에 포함된 글꼴(.fntdata, Embedded OpenType)을 TTF로 푼다.
         * MTX 압축이 걸린 것은 지원하지 않는다(null).
         */
        fun decodeEmbeddedFont(data: ByteArray): ByteArray? {
            if (data.size < 16) return null
            val be = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getInt(0)
            if (be == 0x00010000 || be == 0x74727565 /*true*/) return data
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val eotSize = bb.getInt(0)
            val fontSize = bb.getInt(4)
            val flags = bb.getInt(12)
            if (data.size < 36 || (bb.getShort(34).toInt() and 0xFFFF) != 0x504C) return null
            if (flags and 0x4 != 0) return null // MTX 압축
            val off = eotSize - fontSize
            if (off < 0 || off + fontSize > data.size || fontSize <= 0) return null
            val out = data.copyOfRange(off, off + fontSize)
            if (flags and 0x10000000 != 0) for (i in out.indices) out[i] = (out[i].toInt() xor 0x50).toByte()
            return out
        }

        fun parseTtf(bytes: ByteArray): TrueTypeFont = TTFParser().parse(ByteArrayInputStream(bytes))
    }
}

/** 사용자 글꼴 가져오기용: 글꼴 파일에서 패밀리 이름(한/영)과 스타일을 읽는다. */
object FontInspector {
    class Info(val psName: String?, val families: List<String>, val bold: Boolean, val italic: Boolean)

    /** @throws IllegalArgumentException 지원하지 않는 글꼴(CFF 기반 OTF 등) */
    fun inspect(file: File): List<Info> {
        val out = ArrayList<Info>()
        if (file.name.endsWith(".ttc", true) || file.name.endsWith(".otc", true)) {
            TrueTypeCollection(file).processAllFonts { ttf -> describe(ttf, ttf.name)?.let(out::add) }
        } else {
            val ttf = TTFParser().parse(file)
            try { describe(ttf, null)?.let(out::add) } finally { ttf.close() }
        }
        if (out.isEmpty()) throw IllegalArgumentException("지원하지 않는 글꼴 형식입니다 (TrueType 윤곽선 글꼴만 가능)")
        return out
    }

    private fun describe(ttf: TrueTypeFont, psName: String?): Info? {
        if (ttf.tableMap.containsKey("CFF ") || !ttf.tableMap.containsKey("glyf")) return null
        val names = LinkedHashSet<String>()
        runCatching {
            ttf.naming.nameRecords.forEach { r ->
                if (r.nameId == 1 || r.nameId == 16 || r.nameId == 4) r.string?.trim()?.takeIf { it.isNotEmpty() }?.let(names::add)
            }
        }
        val head = runCatching { ttf.header }.getOrNull()
        val os2 = runCatching { ttf.oS2Windows }.getOrNull()
        val macStyle = head?.macStyle ?: 0
        val bold = (macStyle and 1) != 0 || (os2?.weightClass ?: 400) >= 600
        val italic = (macStyle and 2) != 0
        if (names.isEmpty()) return null
        return Info(psName, names.toList(), bold, italic)
    }
}
