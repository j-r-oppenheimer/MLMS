package com.cnumed.mlms.pdfconvert

import java.io.File
import java.io.InputStream

/** ARGB(0xAARRGGBB) 픽셀 */
class DecodedImage(val width: Int, val height: Int, val argb: IntArray)

/** OCR로 찾은 글줄. 좌표는 이미지 기준 0..1 비율 */
class OcrLine(val text: String, val left: Double, val top: Double, val right: Double, val bottom: Double)

/** 사용자가 설정에서 추가한 글꼴 한 개의 메타데이터 */
data class UserFontInfo(
    val file: File,
    /** TTC 안의 글꼴이면 PostScript 이름, 단일 TTF면 null */
    val psName: String?,
    val families: List<String>,
    val bold: Boolean,
    val italic: Boolean,
)

/** 변환기가 플랫폼(안드로이드/데스크톱 테스트)에서 필요로 하는 것들 */
interface ConverterPlatform {
    /** "fonts/…", "pptx/presetShapeDefinitions.xml" 같은 번들 자원. 없으면 null */
    fun openAsset(path: String): InputStream?
    fun userFonts(): List<UserFontInfo>
    /** 기기 내장 TrueType 글꼴 (마지막 폴백용) */
    fun systemFontFiles(): List<File>
    /** JPEG/PNG/EMF 외 형식(GIF, WebP, TIFF …) 디코딩. 못 하면 null */
    fun decodeImage(bytes: ByteArray): DecodedImage?
    fun log(msg: String, t: Throwable? = null)
    /** 대용량 임시 데이터용 디렉터리 */
    fun tempDir(): File
    /** 이미지(JPEG/PNG/BMP 등 원본 바이트)의 글자 인식. 지원하지 않으면 null */
    fun ocr(imageBytes: ByteArray): List<OcrLine>? = null
}
