package com.dripagency.mlbbdraft.vision

/**
 * Difference hash over an 8x8 grid (9 columns compared pairwise).  Pure Kotlin on a grayscale
 * IntArray so it is unit-testable and matches the scraper's Python `dhash()` bit for bit.
 */
object PerceptualHash {
    const val SIZE = 8

    fun dHash(gray: IntArray, width: Int, height: Int): Long {
        val cols = SIZE + 1
        val cellW = width.toFloat() / cols
        val cellH = height.toFloat() / SIZE
        val small = FloatArray(cols * SIZE)
        for (r in 0 until SIZE) {
            val y0 = (r * cellH).toInt(); val y1 = maxOf(y0 + 1, ((r + 1) * cellH).toInt()).coerceAtMost(height)
            for (c in 0 until cols) {
                val x0 = (c * cellW).toInt(); val x1 = maxOf(x0 + 1, ((c + 1) * cellW).toInt()).coerceAtMost(width)
                var sum = 0L; var n = 0
                for (y in y0 until y1) for (x in x0 until x1) { sum += gray[y * width + x]; n++ }
                small[r * cols + c] = if (n == 0) 0f else sum.toFloat() / n
            }
        }
        var bits = 0L
        for (r in 0 until SIZE) for (c in 0 until SIZE) {
            bits = (bits shl 1) or (if (small[r * cols + c] > small[r * cols + c + 1]) 1L else 0L)
        }
        return bits
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
