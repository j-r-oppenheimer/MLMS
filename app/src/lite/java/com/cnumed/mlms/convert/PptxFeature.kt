package com.cnumed.mlms.convert

import android.content.Context
import android.net.Uri

/** 라이트 버전: PPT → PDF 변환 엔진·글꼴·OCR을 넣지 않는다 (설정 화면에서도 숨김) */
object PptxFeature {
    const val AVAILABLE = false

    fun enqueueConversion(c: Context, url: String, pdfName: String, origName: String): Boolean = false
    fun importFont(c: Context, uri: Uri): List<String> = emptyList()
    fun importedFamilies(c: Context): List<String> = emptyList()
    fun googleFamilies(c: Context): List<String> = emptyList()
    fun clearFonts(c: Context) {}
}
