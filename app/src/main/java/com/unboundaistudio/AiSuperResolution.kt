package com.unboundaistudio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer

/** Real-ESRGAN x4 (RGB float32 NCHW). CPU tiles limit peak inference memory. */
object AiSuperResolution {
 private const val MODEL_URL = "https://huggingface.co/Heliosoph/realesrgan-onnx/resolve/main/realesr-general-x4v3.onnx"
 private const val TILE = 64
 // Wider context reduces visible seams at 64px tile boundaries (especially water and skin).
 private const val PAD = 24


 /** Detect thin neon-green scanline corruption spanning much of a row.
  * Replaces only flagged pixels by vertical interpolation; leaves normal foliage alone. */
 private fun repairGreenBand(source: Bitmap): Bitmap {
  val w = source.width
  val h = source.height
  if (w < 32 || h < 16) return source
  val pixels = IntArray(w * h)
  source.getPixels(pixels, 0, w, 0, 0, w, h)
  val flagged = BooleanArray(w * h)
  val counts = IntArray(h)
  for (y in 2 until h - 2) {
   for (x in 0 until w) {
    val i = y * w + x
    val p = pixels[i]
    val r = p ushr 16 and 255
    val g = p ushr 8 and 255
    val b = p and 255
    val above = pixels[(y - 2) * w + x]
    val below = pixels[(y + 2) * w + x]
    val adjacentGreen = ((above ushr 8 and 255) + (below ushr 8 and 255)) / 2
    if (g > 110 && g > r * 1.6 && g > b * 1.35 && g - adjacentGreen > 45) {
     flagged[i] = true
     counts[y]++
    }
   }
  }
  // Require an implausibly widespread, narrow scanline rather than local green details.
  val suspect = BooleanArray(h)
  for (y in 2 until h - 2) {
   val nearby = (maxOf(2, y - 2)..minOf(h - 3, y + 2)).sumOf { counts[it] }
   if (nearby > w * 0.38) suspect[y] = true
  }
  if (!suspect.any { it }) return source
  val result = pixels.copyOf()
  var changes = 0
  for (y in 2 until h - 2) {
   if (!suspect[y]) continue
   for (x in 0 until w) {
    val i = y * w + x
    if (!flagged[i]) continue
    var top = y - 1
    var bottom = y + 1
    while (top > 0 && flagged[top * w + x]) top--
    while (bottom < h - 1 && flagged[bottom * w + x]) bottom++
    if (y - top > 12 || bottom - y > 12) continue
    val a = pixels[top * w + x]
    val b = pixels[bottom * w + x]
    val weight = (y - top).toFloat() / (bottom - top)
    fun blend(shift: Int): Int {
     val av = a ushr shift and 255
     val bv = b ushr shift and 255
     return (av + (bv - av) * weight).toInt().coerceIn(0, 255)
    }
    result[i] = (0xff shl 24) or (blend(16) shl 16) or (blend(8) shl 8) or blend(0)
    changes++
   }
  }
  if (changes == 0) return source
  return Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
 }

 private fun modelFile(context: Context): File {
  val target = File(context.filesDir, "realesr-general-x4v3.onnx")
  if (target.length() > 1_000_000L) return target
  val temp = File(context.cacheDir, "realesr-download.tmp")
  try {
   val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
   connection.connectTimeout = 20000
   connection.readTimeout = 60000
   connection.instanceFollowRedirects = true
   try {
    check(connection.responseCode in 200..299) { "Model download HTTP ${connection.responseCode}" }
    connection.inputStream.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
   } finally { connection.disconnect() }
   check(temp.length() > 1_000_000L) { "AI model download incomplete" }
   check(temp.renameTo(target)) { "Cannot install AI model" }
  } finally { temp.delete() }
  return target
 }

