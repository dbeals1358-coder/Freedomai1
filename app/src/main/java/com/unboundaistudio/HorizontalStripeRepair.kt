package com.unboundaistudio

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Fast, non-AI recovery for an editing backend that drew a damaged horizontal band.
 *
 * Reuses the original photo's pixels instead of inventing facial detail or smearing the
 * damaged image. It only changes the selected rows; feathering avoids hard horizontal edges.
 * The original and edited frames must have matching composition for good results.
 */
internal object HorizontalStripeRepair {
    fun restoreFromOriginal(
        edited: Bitmap,
        original: Bitmap,
        centerFraction: Float,
        bandFraction: Float = 0.045f
    ): Bitmap {
        require(edited.width > 0 && edited.height > 0) { "Empty edited image" }
        require(original.width > 0 && original.height > 0) { "Empty original image" }
        require(centerFraction in 0f..1f) { "Line position must be between 0 and 1" }
        require(bandFraction in 0.005f..0.2f) { "Invalid repair band size" }

        val width = edited.width
        val height = edited.height
        val aligned = if (original.width == width && original.height == height) original
                      else Bitmap.createScaledBitmap(original, width, height, true)
        val restored = edited.copy(Bitmap.Config.ARGB_8888, true)
        try {
            val centerY = (centerFraction * (height - 1)).roundToInt()
            val half = (height * bandFraction / 2f).roundToInt().coerceAtLeast(2)
            val feather = (half / 2).coerceAtLeast(2)
            val y0 = (centerY - half).coerceAtLeast(0)
            val y1 = (centerY + half).coerceAtMost(height - 1)
            val srcRow = IntArray(width)
            val dstRow = IntArray(width)
            for (y in (y0 - feather).coerceAtLeast(0)..(y1 + feather).coerceAtMost(height - 1)) {
                val weight = blendWeight(y, y0, y1, feather)
                if (weight <= 0f) continue
                aligned.getPixels(srcRow, 0, width, 0, y, width, 1)
                restored.getPixels(dstRow, 0, width, 0, y, width, 1)
                for (x in 0 until width) dstRow[x] = blend(dstRow[x], srcRow[x], weight)
                restored.setPixels(dstRow, 0, width, 0, y, width, 1)
            }
            return restored
        } finally {
            if (aligned !== original) aligned.recycle()
        }
    }

    internal fun blendWeight(y: Int, first: Int, last: Int, feather: Int): Float {
        require(feather > 0)
        if (y < first - feather || y > last + feather) return 0f
        if (y in first..last) return 1f
        return if (y < first) (y - (first - feather)).toFloat() / feather
               else ((last + feather) - y).toFloat() / feather
    }

    internal fun blend(edited: Int, original: Int, weight: Float): Int {
        val f = weight.coerceIn(0f, 1f)
        if (f <= 0f) return edited
        if (f >= 1f) return original
        fun channel(shift: Int): Int {
            val a = (edited ushr shift) and 255
            val b = (original ushr shift) and 255
            return (a + (b - a) * f).roundToInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or
            (channel(8) shl 8) or channel(0)
    }
}
