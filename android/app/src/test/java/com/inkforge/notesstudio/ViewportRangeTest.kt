package com.inkforge.notesstudio

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class ViewportRangeTest {
    @Test fun indexedCandidatesPreserveOriginalCullingAcrossScrollZoomAndBoundaries() {
        val random = Random(413)
        val heights = FloatArray(10_000) { 120f + random.nextInt(3000) }
        val tops = FloatArray(heights.size)
        val bottoms = FloatArray(heights.size)
        var y = 0f
        heights.indices.forEach { index -> tops[index] = y; bottoms[index] = y + heights[index]; y = bottoms[index] + 28f }
        for (scale in listOf(.08f, .3f, 1f, 2.6f, 8f)) repeat(80) { query ->
            val index = random.nextInt(heights.size)
            val origin = if (query % 2 == 0) -tops[index] * scale + 1140f else -bottoms[index] * scale - 500f
            for (offset in listOf(-.01f, 0f, .01f)) {
                val pan = origin + offset
                fun visible(i: Int): Boolean {
                    val top = pan + tops[i] * scale
                    return !(top > 1140f || top + heights[i] * scale < -500f)
                }
                val actual = pageViewportRange(tops, bottoms, (-500f - pan) / scale, (1140f - pan) / scale, true)
                assertEquals(tops.indices.filter(::visible), actual.filter(::visible))
                assertTrue("Offscreen traversal is not bounded", actual.count() <= 80)
            }
        }
    }

    @Test fun emptySingleOffscreenAndMalformedGeometryKeepSafeRanges() {
        assertTrue(pageViewportRange(floatArrayOf(), floatArrayOf(), 0f, 1f, true).isEmpty())
        assertEquals(listOf(0), pageViewportRange(floatArrayOf(0f), floatArrayOf(1414f), 0f, 1414f, true).toList())
        val tops = floatArrayOf(0f, 1442f, 2884f)
        val bottoms = floatArrayOf(1414f, 2856f, 4298f)
        assertTrue(pageViewportRange(tops, bottoms, 9000f, 9100f, true).count() <= 1)
        assertEquals(tops.indices.toList(), pageViewportRange(tops, bottoms, Float.NaN, 500f, true).toList())
        assertEquals(tops.indices.toList(), pageViewportRange(tops, bottoms, 1000f, 2000f, false).toList())
    }
}
