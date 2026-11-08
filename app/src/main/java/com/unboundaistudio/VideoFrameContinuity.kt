package com.unboundaistudio

import kotlin.math.sqrt

/**
 * Conservative check for lost image conditioning in a photo-to-video clip:
 * a recognizable first frame followed by unrelated colorful static.
 *
 * Ordinary motion, mild camera shifts and compression are not enough to trigger
 * this check. The detector operates on several evenly sampled 64x64 grayscale
 * images and never alters the user's video or original photo.
 */
internal object VideoFrameContinuity {
    data class Assessment(
        val likelyCorrupted: Boolean,
        val failedComparisons: Int,
        val testedComparisons: Int
    )

    fun assess(frames: List<IntArray>): Assessment {
        require(frames.size >= 5) { "Need at least five sampled video frames" }
        val size = frames.first().size
        require(size >= 256 && frames.all { it.size == size }) {
            "All frame samples must have the same dimensions"
        }
        val reference = frames.first()
        val comparisons = frames.drop(1) // Include the final video frames.
        val lost = comparisons.count { frame ->
            val correlation = correlation(reference, frame)
            val delta = absoluteDifference(reference, frame)
            // A genuine short image-to-video clip should normally retain
            // recognizable spatial structure from its original photograph.
            // The sampled bad Wan clip has correlation near zero and ~0.2
            // normalized pixel change against the recognizable first frame.
            correlation < 0.14 && delta > 0.17
        }
        return Assessment(lost >= 3, lost, comparisons.size)
    }

    internal fun absoluteDifference(a: IntArray, b: IntArray): Double {
        require(a.size == b.size && a.isNotEmpty())
        var sum = 0L
        for (index in a.indices) {
            sum += kotlin.math.abs(a[index] - b[index])
        }
        return sum.toDouble() / a.size / 255.0
    }

    internal fun correlation(a: IntArray, b: IntArray): Double {
        require(a.size == b.size && a.isNotEmpty())
        val n = a.size
        var aSum = 0.0
        var bSum = 0.0
        for (index in 0 until n) {
            aSum += a[index]
            bSum += b[index]
        }
        val meanA = aSum / n
        val meanB = bSum / n
        var cross = 0.0
        var aVariance = 0.0
        var bVariance = 0.0
        for (index in 0 until n) {
            val da = a[index] - meanA
            val db = b[index] - meanB
            cross += da * db
            aVariance += da * da
            bVariance += db * db
        }
        if (aVariance < 1e-6 || bVariance < 1e-6) return 0.0
        return cross / sqrt(aVariance * bVariance)
    }
}
