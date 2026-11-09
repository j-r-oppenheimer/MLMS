package com.cnumed.mlms.convert

import android.content.Context
import android.content.SharedPreferences

/** PPT→PDF 변환 설정 (기존 class_settings 저장소 사용) */
object ConvertPrefs {
    private const val PREFS = "class_settings"

    const val KEY_PPTX_PDF = "pptx_pdf_enabled"
    const val KEY_OCR = "pptx_pdf_ocr"
    const val KEY_FETCH_FONTS = "pptx_pdf_fetch_fonts"

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pptxToPdf(c: Context) = prefs(c).getBoolean(KEY_PPTX_PDF, false)
    fun ocr(c: Context) = prefs(c).getBoolean(KEY_OCR, false)
    fun fetchFonts(c: Context) = prefs(c).getBoolean(KEY_FETCH_FONTS, true)
}
