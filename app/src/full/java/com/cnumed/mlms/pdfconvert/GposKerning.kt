package com.cnumed.mlms.pdfconvert

/**
 * OpenType GPOS 'kern' 기능의 글자쌍 위치 조정(PairPos, 형식 1·2) 판독기.
 * Calibri/Carlito처럼 kern 표 없이 GPOS로만 커닝을 갖는 글꼴에서 PowerPoint와 같은 글자 간격을 얻기 위해 쓴다.
 * 반환값은 글꼴 단위의 x 전진폭 조정값.
 */
internal class GposKerning private constructor(private val d: ByteArray, private val subtables: List<Int>) {

    private val cache = HashMap<Long, Int>()

    fun kern(left: Int, right: Int): Int {
        val key = (left.toLong() shl 32) or right.toLong()
        return cache.getOrPut(key) { compute(left, right) }
    }

    private fun u16(o: Int) = if (o + 2 > d.size || o < 0) 0 else ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
    private fun s16(o: Int) = u16(o).toShort().toInt()

    private fun compute(l: Int, r: Int): Int {
        for (st in subtables) {
            val fmt = u16(st)
            val ci = coverage(st + u16(st + 2), l)
            if (ci < 0) continue
            val vf1 = u16(st + 4); val vf2 = u16(st + 6)
            val v1 = Integer.bitCount(vf1) * 2; val v2 = Integer.bitCount(vf2) * 2
            if (fmt == 1) {
                val pairSetCount = u16(st + 8)
                if (ci >= pairSetCount) continue
                val ps = st + u16(st + 10 + ci * 2)
                val n = u16(ps)
                val rec = 2 + v1 + v2
                // secondGlyph로 정렬돼 있으므로 이진 탐색
                var lo = 0; var hi = n - 1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val o = ps + 2 + mid * rec
                    val g = u16(o)
                    when {
                        g < r -> lo = mid + 1
                        g > r -> hi = mid - 1
                        else -> return xAdvance(o + 2, vf1)
                    }
                }
            } else if (fmt == 2) {
                val c1 = classOf(st + u16(st + 8), l)
                val c2 = classOf(st + u16(st + 10), r)
                val c1Count = u16(st + 12); val c2Count = u16(st + 14)
                if (c1 >= c1Count || c2 >= c2Count) continue
                val o = st + 16 + (c1 * c2Count + c2) * (v1 + v2)
                return xAdvance(o, vf1)
            }
        }
        return 0
    }

    /** ValueRecord에서 XAdvance(0x4)만 읽는다 */
    private fun xAdvance(o: Int, vf: Int): Int {
        if (vf and 0x4 == 0) return 0
        var off = o
        if (vf and 0x1 != 0) off += 2
        if (vf and 0x2 != 0) off += 2
        return s16(off)
    }

    private fun coverage(c: Int, g: Int): Int {
        when (u16(c)) {
            1 -> {
                var lo = 0; var hi = u16(c + 2) - 1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val v = u16(c + 4 + mid * 2)
                    when { v < g -> lo = mid + 1; v > g -> hi = mid - 1; else -> return mid }
                }
            }
            2 -> {
                var lo = 0; var hi = u16(c + 2) - 1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val o = c + 4 + mid * 6
                    when {
                        u16(o + 2) < g -> lo = mid + 1
                        u16(o) > g -> hi = mid - 1
                        else -> return u16(o + 4) + g - u16(o)
                    }
                }
            }
        }
        return -1
    }

    private fun classOf(c: Int, g: Int): Int {
        when (u16(c)) {
            1 -> {
                val start = u16(c + 2); val n = u16(c + 4)
                return if (g >= start && g < start + n) u16(c + 6 + (g - start) * 2) else 0
            }
            2 -> {
                var lo = 0; var hi = u16(c + 2) - 1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val o = c + 4 + mid * 6
                    when {
                        u16(o + 2) < g -> lo = mid + 1
                        u16(o) > g -> hi = mid - 1
                        else -> return u16(o + 4)
                    }
                }
            }
        }
        return 0
    }

    companion object {
        fun parse(d: ByteArray?): GposKerning? {
            if (d == null || d.size < 10) return null
            fun u16(o: Int) = if (o + 2 > d.size || o < 0) 0 else ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
            fun u32(o: Int) = (u16(o).toLong() shl 16 or u16(o + 2).toLong()).toInt()
            val featureList = u16(6); val lookupList = u16(8)
            val lookups = sortedSetOf<Int>()
            val fCount = u16(featureList)
            for (i in 0 until fCount) {
                val rec = featureList + 2 + i * 6
                val tag = String(d, rec, 4, Charsets.US_ASCII)
                if (tag != "kern") continue
                val f = featureList + u16(rec + 4)
                val n = u16(f + 2)
                for (k in 0 until n) lookups.add(u16(f + 4 + k * 2))
            }
            if (lookups.isEmpty()) return null
            val subs = ArrayList<Int>()
            val lCount = u16(lookupList)
            for (li in lookups) {
                if (li >= lCount) continue
                val lk = lookupList + u16(lookupList + 2 + li * 2)
                val type = u16(lk)
                val n = u16(lk + 4)
                for (k in 0 until n) {
                    var st = lk + u16(lk + 6 + k * 2)
                    var t = type
                    if (t == 9) { t = u16(st + 2); st += u32(st + 4) }
                    if (t == 2) subs.add(st)
                }
            }
            return if (subs.isEmpty()) null else GposKerning(d, subs)
        }
    }
}
