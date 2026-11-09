package com.cnumed.mlms.pdfconvert

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * PPTX가 쓰는 글꼴이 기기에 없을 때 Google Fonts(무료 오픈 라이선스)에서 같은 이름의 글꼴을 받아 둔다.
 * 받은 글꼴은 dir/<패밀리>/ 아래 TTF로 저장되고, 없는 글꼴은 dir/missing.txt에 기록해 다시 묻지 않는다.
 */
class GoogleFontsFetcher(private val dir: File, private val log: (String, Throwable?) -> Unit = { _, _ -> }) {

    private val missingFile = File(dir, "missing.txt")

    private fun missingSet(): MutableSet<String> =
        if (missingFile.exists()) missingFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet() else mutableSetOf()

    /** 이미 받은 글꼴 파일들 */
    fun downloadedFiles(): List<File> =
        dir.listFiles()?.filter { it.isDirectory }?.flatMap { d -> d.listFiles()?.filter { it.name.endsWith(".ttf") } ?: emptyList() } ?: emptyList()

    /**
     * @return 새로 받은 패밀리 이름들
     */
    fun fetch(families: Collection<String>): List<String> {
        dir.mkdirs()
        val missing = missingSet()
        val got = ArrayList<String>()
        for (fam in families) {
            val name = fam.trim()
            if (name.isEmpty() || name.startsWith("+") || name.any { it.code > 0x7E } || name in missing) continue
            val famDir = File(dir, safe(name))
            if (famDir.isDirectory && (famDir.listFiles()?.isNotEmpty() == true)) continue
            val variants = listOf("" to "Regular", ":wght@700" to "Bold", ":ital@1" to "Italic", ":ital,wght@1,700" to "BoldItalic")
            var any = false
            for ((spec, style) in variants) {
                val css = http("https://fonts.googleapis.com/css2?family=" + URLEncoder.encode(name, "UTF-8").replace("+", "%20") + spec) ?: continue
                val url = Regex("url\\((https://[^)]+\\.ttf)\\)").find(String(css, Charsets.UTF_8))?.groupValues?.get(1) ?: continue
                val data = http(url) ?: continue
                famDir.mkdirs()
                File(famDir, "${safe(name)}-$style.ttf").writeBytes(data)
                any = true
            }
            if (any) got.add(name) else { missing.add(name); missingFile.appendText(name + "\n") }
        }
        return got
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._ -]"), "_")

    private fun http(url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 20000
        // 일반 브라우저 UA를 보내지 않으면 Google이 woff2 대신 TTF 주소를 준다
        c.setRequestProperty("User-Agent", "MLMS-PptxConverter")
        if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
    } catch (e: Exception) { log("google fonts: $url", e); null }

    companion object {
        /** 받은 TTF 파일을 사용자 글꼴 메타데이터로 */
        fun infoOf(file: File): UserFontInfo {
            val fam = file.parentFile?.name ?: file.nameWithoutExtension
            val style = file.nameWithoutExtension.substringAfterLast('-')
            return UserFontInfo(file, null, listOf(fam), style.startsWith("Bold"), style.endsWith("Italic"))
        }
    }
}
