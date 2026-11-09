package com.cnumed.mlms.pdfconvert

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColorSpace
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * 이미지가 그림 틀 안에서 차지하는 위치.
 * place = 이미지 전체를 그릴 사각형, clip = 보이는 영역 (모두 틀 기준 0..1 비율)
 */
internal class PlacedImage(val image: PDImageXObject, val place: DoubleArray, val clip: DoubleArray?) {
    var key: String = ""
}

/**
 * 원본 화질 그대로 PDF 이미지로 옮긴다.
 *  - JPEG: 바이트를 그대로 DCTDecode 스트림으로 (재압축 없음)
 *  - PNG : 투명도 없는 일반 PNG는 IDAT 압축 데이터를 그대로 FlateDecode+PNG predictor로,
 *          투명도가 있으면 픽셀을 풀어 색/알파를 각각 무손실(Flate) 저장
 *  - EMF/WMF 안의 비트맵(DIB), BMP: 무손실(Flate)
 *  - 그 밖(GIF/WebP/TIFF): 플랫폼 디코더 → 무손실(Flate)
 */
internal class ImageLoader(private val doc: PDDocument, private val platform: ConverterPlatform) {
    private val cache = HashMap<String, PlacedImage?>()
    private val sources = HashMap<String, () -> ByteArray?>()
    private val ocrCache = HashMap<String, List<OcrLine>>()

    fun load(key: String, bytesProvider: () -> ByteArray?): PlacedImage? = cache.getOrPut(key) {
        sources[key] = bytesProvider
        val bytes = bytesProvider() ?: return@getOrPut null
        runCatching { decode(bytes) }
            .onFailure { platform.log("image failed: $key", it) }
            .getOrNull()?.also { it.key = key }
    }

    /** 이미지 글자 인식 결과(이미지당 한 번). EMF/WMF는 안의 비트맵을 BMP로 꺼내 인식한다 */
    fun ocr(key: String): List<OcrLine> = ocrCache.getOrPut(key) {
        val b = sources[key]?.invoke() ?: return@getOrPut emptyList()
        val input = when {
            b.size > 44 && le32(b, 0) == 1 && le32(b, 40) == 0x464D4520 -> emfBitmapAsBmp(b)
            else -> b
        } ?: return@getOrPut emptyList()
        runCatching { platform.ocr(input) }.onFailure { platform.log("ocr failed: $key", it) }.getOrNull() ?: emptyList()
    }

    private fun emfBitmapAsBmp(b: ByteArray): ByteArray? {
        var i = 0
        while (i + 8 <= b.size) {
            val type = le32(b, i); val size = le32(b, i + 4)
            if (size < 8 || i + size > b.size) break
            if (type == 81) {
                val bmi = i + le32(b, i + 48); val bmiLen = le32(b, i + 52)
                val bits = i + le32(b, i + 56); val bitsLen = le32(b, i + 60)
                return bmpFile(b, bmi, bmiLen + bitsLen, bits - bmi)
            }
            i += size
        }
        return null
    }

    private val FULL = doubleArrayOf(0.0, 0.0, 1.0, 1.0)

    private fun decode(b: ByteArray): PlacedImage? {
        fun full(img: PDImageXObject?) = img?.let { PlacedImage(it, FULL, null) }
        return when {
            b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> full(jpeg(b))
            b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> full(png(b))
            b.size > 44 && le32(b, 0) == 1 && le32(b, 40) == 0x464D4520 -> emf(b)
            b.size > 22 && (le32(b, 0) == 0x9AC6CDD7.toInt() || (le16(b, 0) == 1 && le16(b, 2) == 9)) -> wmf(b)
            b.size > 54 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> full(dib(b, 14, b.size - 14, le32(b, 10) - 14))
            else -> full(platform.decodeImage(b)?.let { argb(it) })
        }
    }

