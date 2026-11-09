package com.cnumed.mlms.pdfconvert

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.w3c.dom.Element
import java.io.File

class ConversionResult(val pages: Int, val skippedHidden: Int, val failedSlides: List<Int>)

class ConversionCancelledException : Exception("변환이 취소되었습니다")

/**
 * PPTX → PDF 변환기.
 * 슬라이드 크기를 그대로 PDF 페이지 크기로 쓰고(1 EMU = 1/12700 pt), 글자는 실제 글꼴을 서브셋 임베딩한
 * 벡터 텍스트로, 그림은 원본 데이터 그대로(JPEG) 또는 무손실(그 외)로 넣는다.
 */
class PptxPdfConverter(private val platform: ConverterPlatform) {
    private var builtinStyles: Map<String, Element>? = null

    fun convert(
        input: File,
        output: File,
        includeHidden: Boolean = false,
        ocr: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): ConversionResult {
        if (GeometryEngine.preset("rect") == null) {
            platform.openAsset("pptx/presetShapeDefinitions.xml")?.use { GeometryEngine.loadPresets(it.readBytes()) }
        }
        OpcPackage(input).use { pkg ->
            val presPath = pkg.relsOfType("", "/officeDocument").firstOrNull()?.target ?: "ppt/presentation.xml"
            val pres = pkg.xml(presPath) ?: throw IllegalArgumentException("PowerPoint(.pptx) 파일이 아닙니다")
            val sz = pres.child("sldSz")
            val slideW = emu(sz?.long("cx") ?: 12192000L)
            val slideH = emu(sz?.long("cy") ?: 6858000L)
            val rels = pkg.rels(presPath)
            val slidePaths = pres.child("sldIdLst")?.children("sldId")?.mapNotNull { rels[it.rAttr("id")]?.target } ?: emptyList()

            val tableStyles = HashMap<String, Element>()
            // PowerPoint 기본 제공 표 스타일 74종 — 파일에 정의가 빠져 있어도 같은 모양으로 그린다
            builtinTableStyles().forEach { (k, v) -> tableStyles[k] = v }
            pkg.relsOfType(presPath, "/tableStyles").firstOrNull()?.let { r ->
                pkg.xml(r.target)?.children("tblStyle")?.forEach { s -> s.attr("styleId")?.let { tableStyles[it] = s } }
            }
            val info = PresInfo(pres.child("defaultTextStyle"), tableStyles)

            val mem = MemoryUsageSetting.setupTempFileOnly().setTempDir(platform.tempDir())
            PDDocument(mem).use { doc ->
                val fonts = FontManager(doc, platform, embeddedFonts(pkg, pres, presPath))
                val images = ImageLoader(doc, platform)
                val ocrOverlay = if (ocr) OcrOverlay(images, fonts) else null
                val themes = HashMap<String, Theme>()
                val failed = ArrayList<Int>()
                var hidden = 0
                var pages = 0
                slidePaths.forEachIndexed { i, slidePath ->
                    if (isCancelled()) throw ConversionCancelledException()
                    onProgress(i, slidePaths.size)
                    val slide = pkg.xml(slidePath) ?: return@forEachIndexed
                    if (!includeHidden && slide.attr("show") == "0") { hidden++; return@forEachIndexed }
                    val layoutPath = pkg.relsOfType(slidePath, "/slideLayout").firstOrNull()?.target
                    val masterPath = layoutPath?.let { pkg.relsOfType(it, "/slideMaster").firstOrNull()?.target }
                    val themePath = masterPath?.let { pkg.relsOfType(it, "/theme").firstOrNull()?.target }
                    val theme = themes.getOrPut(themePath ?: "") { Theme(themePath?.let { pkg.xml(it) }) }

                    val page = PDPage(PDRectangle(slideW.toFloat(), slideH.toFloat()))
                    doc.addPage(page)
                    pages++
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { cs ->
                        val painter = Painter(cs, slideH)
                        try {
                            SlideRenderer(pkg, painter, slidePath, slide, layoutPath, layoutPath?.let { pkg.xml(it) },
                                masterPath, masterPath?.let { pkg.xml(it) }, theme, info, fonts, images, i + 1, slideW, slideH, platform, ocrOverlay).render()
                        } catch (e: Exception) {
                            platform.log("slide ${i + 1} failed", e); failed.add(i + 1)
                        }
                    }
                }
                onProgress(slidePaths.size, slidePaths.size)
                docTitle(pkg)?.let { doc.documentInformation.title = it }
                doc.documentInformation.creator = "MLMS PPTX→PDF"
                doc.save(output)
                return ConversionResult(pages, hidden, failed)
            }
        }
    }

