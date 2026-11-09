package com.cnumed.mlms.convert

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.cnumed.mlms.pdfconvert.FontInspector
import com.cnumed.mlms.pdfconvert.GoogleFontsFetcher
import com.cnumed.mlms.pdfconvert.UserFontInfo
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 변환용 글꼴 저장소.
 *  - 사용자가 설정에서 가져온 TTF/TTC (files/user_fonts)
 *  - Google Fonts에서 자동으로 받은 글꼴 (files/gfonts)
 */
object UserFontStore {
    private fun dir(c: Context) = File(c.filesDir, "user_fonts").apply { mkdirs() }
    private fun index(c: Context) = File(dir(c), "index.json")
    fun googleDir(c: Context) = File(c.filesDir, "gfonts")

    @Synchronized
    fun imported(c: Context): List<UserFontInfo> {
        val f = index(c)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val file = File(dir(c), o.getString("file"))
                if (!file.exists()) return@mapNotNull null
                val fams = o.getJSONArray("families").let { a -> (0 until a.length()).map { a.getString(it) } }
                UserFontInfo(file, o.optString("ps").ifEmpty { null }, fams, o.optBoolean("bold"), o.optBoolean("italic"))
            }
        }.getOrDefault(emptyList())
    }

    fun all(c: Context): List<UserFontInfo> =
        imported(c) + GoogleFontsFetcher(googleDir(c)).downloadedFiles().map { GoogleFontsFetcher.infoOf(it) }

    /** 가져온 글꼴 패밀리 이름 목록(표시용) */
    fun importedFamilies(c: Context): List<String> = imported(c).map { it.families.first() }.distinct()

    fun googleFamilies(c: Context): List<String> =
        GoogleFontsFetcher(googleDir(c)).downloadedFiles().mapNotNull { it.parentFile?.name }.distinct()

    /**
     * 글꼴 파일을 가져온다.
     * @return 추가된 패밀리 이름들
     * @throws IllegalArgumentException 지원하지 않는 글꼴
     */
    @Synchronized
    fun import(c: Context, uri: Uri): List<String> {
        PDFBoxResourceLoader.init(c.applicationContext)
        val display = c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        } ?: "font.ttf"
        val ext = display.substringAfterLast('.', "ttf").lowercase()
        if (ext !in setOf("ttf", "ttc", "otf", "otc")) throw IllegalArgumentException("TTF·TTC 글꼴 파일만 추가할 수 있습니다")
        val target = File(dir(c), "${System.currentTimeMillis()}_${display.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        c.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalArgumentException("파일을 열 수 없습니다")
        val infos = try {
            FontInspector.inspect(target)
        } catch (e: Exception) {
            target.delete()
            throw IllegalArgumentException(e.message ?: "글꼴을 읽을 수 없습니다 (TrueType 글꼴만 지원)")
        }
        val arr = if (index(c).exists()) runCatching { JSONArray(index(c).readText()) }.getOrDefault(JSONArray()) else JSONArray()
        for (info in infos) {
            arr.put(JSONObject().apply {
                put("file", target.name)
                put("ps", info.psName ?: "")
                put("families", JSONArray(info.families))
                put("bold", info.bold)
                put("italic", info.italic)
            })
        }
        index(c).writeText(arr.toString())
        return infos.map { it.families.first() }.distinct()
    }

    @Synchronized
    fun clearImported(c: Context) {
        dir(c).deleteRecursively()
    }

    fun clearGoogle(c: Context) {
        googleDir(c).deleteRecursively()
    }
}
