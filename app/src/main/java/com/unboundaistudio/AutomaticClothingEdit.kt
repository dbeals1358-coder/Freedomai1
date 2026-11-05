package com.unboundaistudio

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Conservative, fully on-device clothing-color editing for a front-facing bikini portrait.
 *
 * This is a pixel-level recolor, NOT a generative replacement or a semantic clothing segmenter.
 * It deliberately changes only identifiable, saturated fabric pixels inside two anatomically
 * approximate image regions; outside those pixels the source image is copied byte-for-byte.
 * If there are no confident fabric candidates, it fails closed instead of recreating the person.
 * Other garment shapes and structural edits still need a real mask/inpainting model.
 */
internal object AutomaticClothingEdit {
    data class Request(val color: String, val hue: Float, val top: Boolean, val bottom: Boolean)

    private val colorNames = mapOf(
        "red" to 0f, "crimson" to 355f,
        "orange" to 22f, "yellow" to 52f,
        "green" to 125f, "teal" to 175f, "turquoise" to 180f,
        "blue" to 221f, "navy" to 232f,
        "purple" to 275f, "pink" to 330f, "magenta" to 305f
    )
    private val colors = Regex("\\b(red|crimson|orange|yellow|green|teal|turquoise|blue|navy|purple|pink|magenta)\\b", RegexOption.IGNORE_CASE)
    private val bikini = Regex("\\b(bikini|two[- ]piece)\\b", RegexOption.IGNORE_CASE)
    private val otherChanges = Regex("\\b(nude|naked|remove|erase|add|shorts|jeans|pants|dress|skirt|smaller|larger|resize|replace|reposition|background to|different person)\\b", RegexOption.IGNORE_CASE)

    /** A short color-only command, e.g. 'red bikini' or 'change the blue bikini to red'. */
    fun parse(prompt: String): Request? {
        if (!bikini.containsMatchIn(prompt) || otherChanges.containsMatchIn(prompt)) return null
        val matches = colors.findAll(prompt).toList()
        if (matches.isEmpty()) return null
        val name = matches.last().value.lowercase()
        val targetHue = colorNames[name] ?: return null
        val topOnly = Regex("\\bbikini\\s+top\\b|\\btop\\s+of\\s+(the\\s+)?bikini\\b", RegexOption.IGNORE_CASE).containsMatchIn(prompt)
        val bottomOnly = Regex("\\bbikini\\s+bottoms?\\b|\\bbottoms?\\s+of\\s+(the\\s+)?bikini\\b", RegexOption.IGNORE_CASE).containsMatchIn(prompt)
        val top = topOnly || !bottomOnly
        val bottom = bottomOnly || !topOnly
        return Request(name, targetHue, top, bottom)
    }

