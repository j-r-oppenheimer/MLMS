package com.cnumed.mlms.convert

import android.content.Context
import android.net.Uri

/** 정식(full) 버전: PPT → PDF 변환 기능 사용 가능 */
object PptxFeature {
    const val AVAILABLE = true

    /** @return 앱 알림이 켜져 있으면 true */
    fun enqueueConversion(c: Context, url: String, pdfName: String, origName: String): Boolean =
        PptxConvertWorker.enqueue(c, url, pdfName, origName)

    fun importFont(c: Context, uri: Uri): List<String> = UserFontStore.import(c, uri)
    fun importedFamilies(c: Context): List<String> = UserFontStore.importedFamilies(c)
    fun googleFamilies(c: Context): List<String> = UserFontStore.googleFamilies(c)
    fun clearFonts(c: Context) { UserFontStore.clearImported(c); UserFontStore.clearGoogle(c) }
}
