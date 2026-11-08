package com.unboundaistudio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.graphics.Bitmap
import kotlin.math.abs

/** Samples actual decoded frames; a duration or file size alone cannot prove motion. */
internal object VideoMotionCheck {
 data class Result(val meanChange: Double, val nearlyStatic: Boolean, val samples: Int, val largeFrameChanges: Int = 0)

 fun inspect(context: Context, uri: Uri): Result {
  val retriever = MediaMetadataRetriever()
  try {
   retriever.setDataSource(context, uri)
   val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
    ?.toLongOrNull() ?: return Result(0.0, false, 0)
   if (durationMs < 300L) return Result(0.0, false, 0)
   var previous: IntArray? = null
   var total = 0.0
   var comparisons = 0
   var largeFrameChanges = 0
   val grid = 32
   for (index in 0 until 6) {
    val timeUs = (durationMs * 1000L * (index + 1) / 7L)
    val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
     ?: continue
    val reduced = Bitmap.createScaledBitmap(frame, grid, grid, true)
    val pixels = IntArray(grid * grid)
    reduced.getPixels(pixels, 0, grid, 0, 0, grid, grid)
    if (reduced !== frame) reduced.recycle()
    frame.recycle()
    previous?.let { prior ->
     var difference = 0L
     for (i in pixels.indices) {
      val a = prior[i]
      val b = pixels[i]
      difference += abs(android.graphics.Color.red(a) - android.graphics.Color.red(b))
      difference += abs(android.graphics.Color.green(a) - android.graphics.Color.green(b))
      difference += abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
     }
     val change = difference.toDouble() / (pixels.size * 3 * 255)
     total += change
     if (change > 0.10) largeFrameChanges++
     comparisons++
    }
    previous = pixels
   }
   val mean = if (comparisons == 0) 0.0 else total / comparisons
   // Conservative heuristic: compression noise alone can create tiny differences.
   return Result(mean, comparisons >= 3 && mean < 0.012, comparisons + 1, largeFrameChanges)
  } finally {
   retriever.release()
  }
 }
}
