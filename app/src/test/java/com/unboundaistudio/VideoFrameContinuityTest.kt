package com.unboundaistudio

import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class VideoFrameContinuityTest {
    private val width = 64
    private val height = 64
    private fun photo() = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        ((x * 2 + y * 2) + if (x in 18..45 && y in 12..50) 60 else 0).coerceIn(0, 255)
    }

    @Test fun detectsPhotoFollowedByUnrelatedNoise() {
        val source = photo()
        val random = Random(777)
        val frames = listOf(source) + (0 until 6).map {
            IntArray(source.size) { random.nextInt(256) }
        }
        val result = VideoFrameContinuity.assess(frames)
        assertTrue("Noise after source frame must be rejected", result.likelyCorrupted)
        assertTrue(result.failedComparisons >= 3)
    }

    @Test fun acceptsSimilarMovingFrames() {
        val source = photo()
        val frames = (0 until 7).map { offset ->
            IntArray(source.size) { index ->
                (source[index] + offset * 2).coerceAtMost(255)
            }
        }
        assertFalse(VideoFrameContinuity.assess(frames).likelyCorrupted)
    }

    @Test fun acceptsFlatStableVideoWithoutFalseNoiseAlert() {
        val frame = IntArray(width * height) { 125 }
        assertFalse(VideoFrameContinuity.assess(List(7) { frame }).likelyCorrupted)
    }

    @Test fun rejectsMismatchedFrameLengths() {
        try {
            VideoFrameContinuity.assess(
                listOf(IntArray(4096), IntArray(4096), IntArray(4096), IntArray(4096), IntArray(5))
            )
            fail("Expected mismatched frame size rejection")
        } catch (_: IllegalArgumentException) { }
    }
}
