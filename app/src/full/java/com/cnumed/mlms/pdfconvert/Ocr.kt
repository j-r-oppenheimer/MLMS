package com.cnumed.mlms.pdfconvert

/**
 * 그림 위에 OCR 결과를 보이지 않는 글자로 겹쳐 PDF에서 검색·선택·복사가 되게 한다.
 * 그림 자체는 건드리지 않는다.
 */
internal class OcrOverlay(private val images: ImageLoader, private val fonts: FontManager) {

    /**
     * @param x,y,w,h 그림이 그려진 사각형, frameW/frameH 보이는 틀(잘린 부분 제외용)
     */
    fun draw(p: Painter, key: String, x: Double, y: Double, w: Double, h: Double, frameW: Double, frameH: Double) {
        if (key.isEmpty() || w < 20 || h < 20) return
        val lines = images.ocr(key)
        if (lines.isEmpty()) return
        val choices = fonts.choices("맑은 고딕", false, false)
        for (ln in lines) {
            val bx = x + ln.left * w; val by = y + ln.top * h
            val bw = (ln.right - ln.left) * w; val bh = (ln.bottom - ln.top) * h
            if (bw <= 1 || bh <= 1) continue
            val cx = bx + bw / 2; val cy = by + bh / 2
            if (cx < 0 || cy < 0 || cx > frameW || cy > frameH) continue
            // 한 글꼴로 출력(없는 글자는 뺀다)
            val face = choices.firstOrNull { c -> ln.text.codePoints().allMatch { it == ' '.code || c.face.hasGlyph(it) } }?.face
                ?: choices.firstOrNull()?.face ?: continue
            val text = buildString { ln.text.codePoints().forEach { if (it == ' '.code || face.hasGlyph(it)) appendCodePoint(it) } }.trim()
            if (text.isEmpty()) continue
            val size = bh * 0.9
            var natural = 0.0
            text.codePoints().forEach { natural += face.advance(it) * size }
            if (natural <= 0) continue
            val scale = (bw / natural * 100).coerceIn(10.0, 500.0)
            p.invisibleText(face, size, bx, by + bh * 0.82, text, scale)
        }
    }
}
