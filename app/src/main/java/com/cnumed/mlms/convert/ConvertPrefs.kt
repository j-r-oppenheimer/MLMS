package com.cnumed.mlms.convert

import android.content.Context
import android.content.SharedPreferences

/** PPT→PDF 변환·파일 이름 형식 설정 (기존 class_settings 저장소 사용) */
object ConvertPrefs {
    private const val PREFS = "class_settings"

    const val KEY_PPTX_PDF = "pptx_pdf_enabled"
    const val KEY_OCR = "pptx_pdf_ocr"
    const val KEY_FETCH_FONTS = "pptx_pdf_fetch_fonts"
    const val KEY_NAME_ENABLED = "filename_template_enabled"
    const val KEY_NAME_TEMPLATE = "filename_template"
    const val KEY_NAME_DATE_FMT = "filename_date_format"

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pptxToPdf(c: Context) = prefs(c).getBoolean(KEY_PPTX_PDF, false)
    fun ocr(c: Context) = prefs(c).getBoolean(KEY_OCR, false)
    fun fetchFonts(c: Context) = prefs(c).getBoolean(KEY_FETCH_FONTS, true)
    fun nameEnabled(c: Context) = prefs(c).getBoolean(KEY_NAME_ENABLED, false)
    fun nameTemplate(c: Context): String {
        val t = prefs(c).getString(KEY_NAME_TEMPLATE, null)
        return if (t == null || t == FileNameFormatter.OLD_DEFAULT_TEMPLATE) FileNameFormatter.DEFAULT_TEMPLATE else t
    }
    fun dateFormat(c: Context) = prefs(c).getString(KEY_NAME_DATE_FMT, FileNameFormatter.DATE_FORMATS[0]) ?: FileNameFormatter.DATE_FORMATS[0]
}