    /**
     * Returns null if garment fabric cannot be confidently distinguished from the surrounding
     * photo. This mode intentionally supports vivid non-skin source fabric (blue, green, purple,
     * etc.) and retains patterns/prints; white, black and skin-toned fabric need actual segmentation.
     */
    fun recolor(source: Bitmap, request: Request): Bitmap? {
        val w = source.width
        val h = source.height
        if (w < 180 || h < 180 || w.toLong() * h > 5_000_000L) return null

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val hsv = FloatArray(3)
        val histogram = IntArray(36)

        for (y in 0 until h) for (x in 0 until w) {
            if (!inGarmentRegion(x, y, w, h, request)) continue
            val pixel = pixels[y * w + x]
            if (Color.alpha(pixel) < 200) continue
            Color.colorToHSV(pixel, hsv)
            if (hsv[1] >= 0.60f && hsv[2] > 0.26f && hsv[0] in 70f..320f) {
                histogram[(hsv[0] / 10).toInt().coerceIn(0, 35)]++
            }
        }

        // Smooth adjacent hue bins: a turquoise fabric may straddle a 10-degree boundary.
        val peak = (7..31).maxByOrNull { bin ->
            histogram[bin - 1] + histogram[bin] + histogram[bin + 1]
        } ?: return null
        val strongCount = histogram[peak - 1] + histogram[peak] + histogram[peak + 1]
        if (strongCount < max(80, (w * h) / 3000)) return null

        val fabricHue = peak * 10f + 5f
        if (hueDistance(fabricHue, request.hue) < 18f) return null

        val seed = BooleanArray(w * h)
        var confidentPixels = 0
        var corePixels = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (!inGarmentRegion(x, y, w, h, request)) continue
            val idx = y * w + x
            if (Color.alpha(pixels[idx]) < 200) continue
            Color.colorToHSV(pixels[idx], hsv)
            if (hsv[1] > 0.60f && hsv[2] > 0.28f && hueDistance(hsv[0], fabricHue) <= 24f) {
                seed[idx] = true
                confidentPixels++
                val xr = x.toFloat() / w
                val yr = y.toFloat() / h
                if ((request.top && xr in 0.35f..0.65f && yr in 0.42f..0.60f) ||
                    (request.bottom && xr in 0.36f..0.64f && yr in 0.87f..0.99f)) {
                    corePixels++
                }
            }
        }
        // A vivid ocean or foliage must not be mistaken for a bikini.
        if (confidentPixels < max(80, (w * h) / 3000) ||
            corePixels < max(30, (w * h) / 18000)) return null

        val closeToFabric = BooleanArray(w * h)
        val radius = (min(w, h) * 0.008f).roundToInt().coerceIn(2, 7)
        for (y in 0 until h) for (x in 0 until w) {
            if (!seed[y * w + x]) continue
            for (yy in max(0, y - radius)..min(h - 1, y + radius)) {
                for (xx in max(0, x - radius)..min(w - 1, x + radius)) {
                    closeToFabric[yy * w + xx] = true
                }
            }
        }

        val recolored = pixels.copyOf()
        var changed = 0
        for (y in 0 until h) for (x in 0 until w) {
            val idx = y * w + x
            if (!closeToFabric[idx] || !inGarmentRegion(x, y, w, h, request)) continue
            val pixel = pixels[idx]
            if (Color.alpha(pixel) < 200) continue
            Color.colorToHSV(pixel, hsv)
            if (hueDistance(hsv[0], fabricHue) > 29f || hsv[1] < 0.20f || hsv[2] < 0.20f) continue
            val mix = ((hsv[1] - 0.20f) / 0.34f).coerceIn(0f, 1f)
            if (mix <= 0f) continue
            val updated = Color.HSVToColor(Color.alpha(pixel), floatArrayOf(request.hue, max(0.65f, hsv[1]), hsv[2]))
            val r = (Color.red(pixel) * (1f - mix) + Color.red(updated) * mix).roundToInt().coerceIn(0, 255)
            val g = (Color.green(pixel) * (1f - mix) + Color.green(updated) * mix).roundToInt().coerceIn(0, 255)
            val b = (Color.blue(pixel) * (1f - mix) + Color.blue(updated) * mix).roundToInt().coerceIn(0, 255)
            val changedPixel = Color.argb(Color.alpha(pixel), r, g, b)
            if (changedPixel != pixel) {
                recolored[idx] = changedPixel
                changed++
            }
        }
        if (changed < max(70, (w * h) / 6000)) return null
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(recolored, 0, w, 0, 0, w, h)
        }
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val distance = abs(a - b) % 360f
        return min(distance, 360f - distance)
    }

    private fun inGarmentRegion(x: Int, y: Int, w: Int, h: Int, request: Request): Boolean {
        val xr = x.toFloat() / w
        val yr = y.toFloat() / h
        return ((request.top && xr in 0.27f..0.73f && yr in 0.34f..0.64f) ||
                (request.bottom && xr in 0.27f..0.73f && yr in 0.77f..1f))
    }
}
