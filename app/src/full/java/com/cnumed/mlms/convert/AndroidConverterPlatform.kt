package com.cnumed.mlms.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.cnumed.mlms.pdfconvert.ConverterPlatform
import com.cnumed.mlms.pdfconvert.DecodedImage
import com.cnumed.mlms.pdfconvert.OcrLine
import com.cnumed.mlms.pdfconvert.UserFontInfo
import com.cnumed.mlms.util.AppLog
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** 변환기의 안드로이드 구현: 번들 자원, 사용자 글꼴, 기기 글꼴, 이미지 디코딩, ML Kit OCR */
class AndroidConverterPlatform(private val context: Context, private val ocrEnabled: Boolean) : ConverterPlatform {

    private var recognizer: TextRecognizer? = null
    private var ocrBroken = false

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    override fun openAsset(path: String): InputStream? = runCatching { context.assets.open(path) }.getOrNull()

    override fun userFonts(): List<UserFontInfo> = UserFontStore.all(context)

    /** 마지막 폴백: 기호·수학 기호 등 (CFF 기반 OTF/TTC는 PDF 임베딩이 안 되므로 TTF만) */
    override fun systemFontFiles(): List<File> = listOf(
        "NotoSansSymbols-Regular-Subsetted.ttf", "NotoSansSymbols-Regular-Subsetted2.ttf", "NotoSansMath-Regular.ttf",
        "Roboto-Regular.ttf", "NotoSerif-Regular.ttf", "DroidSansFallback.ttf", "NotoSansSymbols-Regular.ttf",
    ).map { File("/system/fonts", it) }.filter { it.exists() }

    override fun decodeImage(bytes: ByteArray): DecodedImage? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return DecodedImage(bmp.width, bmp.height, px).also { bmp.recycle() }
    }

    override fun log(msg: String, t: Throwable?) {
        if (t != null) AppLog.e("PptxPdf", msg, t) else AppLog.d("PptxPdf", msg)
    }

    override fun tempDir(): File = File(context.cacheDir, "pdfconvert").apply { mkdirs() }

    override fun ocr(imageBytes: ByteArray): List<OcrLine>? {
        if (!ocrEnabled || ocrBroken) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        if (bounds.outWidth < 48 || bounds.outHeight < 24) return null
        var sample = 1
        while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
        val raw = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        // 투명 배경(PNG)은 흰 바탕에 합성 — 그대로 두면 투명 부분이 검게 보여 검은 글자를 놓친다
        val decoded = if (raw.hasAlpha()) {
            val white = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(white).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(raw, 0f, 0f, null) }
            raw.recycle(); white
        } else raw
        // 작은 캡처 이미지는 글자 높이가 낮아 인식률이 떨어지므로 긴 변이 2000px쯤 되도록 키운다 (최대 3배)
        val longSide = maxOf(decoded.width, decoded.height)
        val bmp = if (longSide < 1600) {
            val f = minOf(3.0, 2000.0 / longSide)
            Bitmap.createScaledBitmap(decoded, (decoded.width * f).toInt(), (decoded.height * f).toInt(), true).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        try {
            val rec = recognizer ?: TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()).also { recognizer = it }
            val result = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 60, TimeUnit.SECONDS)
            val w = bmp.width.toDouble(); val h = bmp.height.toDouble()
            return result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val b = line.boundingBox ?: return@mapNotNull null
                val text = line.text.trim()
                if (text.isEmpty()) null
                else OcrLine(text, b.left / w, b.top / h, b.right / w, b.bottom / h)
            }
        } catch (e: Exception) {
            // Play 서비스 OCR 모듈이 아직 내려받아지지 않았거나 쓸 수 없는 경우 — 이번 변환에서는 OCR 생략
            AppLog.e("PptxPdf", "OCR failed", e)
            var c: Throwable? = e
            while (c != null) {
                if (c is com.google.mlkit.common.MlKitException && c.errorCode == com.google.mlkit.common.MlKitException.UNAVAILABLE) ocrBroken = true
                c = c.cause
            }
            if (e is java.util.concurrent.TimeoutException) ocrBroken = true
            return null
        } finally {
            bmp.recycle()
        }
    }

    fun close() {
        recognizer?.close()
    }
}