 suspend fun upscale(context: Context, source: Bitmap, onProgress: (Int) -> Unit = {}): Bitmap {
  onProgress(0)
  val longest = maxOf(source.width, source.height)
  require(longest in 1..4096) { "Image dimensions unsupported" }
  val targetWidth = (source.width.toLong() * 4096L / longest).toInt().coerceAtLeast(1)
  val targetHeight = (source.height.toLong() * 4096L / longest).toInt().coerceAtLeast(1)
  // Repair only high-confidence, thin, horizontally continuous green corruption.
  // This is deliberately conservative: arbitrary unusual objects are not defects.
  val cleaned = repairGreenBand(source)
  // A 4x model must process no more than 1024 input pixels on its longest edge.
  val working = if (longest > 1024) Bitmap.createScaledBitmap(cleaned,
   (source.width * 1024f / longest).toInt().coerceAtLeast(1),
   (source.height * 1024f / longest).toInt().coerceAtLeast(1), true) else cleaned
  val output = Bitmap.createBitmap(working.width * 4, working.height * 4, Bitmap.Config.ARGB_8888)
  val tileColumns = (working.width + TILE - 1) / TILE
  val tileRows = (working.height + TILE - 1) / TILE
  val totalTiles = tileColumns * tileRows
  var completedTiles = 0
  val env = OrtEnvironment.getEnvironment()
  val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
  try {
   env.createSession(modelFile(context).absolutePath, opts).use { session ->
    val canvas = Canvas(output)
    for (top in 0 until working.height step TILE) {
     for (left in 0 until working.width step TILE) {
      coroutineContext.ensureActive()
      val cw = minOf(TILE, working.width - left)
      val ch = minOf(TILE, working.height - top)
      val x0 = maxOf(0, left - PAD)
      val y0 = maxOf(0, top - PAD)
      val x1 = minOf(working.width, left + cw + PAD)
      val y1 = minOf(working.height, top + ch + PAD)
      val w = x1 - x0
      val h = y1 - y0
      val pixels = IntArray(w * h)
      working.getPixels(pixels, 0, w, x0, y0, w, h)
      val rgb = FloatArray(3 * w * h)
      for (i in pixels.indices) {
       val p = pixels[i]
       rgb[i] = ((p ushr 16) and 255) / 255f
       rgb[w*h+i] = ((p ushr 8) and 255) / 255f
       rgb[2*w*h+i] = (p and 255) / 255f
      }
      OnnxTensor.createTensor(env, FloatBuffer.wrap(rgb), longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
       session.run(mapOf("input" to tensor)).use { result ->
        @Suppress("UNCHECKED_CAST")
        val values = result[0].value as Array<Array<Array<FloatArray>>>
        val channels = values[0]
        val outW = w * 4
        val outH = h * 4
        val argb = IntArray(outW * outH)
        for (i in argb.indices) {
         val yy = i / outW
         val xx = i % outW
         val red = (channels[0][yy][xx] * 255f).toInt().coerceIn(0,255)
         val green = (channels[1][yy][xx] * 255f).toInt().coerceIn(0,255)
         val blue = (channels[2][yy][xx] * 255f).toInt().coerceIn(0,255)
         argb[i] = -0x1000000 or (red shl 16) or (green shl 8) or blue
        }
        val tile = Bitmap.createBitmap(argb, outW, outH, Bitmap.Config.ARGB_8888)
        val sx = (left-x0)*4
        val sy = (top-y0)*4
        canvas.drawBitmap(tile, Rect(sx,sy,sx+cw*4,sy+ch*4),
         RectF(left*4f,top*4f,(left+cw)*4f,(top+ch)*4f), null)
        tile.recycle()
        completedTiles++
        onProgress(5 + completedTiles * 90 / totalTiles)
       }
      }
     }
    }
   }
  } finally { opts.close() }
  if (working !== source) working.recycle()
  if (cleaned !== source && cleaned !== working) cleaned.recycle()
  if (output.width == targetWidth && output.height == targetHeight) {
   onProgress(100)
   return output
  }
  val resized = Bitmap.createScaledBitmap(output, targetWidth, targetHeight, true)
  output.recycle()
  onProgress(100)
  return resized
 }
}