    /**
     * 기기에 없어서 Google Fonts에서 받아 볼 만한 글꼴 이름들
     * (PPTX 포함 글꼴·사용자 글꼴·번들 글꼴·알려진 대체 글꼴이 있는 것은 제외)
     */
    fun missingFontFamilies(input: File): Set<String> {
        val used = LinkedHashSet<String>()
        OpcPackage(input).use { pkg ->
            val presPath = pkg.relsOfType("", "/officeDocument").firstOrNull()?.target ?: "ppt/presentation.xml"
            val pres = pkg.xml(presPath) ?: return emptySet()
            val embedded = pres.child("embeddedFontLst")?.children("embeddedFont")?.mapNotNull { it.child("font")?.attr("typeface") }?.map(::normFamily)?.toSet() ?: emptySet()
            val parts = HashSet<String>()
            val slides = pkg.rels(presPath).values.filter { it.type.endsWith("/slide") }.map { it.target }
            for (s in slides) {
                parts.add(s)
                pkg.relsOfType(s, "/slideLayout").forEach { l -> parts.add(l.target); pkg.relsOfType(l.target, "/slideMaster").forEach { m -> parts.add(m.target); pkg.relsOfType(m.target, "/theme").forEach { parts.add(it.target) } } }
            }
            // 테마의 언어별(script) 글꼴 목록은 실제로 쓰이지 않는 경우가 대부분이라 제외한다
            val re = Regex("<a:(?:latin|ea|cs|buFont|sym)\\s[^>]*?typeface=\"([^\"]+)\"")
            for (p in parts) {
                val text = pkg.bytes(p)?.toString(Charsets.UTF_8) ?: continue
                re.findAll(text).forEach { m -> val f = m.groupValues[1].trim(); if (f.isNotEmpty() && !f.startsWith("+")) used.add(f) }
            }
            val user = platform.userFonts().flatMap { it.families }.map(::normFamily).toSet()
            return used.filter { f ->
                val n = normFamily(f)
                n !in embedded && n !in user && !FontManager.hasBundledOrAlias(f)
            }.toSet()
        }
    }

    private fun builtinTableStyles(): Map<String, Element> = builtinStyles ?: run {
        val m = HashMap<String, Element>()
        runCatching {
            platform.openAsset("pptx/builtinTableStyles.xml")?.use { Xml.parse(it.readBytes()) }
                ?.children("tblStyle")?.forEach { s -> s.attr("styleId")?.let { m[it] = s } }
        }
        builtinStyles = m
        m
    }

    private fun docTitle(pkg: OpcPackage): String? {
        val core = pkg.relsOfType("", "/core-properties").firstOrNull()?.target ?: "docProps/core.xml"
        return pkg.xml(core)?.child("title")?.textContent?.trim()?.ifEmpty { null }
    }

    /** presentation.xml의 embeddedFontLst → 글꼴 소스 */
    private fun embeddedFonts(pkg: OpcPackage, pres: Element, presPath: String): List<FaceSource> {
        val out = ArrayList<FaceSource>()
        pres.child("embeddedFontLst")?.children("embeddedFont")?.forEach { ef ->
            val face = ef.child("font")?.attr("typeface") ?: return@forEach
            listOf("regular" to (false to false), "bold" to (true to false), "italic" to (false to true), "boldItalic" to (true to true))
                .forEach { (tag, style) ->
                    val target = pkg.relTarget(presPath, ef.child(tag)?.rAttr("id")) ?: return@forEach
                    out.add(FaceSource("embed:$target", setOf(normFamily(face)), style.first, style.second) {
                        pkg.bytes(target)?.let { FontManager.decodeEmbeddedFont(it) }?.let { FontManager.parseTtf(it) }
                    })
                }
        }
        return out
    }
}
