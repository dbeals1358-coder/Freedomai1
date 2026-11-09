package com.unboundaistudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Tracks the exact letterbox used for Nightmare edits. Cropping the returned
 * frame to this rectangle prevents added black/white bands and warped faces.
 */
internal object NightmareEditGeometry {
 data class Bounds(val left: Int, val top: Int, val width: Int, val height: Int)
 data class Frame(
  val sourceWidth: Int, val sourceHeight: Int,
  val contentWidth: Int, val contentHeight: Int,
  val width: Int, val height: Int
 ) {
  val left get() = (width - contentWidth) / 2
  val top get() = (height - contentHeight) / 2

  fun boundsIn(outputWidth: Int, outputHeight: Int): Bounds {
   require(outputWidth > 0 && outputHeight > 0)
   val x0 = floor(left.toDouble() * outputWidth / width).toInt().coerceIn(0, outputWidth - 1)
   val y0 = floor(top.toDouble() * outputHeight / height).toInt().coerceIn(0, outputHeight - 1)
   val x1 = ceil((left + contentWidth).toDouble() * outputWidth / width).toInt().coerceIn(x0 + 1, outputWidth)
   val y1 = ceil((top + contentHeight).toDouble() * outputHeight / height).toInt().coerceIn(y0 + 1, outputHeight)
   return Bounds(x0, y0, x1 - x0, y1 - y0)
  }

  fun encodeSource(input: Bitmap): Bitmap {
   require(input.width == sourceWidth && input.height == sourceHeight)
   if (width == sourceWidth && height == sourceHeight) return input
   val canvasImage = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
   val canvas = Canvas(canvasImage)
   canvas.drawBitmap(input, null,
    Rect(left, top, left + contentWidth, top + contentHeight),
    Paint(Paint.FILTER_BITMAP_FLAG))
   return canvasImage
  }

  fun restoreOutput(output: Bitmap): Bitmap {
   val bounds = boundsIn(output.width, output.height)
   if (bounds.left == 0 && bounds.top == 0 &&
       bounds.width == output.width && bounds.height == output.height) return output
   return Bitmap.createBitmap(output, bounds.left, bounds.top, bounds.width, bounds.height)
  }
 }

 fun plan(sourceWidth: Int, sourceHeight: Int, maxEdge: Int): Frame {
  require(sourceWidth > 0 && sourceHeight > 0 && maxEdge in 512..2048)
  val scale = minOf(1.0, maxEdge.toDouble() / maxOf(sourceWidth, sourceHeight))
  val contentW = (sourceWidth * scale).toInt().coerceAtLeast(1)
  val contentH = (sourceHeight * scale).toInt().coerceAtLeast(1)
  fun align(value: Int) = ((value + 63) / 64 * 64).coerceAtLeast(512)
  return Frame(sourceWidth, sourceHeight, contentW, contentH,
   align(contentW), align(contentH))
 }
}
