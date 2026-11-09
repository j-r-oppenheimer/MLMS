package com.cnumed.mlms.pdfconvert

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

internal const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

// ───────────────────────── XML 헬퍼 (DOM, 로컬 이름 기준) ─────────────────────────

internal object Xml {
    private val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }

    fun parse(bytes: ByteArray): Element = synchronized(factory) {
        factory.newDocumentBuilder()
    }.parse(ByteArrayInputStream(bytes)).documentElement
}

internal val Element.ln: String get() = localName ?: nodeName.substringAfter(':')

internal fun Element.child(name: String): Element? {
    var n: Node? = firstChild
    while (n != null) {
        if (n is Element && n.ln == name) return n
        n = n.nextSibling
    }
    return null
}

internal fun Element.children(name: String): List<Element> {
    val out = ArrayList<Element>()
    var n: Node? = firstChild
    while (n != null) {
        if (n is Element && n.ln == name) out.add(n)
        n = n.nextSibling
    }
    return out
}

internal fun Element.elements(): List<Element> {
    val out = ArrayList<Element>()
    var n: Node? = firstChild
    while (n != null) {
        if (n is Element) out.add(n)
        n = n.nextSibling
    }
    return out
}

internal fun Element.path(vararg names: String): Element? {
    var cur: Element = this
    for (nm in names) cur = cur.child(nm) ?: return null
    return cur
}

/** 접두어 없는 속성. 없으면 null. */
internal fun Element.attr(name: String): String? = if (hasAttribute(name)) getAttribute(name) else null

internal fun Element.rAttr(name: String): String? =
    getAttributeNS(NS_R, name).takeIf { it.isNotEmpty() }

internal fun Element.long(name: String): Long? = attr(name)?.trim()?.toLongOrNull()
internal fun Element.int(name: String): Int? = attr(name)?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
internal fun Element.bool(name: String): Boolean? = attr(name)?.let { it == "1" || it.equals("true", true) || it == "on" }

/** "50%" 같은 퍼센트 문자열 또는 1/1000% 정수 → 비율(1.0 = 100%) */
internal fun Element.pct(name: String): Double? {
    val v = attr(name)?.trim() ?: return null
    return if (v.endsWith("%")) v.dropLast(1).toDoubleOrNull()?.div(100.0) else v.toDoubleOrNull()?.div(100000.0)
}

internal const val EMU_PER_PT = 12700.0
internal fun emu(v: Long?): Double = (v ?: 0L) / EMU_PER_PT

// ───────────────────────── OPC 패키지 (zip + 관계) ─────────────────────────

internal class Rel(val id: String, val type: String, val target: String, val external: Boolean)

internal class OpcPackage(file: File) : Closeable {
    private val zip = ZipFile(file)
    private val entries: Map<String, ZipEntry> =
        zip.entries().asSequence().associateBy { it.name.trimStart('/').lowercase() }
    private val xmlCache = HashMap<String, Element?>()
    private val relCache = HashMap<String, Map<String, Rel>>()

    fun exists(path: String) = entries.containsKey(path.trimStart('/').lowercase())

    fun bytes(path: String): ByteArray? {
        val e = entries[path.trimStart('/').lowercase()] ?: return null
        return zip.getInputStream(e).use { it.readBytes() }
    }

    fun xml(path: String): Element? = xmlCache.getOrPut(path) {
        bytes(path)?.let { runCatching { Xml.parse(it) }.getOrNull() }
    }

    /** 자주 안 쓰는 큰 XML(예: SmartArt 데이터)은 캐시하지 않고 읽는다. */
    fun xmlUncached(path: String): Element? = bytes(path)?.let { runCatching { Xml.parse(it) }.getOrNull() }

    fun rels(partPath: String): Map<String, Rel> = relCache.getOrPut(partPath) {
        val dir = partPath.substringBeforeLast('/', "")
        val name = partPath.substringAfterLast('/')
        val relPath = if (dir.isEmpty()) "_rels/$name.rels" else "$dir/_rels/$name.rels"
        val root = xml(relPath) ?: return@getOrPut emptyMap()
        root.children("Relationship").mapNotNull { r ->
            val id = r.attr("Id") ?: return@mapNotNull null
            val external = r.attr("TargetMode") == "External"
            val target = r.attr("Target") ?: ""
            Rel(id, r.attr("Type") ?: "", if (external) target else resolve(dir, target), external)
        }.associateBy { it.id }
    }

    fun relTarget(partPath: String, rId: String?): String? {
        if (rId == null) return null
        val r = rels(partPath)[rId] ?: return null
        return if (r.external) null else r.target
    }

    fun relsOfType(partPath: String, typeSuffix: String): List<Rel> =
        rels(partPath).values.filter { it.type.endsWith(typeSuffix) && !it.external }

    override fun close() = zip.close()

    companion object {
        fun resolve(baseDir: String, target: String): String {
            val t = target.replace('\\', '/')
            val parts = ArrayList<String>()
            if (!t.startsWith("/")) parts.addAll(baseDir.split('/').filter { it.isNotEmpty() })
            for (seg in t.split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(java.net.URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8"))
                }
            }
            return parts.joinToString("/")
        }
    }
}
