package com.unboundaistudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Draws the actual English words on the finished cartoon. Nightmare must render
 * an image without letters first; text is drawn by Android, not by diffusion.
 * The deliberately simple top-row layout does not claim to find speaker faces.
 */
internal object CartoonLetteringOverlay {
    fun render(bitmap: Bitmap, words: List<String>): Bitmap {
        if (words.isEmpty() || bitmap.width < 320 || bitmap.height < 320) return bitmap
        val phrases = words.take(3)
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("Could not prepare the cartoon for English lettering")
        val canvas = Canvas(result)
        val w = result.width.toFloat()
        val h = result.height.toFloat()
        val count = phrases.size
        val gap = w * 0.018f
        val outer = w * 0.045f
        val bubbleWidth = (w - 2 * outer - gap * (count - 1)) / count
        val top = h * 0.025f
        val bottom = h * (if (count == 3) 0.255f else 0.225f)
        val border = (w / 340f).coerceAtLeast(2f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = border
            strokeJoin = Paint.Join.ROUND
        }
        for (i in phrases.indices) {
            val left = outer + i * (bubbleWidth + gap)
            val right = left + bubbleWidth
            val rect = RectF(left, top, right, bottom)
            val radius = (bubbleWidth * 0.12f).coerceAtMost(40f)
            // Tails point toward the picture below the bubbles, not an
            // unverified speaker location.
            val center = (left + right) / 2
            val tail = Path().apply {
                moveTo(center - w * 0.018f, bottom - border)
                lineTo(center + w * 0.018f, bottom - border)
                lineTo(center, bottom + h * 0.045f)
                close()
            }
            canvas.drawPath(tail, fill)
            canvas.drawPath(tail, outline)
            canvas.drawRoundRect(rect, radius, radius, fill)
            canvas.drawRoundRect(rect, radius, radius, outline)
            val pad = (bubbleWidth * 0.08f).coerceAtLeast(7f)
            val availWidth = (bubbleWidth - 2 * pad).toInt().coerceAtLeast(1)
            val availHeight = (bottom - top - 2 * pad).toInt().coerceAtLeast(1)
            val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            var layout: StaticLayout
            var size = (w * 0.040f).coerceIn(16f, 44f)
            do {
                ink.textSize = size
                layout = StaticLayout.Builder.obtain(phrases[i], 0, phrases[i].length, ink, availWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
                if (layout.height <= availHeight || size <= 11f) break
                size -= 1.5f
            } while (true)
            canvas.save()
            canvas.translate(left + pad, top + (bottom - top - layout.height) / 2)
            layout.draw(canvas)
            canvas.restore()
        }
        return result
    }
}