    // ───────────── JPEG ─────────────
    private fun jpeg(b: ByteArray): PDImageXObject? {
        var i = 2
        var w = 0; var h = 0; var comps = 3; var adobe = false
        while (i + 4 < b.size) {
            if (b[i] != 0xFF.toByte()) { i++; continue }
            val m = b[i + 1].toInt() and 0xFF
            if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7 || m == 0xFF) { i++; continue }
            if (m == 0xD9 || m == 0xDA) break
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (m == 0xEE && len >= 12 && String(b, i + 4, 5, Charsets.US_ASCII) == "Adobe") adobe = true
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                comps = b[i + 9].toInt() and 0xFF
            }
            i += 2 + len
        }
        if (w <= 0 || h <= 0) return platform.decodeImage(b)?.let { argb(it) }
        val cs: PDColorSpace = if (comps == 1) PDDeviceGray.INSTANCE else PDDeviceRGB.INSTANCE
        val img = PDImageXObject(doc, ByteArrayInputStream(b), COSName.DCT_DECODE, w, h, 8, cs)
        if (comps == 4) img.cosObject.setItem(COSName.COLORSPACE, COSName.DEVICECMYK)
        if (comps == 4 && adobe) {
            // Adobe(Photoshop) CMYK JPEG는 반전 저장된다
            img.cosObject.setItem(COSName.DECODE, COSArray().apply { repeat(4) { add(COSInteger.ONE); add(COSInteger.ZERO) } })
        }
        return img
    }

    // ───────────── PNG ─────────────
    private fun png(b: ByteArray): PDImageXObject? {
        var i = 8
        var w = 0; var h = 0; var depth = 8; var ctype = 2; var interlace = 0
        var plte: ByteArray? = null; var trns: ByteArray? = null
        val idat = ByteArrayOutputStream()
        while (i + 8 <= b.size) {
            val len = be32(b, i)
            val type = String(b, i + 4, 4, Charsets.US_ASCII)
            val d = i + 8
            if (len < 0 || d + len > b.size) break
            when (type) {
                "IHDR" -> { w = be32(b, d); h = be32(b, d + 4); depth = b[d + 8].toInt() and 0xFF; ctype = b[d + 9].toInt() and 0xFF; interlace = b[d + 12].toInt() }
                "PLTE" -> plte = b.copyOfRange(d, d + len)
                "tRNS" -> trns = b.copyOfRange(d, d + len)
                "IDAT" -> idat.write(b, d, len)
                "IEND" -> break
            }
            i = d + len + 4
        }
        if (w <= 0 || h <= 0) return null
        val data = idat.toByteArray()

        // 투명도 없음 + 비인터레이스 → 압축 데이터 그대로 (완전 무손실, 재압축 없음)
        if (interlace == 0 && trns == null && (ctype == 0 || ctype == 2 || (ctype == 3 && plte != null))) {
            val colors = if (ctype == 2) 3 else 1
            val cs: PDColorSpace = when (ctype) { 0 -> PDDeviceGray.INSTANCE; else -> PDDeviceRGB.INSTANCE }
            val img = PDImageXObject(doc, ByteArrayInputStream(data), COSName.FLATE_DECODE, w, h, depth, cs)
            img.cosObject.setItem(COSName.DECODE_PARMS, COSDictionary().apply {
                setInt(COSName.PREDICTOR, 15); setInt(COSName.COLORS, colors)
                setInt(COSName.BITS_PER_COMPONENT, depth); setInt(COSName.COLUMNS, w)
            })
            if (ctype == 3) img.cosObject.setItem(COSName.COLORSPACE, indexed(plte!!))
            return img
        }
        return pngDecoded(data, w, h, depth, ctype, interlace, plte, trns)
    }

    private fun indexed(plte: ByteArray) = COSArray().apply {
        add(COSName.INDEXED); add(COSName.DEVICERGB)
        add(COSInteger.get((plte.size / 3 - 1).toLong())); add(COSString(plte))
    }

    private fun pngDecoded(data: ByteArray, w: Int, h: Int, depth: Int, ctype: Int, interlace: Int, plte: ByteArray?, trns: ByteArray?): PDImageXObject {
        val channels = when (ctype) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> 3 }
        val bitsPP = channels * depth
        val bpp = maxOf(1, bitsPP / 8)
        // 출력 형식
        val outColors = if (ctype == 0 || ctype == 4) 1 else 3
        val outDepth = if (depth == 16 && ctype != 3) 16 else 8
        val outBytes = outDepth / 8
        val colorOut = ByteArrayOutputStream()
        val alphaOut = ByteArrayOutputStream()
        val colorZ = DeflaterOutputStream(colorOut, Deflater(6))
        val alphaZ = DeflaterOutputStream(alphaOut, Deflater(6))
        var transparent = false
        val cRow = ByteArray(w * outColors * outBytes)
        val aRow = ByteArray(w * outBytes)

        fun sample(row: ByteArray, x: Int, ch: Int): Int {
            return when (depth) {
                16 -> { val o = (x * channels + ch) * 2; ((row[o].toInt() and 0xFF) shl 8) or (row[o + 1].toInt() and 0xFF) }
                8 -> row[x * channels + ch].toInt() and 0xFF
                else -> { // 1/2/4비트 (회색 또는 팔레트, 채널 1개)
                    val bit = x * depth
                    (row[bit shr 3].toInt() shr (8 - depth - (bit and 7))) and ((1 shl depth) - 1)
                }
            }
        }
        val trnsKey: IntArray? = trns?.let { t ->
            when (ctype) {
                0 -> if (t.size >= 2) intArrayOf(be16(t, 0)) else null
                2 -> if (t.size >= 6) intArrayOf(be16(t, 0), be16(t, 2), be16(t, 4)) else null
                else -> null
            }
        }
        val maxVal = (1 shl depth) - 1
        fun emitRow(row: ByteArray) {
            for (x in 0 until w) {
                var alpha = if (outDepth == 16) 65535 else 255
                when (ctype) {
                    3 -> {
                        val idx = sample(row, x, 0)
                        val p = plte ?: ByteArray(768)
                        for (c in 0 until 3) cRow[x * 3 + c] = if (idx * 3 + c < p.size) p[idx * 3 + c] else 0
                        if (trns != null && idx < trns.size) alpha = trns[idx].toInt() and 0xFF
                    }
                    0, 4 -> {
                        val g = sample(row, x, 0)
                        putSample(cRow, x, g, depth, outDepth, maxVal)
                        if (ctype == 4) alpha = sample(row, x, 1).let { if (outDepth == 16) it else it }
                        else if (trnsKey != null && g == trnsKey[0]) alpha = 0
                    }
                    else -> {
                        val r = sample(row, x, 0); val g = sample(row, x, 1); val bl = sample(row, x, 2)
                        putSample(cRow, x * 3, r, depth, outDepth, maxVal)
                        putSample(cRow, x * 3 + 1, g, depth, outDepth, maxVal)
                        putSample(cRow, x * 3 + 2, bl, depth, outDepth, maxVal)
                        if (ctype == 6) alpha = sample(row, x, 3)
                        else if (trnsKey != null && r == trnsKey[0] && g == trnsKey[1] && bl == trnsKey[2]) alpha = 0
                    }
                }
                if (outDepth == 16) { aRow[x * 2] = (alpha shr 8).toByte(); aRow[x * 2 + 1] = alpha.toByte() }
                else aRow[x] = alpha.toByte()
                if (alpha != (if (outDepth == 16) 65535 else 255)) transparent = true
            }
            colorZ.write(cRow); alphaZ.write(aRow)
        }

        val inf = InflaterInputStream(ByteArrayInputStream(data), java.util.zip.Inflater(), 65536)
        if (interlace == 0) {
            unfilterRows(inf, w, h, bitsPP, bpp) { emitRow(it) }
        } else {
            // Adam7: 전체 버퍼를 만들어 일반 행으로 재조립
            val stride = (w * bitsPP + 7) / 8
            val full = ByteArray(stride * h)
            val passes = arrayOf(intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8), intArrayOf(2, 0, 4, 4),
                intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2), intArrayOf(0, 1, 1, 2))
            for (p in passes) {
                val pw = (w - p[0] + p[2] - 1) / p[2]
                val ph = (h - p[1] + p[3] - 1) / p[3]
                if (pw <= 0 || ph <= 0) continue
                var py = 0
                unfilterRows(inf, pw, ph, bitsPP, bpp) { row ->
                    val y = p[1] + py * p[3]
                    for (px in 0 until pw) {
                        val x = p[0] + px * p[2]
                        copyPixel(row, px, full, y * stride, x, bitsPP)
                    }
                    py++
                }
            }
            for (y in 0 until h) emitRow(full.copyOfRange(y * stride, (y + 1) * stride))
        }
        colorZ.finish(); alphaZ.finish()
        val cs = if (outColors == 1) PDDeviceGray.INSTANCE else PDDeviceRGB.INSTANCE
        val img = PDImageXObject(doc, ByteArrayInputStream(colorOut.toByteArray()), COSName.FLATE_DECODE, w, h, outDepth, cs)
        if (transparent) {
            val mask = PDImageXObject(doc, ByteArrayInputStream(alphaOut.toByteArray()), COSName.FLATE_DECODE, w, h, outDepth, PDDeviceGray.INSTANCE)
            img.cosObject.setItem(COSName.SMASK, mask)
        }
        return img
    }

    private fun putSample(dst: ByteArray, idx: Int, v: Int, depth: Int, outDepth: Int, maxVal: Int) {
        if (outDepth == 16) { dst[idx * 2] = (v shr 8).toByte(); dst[idx * 2 + 1] = v.toByte() }
        else dst[idx] = (if (depth == 8) v else v * 255 / maxVal).toByte()
    }

    private fun copyPixel(src: ByteArray, sx: Int, dst: ByteArray, rowOff: Int, dx: Int, bitsPP: Int) {
        if (bitsPP >= 8) {
            val n = bitsPP / 8
            System.arraycopy(src, sx * n, dst, rowOff + dx * n, n)
        } else {
            val sb = sx * bitsPP; val db = dx * bitsPP
            val v = (src[sb shr 3].toInt() shr (8 - bitsPP - (sb and 7))) and ((1 shl bitsPP) - 1)
            val shift = 8 - bitsPP - (db and 7)
            val i = rowOff + (db shr 3)
            dst[i] = ((dst[i].toInt() and (((1 shl bitsPP) - 1) shl shift).inv()) or (v shl shift)).toByte()
        }
    }

    private inline fun unfilterRows(inp: java.io.InputStream, w: Int, h: Int, bitsPP: Int, bpp: Int, sink: (ByteArray) -> Unit) {
        val stride = (w * bitsPP + 7) / 8
        var prev = ByteArray(stride)
        var cur = ByteArray(stride)
        for (y in 0 until h) {
            val f = inp.read()
            if (f < 0) break
            var got = 0
            while (got < stride) { val n = inp.read(cur, got, stride - got); if (n < 0) break; got += n }
            when (f) {
                1 -> for (i in bpp until stride) cur[i] = (cur[i] + cur[i - bpp]).toByte()
                2 -> for (i in 0 until stride) cur[i] = (cur[i] + prev[i]).toByte()
                3 -> for (i in 0 until stride) {
                    val left = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                    cur[i] = (cur[i] + ((left + (prev[i].toInt() and 0xFF)) shr 1)).toByte()
                }
                4 -> for (i in 0 until stride) {
                    val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                    val bb = prev[i].toInt() and 0xFF
                    val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                    val p = a + bb - c
                    val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - bb); val pc = kotlin.math.abs(p - c)
                    val pr = if (pa <= pb && pa <= pc) a else if (pb <= pc) bb else c
                    cur[i] = (cur[i] + pr).toByte()
                }
            }
            sink(cur)
            val t = prev; prev = cur; cur = t
        }
    }

    // ───────────── DIB (BMP, EMF/WMF 안의 비트맵) ─────────────
    /**
     * @param off BITMAPINFOHEADER 시작, @param bitsOff 픽셀 시작(off 기준 상대)
     */
    private fun dib(b: ByteArray, off: Int, len: Int, bitsOff: Int): PDImageXObject? {
        if (off + 40 > b.size) return null
        val hdrSize = le32(b, off)
        val w: Int; val hRaw: Int; val bitCount: Int; val compression: Int; var clrUsed: Int
        if (hdrSize == 12) { // BITMAPCOREHEADER
            w = le16(b, off + 4); hRaw = le16(b, off + 6); bitCount = le16(b, off + 10); compression = 0; clrUsed = 0
        } else {
            w = le32(b, off + 4); hRaw = le32(b, off + 8); bitCount = le16(b, off + 14)
            compression = le32(b, off + 16); clrUsed = le32(b, off + 32)
        }
        val h = kotlin.math.abs(hRaw)
        val topDown = hRaw < 0
        if (w <= 0 || h <= 0) return null
        val pixStart = off + bitsOff
        // BI_JPEG / BI_PNG: 압축 데이터가 그대로 들어있다
        if (compression == 4 || compression == 5) {
            val inner = b.copyOfRange(pixStart, minOf(b.size, off + len))
            return decode(inner)?.image
        }
        if (compression != 0 && compression != 3) {
            return platform.decodeImage(bmpFile(b, off, len, bitsOff))?.let { argb(it) }
        }
        val palEntry = if (hdrSize == 12) 3 else 4
        if (bitCount <= 8 && clrUsed == 0) clrUsed = 1 shl bitCount
        val palOff = off + hdrSize + (if (compression == 3 && hdrSize == 40) 12 else 0)
        var rMask = 0x00FF0000; var gMask = 0x0000FF00; var bMask = 0x000000FF
        if (bitCount == 16) { rMask = 0x7C00; gMask = 0x03E0; bMask = 0x001F }
        if (compression == 3) {
            val mo = if (hdrSize >= 52) off + 40 else off + hdrSize
            rMask = le32(b, mo); gMask = le32(b, mo + 4); bMask = le32(b, mo + 8)
        }
        val stride = ((w * bitCount + 31) / 32) * 4
        val out = ByteArrayOutputStream()
        val z = DeflaterOutputStream(out, Deflater(6))
        val row = ByteArray(w * 3)
        fun maskVal(v: Int, m: Int): Int {
            if (m == 0) return 0
            val shift = Integer.numberOfTrailingZeros(m)
            val bits = Integer.bitCount(m)
            val x = (v and m) ushr shift
            return if (bits >= 8) x ushr (bits - 8) else (x * 255) / ((1 shl bits) - 1)
        }
        for (yy in 0 until h) {
            val srcRow = if (topDown) yy else h - 1 - yy
            val ro = pixStart + srcRow * stride
            if (ro + stride > b.size) { java.util.Arrays.fill(row, 0); z.write(row); continue }
            for (x in 0 until w) {
                var r: Int; var g: Int; var bl: Int
                when (bitCount) {
                    24 -> { val p = ro + x * 3; bl = b[p].toInt() and 0xFF; g = b[p + 1].toInt() and 0xFF; r = b[p + 2].toInt() and 0xFF }
                    32 -> {
                        val v = le32(b, ro + x * 4)
                        if (compression == 3) { r = maskVal(v, rMask); g = maskVal(v, gMask); bl = maskVal(v, bMask) }
                        else { bl = v and 0xFF; g = (v shr 8) and 0xFF; r = (v shr 16) and 0xFF }
                    }
                    16 -> { val v = le16(b, ro + x * 2); r = maskVal(v, rMask); g = maskVal(v, gMask); bl = maskVal(v, bMask) }
                    else -> {
                        val bit = x * bitCount
                        val idx = ((b[ro + (bit shr 3)].toInt() and 0xFF) shr (8 - bitCount - (bit and 7))) and ((1 shl bitCount) - 1)
                        val po = palOff + idx * palEntry
                        if (po + 2 < b.size) { bl = b[po].toInt() and 0xFF; g = b[po + 1].toInt() and 0xFF; r = b[po + 2].toInt() and 0xFF }
                        else { r = 0; g = 0; bl = 0 }
                    }
                }
                row[x * 3] = r.toByte(); row[x * 3 + 1] = g.toByte(); row[x * 3 + 2] = bl.toByte()
            }
            z.write(row)
        }
        z.finish()
        return PDImageXObject(doc, ByteArrayInputStream(out.toByteArray()), COSName.FLATE_DECODE, w, h, 8, PDDeviceRGB.INSTANCE)
    }

    fun bmpFile(b: ByteArray, off: Int, len: Int, bitsOff: Int): ByteArray {
        val o = ByteArrayOutputStream()
        val total = 14 + len
        o.write('B'.code); o.write('M'.code)
        writeLe32(o, total); writeLe32(o, 0); writeLe32(o, 14 + bitsOff)
        o.write(b, off, minOf(len, b.size - off))
        return o.toByteArray()
    }

    // ───────────── EMF / WMF: 들어있는 비트맵만 추출 ─────────────
    private class BitmapRec(val bmiOff: Int, val bmiLen: Int, val bitsOff: Int, val bitsLen: Int,
                            val dx: Double, val dy: Double, val dw: Double, val dh: Double)

    private fun emf(b: ByteArray): PlacedImage? {
        val bl = le32(b, 8); val bt = le32(b, 12); val br = le32(b, 16); val bb = le32(b, 20)
        val recs = ArrayList<BitmapRec>()
        var i = 0
        while (i + 8 <= b.size) {
            val type = le32(b, i); val size = le32(b, i + 4)
            if (size < 8 || i + size > b.size) break
            when (type) {
                81 -> recs.add(BitmapRec(i + le32(b, i + 48), le32(b, i + 52), i + le32(b, i + 56), le32(b, i + 60),
                    le32(b, i + 24).toDouble(), le32(b, i + 28).toDouble(), le32(b, i + 72).toDouble(), le32(b, i + 76).toDouble()))
                76, 77 -> if (size >= 100 && le32(b, i + 88) > 0) recs.add(BitmapRec(i + le32(b, i + 84), le32(b, i + 88), i + le32(b, i + 92), le32(b, i + 96),
                    le32(b, i + 24).toDouble(), le32(b, i + 28).toDouble(), le32(b, i + 32).toDouble(), le32(b, i + 36).toDouble()))
                80 -> recs.add(BitmapRec(i + le32(b, i + 48), le32(b, i + 52), i + le32(b, i + 56), le32(b, i + 60),
                    le32(b, i + 24).toDouble(), le32(b, i + 28).toDouble(), le32(b, i + 40).toDouble(), le32(b, i + 44).toDouble()))
                14 -> break
            }
            i += size
        }
        val rec = recs.maxByOrNull { it.bitsLen } ?: return null
        val img = dibFromRec(b, rec) ?: return null
        val fw = (br - bl + 1).toDouble(); val fh = (bb - bt + 1).toDouble()
        var place = doubleArrayOf((rec.dx - bl) / fw, (rec.dy - bt) / fh, kotlin.math.abs(rec.dw) / fw, kotlin.math.abs(rec.dh) / fh)
        // 좌표계가 예상과 다르면(매핑 모드 변경 등) 틀 전체를 채운다
        if (recs.size > 1 || place.any { it.isNaN() } || place[0] < -0.05 || place[1] < -0.05 ||
            place[0] + place[2] > 1.05 || place[1] + place[3] > 1.05 || place[2] < 0.5 || place[3] < 0.5) {
            place = FULL
        }
        return PlacedImage(img, place, null)
    }

    private fun dibFromRec(b: ByteArray, r: BitmapRec): PDImageXObject? {
        if (r.bmiOff <= 0 || r.bmiOff + 12 > b.size) return null
        return dib(b, r.bmiOff, r.bmiLen + r.bitsLen, r.bitsOff - r.bmiOff)
    }

    private fun wmf(b: ByteArray): PlacedImage? {
        var i = if (le32(b, 0) == 0x9AC6CDD7.toInt()) 22 else 0
        val hdrWords = le16(b, i + 2)
        i += hdrWords * 2
        var best: BitmapRec? = null
        while (i + 6 <= b.size) {
            val sizeW = le32(b, i).toLong() and 0xFFFFFFFFL
            val fn = le16(b, i + 4)
            if (sizeW < 3 || i + sizeW * 2 > b.size) break
            val size = (sizeW * 2).toInt()
            val dibStart: Int = when (fn) {
                0x0F43 -> i + 6 + 22 // STRETCHDIB
                0x0B41 -> i + 6 + 20 // DIBSTRETCHBLT
                0x0940 -> i + 6 + 16 // DIBBITBLT
                else -> -1
            }
            if (dibStart > 0 && dibStart < i + size) {
                val hdr = le32(b, dibStart)
                val bitCount = le16(b, dibStart + 14)
                var clr = le32(b, dibStart + 32)
                if (bitCount <= 8 && clr == 0) clr = 1 shl bitCount
                val bitsRel = hdr + (if (bitCount <= 8) clr * 4 else 0)
                val len = i + size - dibStart
                if (best == null || len > best.bitsLen) best = BitmapRec(dibStart, 0, dibStart + bitsRel, len, 0.0, 0.0, 1.0, 1.0)
            }
            if (fn == 0) break
            i += size
        }
        val r = best ?: return null
        val img = dib(b, r.bmiOff, r.bitsLen, r.bitsOff - r.bmiOff) ?: return null
        return PlacedImage(img, FULL, null)
    }

    // ───────────── 플랫폼 디코딩 결과 ─────────────
    private fun argb(d: DecodedImage): PDImageXObject {
        val colorOut = ByteArrayOutputStream(); val alphaOut = ByteArrayOutputStream()
        val cz = DeflaterOutputStream(colorOut); val az = DeflaterOutputStream(alphaOut)
        val row = ByteArray(d.width * 3); val arow = ByteArray(d.width)
        var transparent = false
        for (y in 0 until d.height) {
            for (x in 0 until d.width) {
                val p = d.argb[y * d.width + x]
                row[x * 3] = (p shr 16).toByte(); row[x * 3 + 1] = (p shr 8).toByte(); row[x * 3 + 2] = p.toByte()
                val a = p ushr 24
                arow[x] = a.toByte(); if (a != 255) transparent = true
            }
            cz.write(row); az.write(arow)
        }
        cz.finish(); az.finish()
        val img = PDImageXObject(doc, ByteArrayInputStream(colorOut.toByteArray()), COSName.FLATE_DECODE, d.width, d.height, 8, PDDeviceRGB.INSTANCE)
        if (transparent) img.cosObject.setItem(COSName.SMASK,
            PDImageXObject(doc, ByteArrayInputStream(alphaOut.toByteArray()), COSName.FLATE_DECODE, d.width, d.height, 8, PDDeviceGray.INSTANCE))
        return img
    }

    companion object {
        fun le32(b: ByteArray, i: Int): Int = if (i + 4 > b.size || i < 0) 0 else ByteBuffer.wrap(b, i, 4).order(ByteOrder.LITTLE_ENDIAN).int
        fun le16(b: ByteArray, i: Int): Int = if (i + 2 > b.size || i < 0) 0 else (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        fun be32(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
        fun be16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        private fun writeLe32(o: OutputStream, v: Int) { o.write(v); o.write(v shr 8); o.write(v shr 16); o.write(v shr 24) }
    }
}
