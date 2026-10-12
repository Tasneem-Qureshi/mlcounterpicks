package com.dripagency.mlbbdraft.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualHashTest {
    private fun gradient(w: Int, h: Int, flip: Boolean = false) = IntArray(w * h) { i -> val x = i % w; if (flip) 255 - x * 255 / w else x * 255 / w }

    @Test
    fun `identical images hash identically`() {
        val a = PerceptualHash.dHash(gradient(64, 64), 64, 64)
        val b = PerceptualHash.dHash(gradient(64, 64), 64, 64)
        assertEquals(0, PerceptualHash.hamming(a, b))
    }

    @Test
    fun `hash is resolution invariant for the same picture`() {
        val a = PerceptualHash.dHash(gradient(64, 64), 64, 64)
        val b = PerceptualHash.dHash(gradient(96, 96), 96, 96)
        assertTrue(PerceptualHash.hamming(a, b) <= 2)
    }

    @Test
    fun `mirrored gradient is maximally different`() {
        val a = PerceptualHash.dHash(gradient(64, 64), 64, 64)
        val b = PerceptualHash.dHash(gradient(64, 64, flip = true), 64, 64)
        assertEquals(64, PerceptualHash.hamming(a, b))
    }
}
