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


 /** Conservative multicolor horizontal corruption cleanup.
  * Looks for a narrow row band containing widespread high-contrast color outliers.
  * This is interpolation, not semantic AI inpainting; uncertain regions stay intact. */
 private fun repairColorBand(source: Bitmap): Bitmap {
  val w = source.width
  val h = source.height
  if (w < 64 || h < 32) return source
  val pixels = IntArray(w * h)
  source.getPixels(pixels, 0, w, 0, 0, w, h)
  val rowHits = IntArray(h)
  val flags = BooleanArray(w * h)
  fun channel(p: Int, shift: Int) = (p ushr shift) and 255
  for (y in 5 until h - 5) {
   for (x in 0 until w) {
    val i = y * w + x
    val p = pixels[i]
    val a = pixels[(y - 5) * w + x]
    val b = pixels[(y + 5) * w + x]
    val r = channel(p,16); val g = channel(p,8); val blue = channel(p,0)
    val maxC = maxOf(r,g,blue); val minC = minOf(r,g,blue)
    val highChroma = maxC - minC > 65
    val unusualGreen = g > r * 1.35 && g > blue * 1.2 && g > 90
    val unusualMagenta = r > g * 1.4 && blue > g * 1.3 && r > 90
    val contrast = (listOf(16,8,0).sumOf { shift ->
     kotlin.math.abs(channel(p,shift) - (channel(a,shift)+channel(b,shift))/2)
    })
    if ((highChroma || unusualGreen || unusualMagenta) && contrast > 115) {
     flags[i] = true
     rowHits[y]++
    }
   }
  }
  // Widespread in a narrow horizontal interval; do not mistake ordinary scenery.
  val dense = BooleanArray(h)
  for (y in 5 until h - 5) {
   var total = 0
   for (dy in -3..3) total += rowHits[y + dy]
   dense[y] = total > w * 0.65
  }
  val candidates = mutableListOf<IntRange>()
  var y = 5
  while (y < h - 5) {
   if (!dense[y]) { y++; continue }
   val first = y
   while (y < h - 5 && dense[y]) y++
   if (y - first <= 42) candidates.add(first..(y-1))
  }
  if (candidates.isEmpty()) return source
  val out = pixels.copyOf()
  var changed = 0
  for (band in candidates) {
   val top = maxOf(0, band.first - 5)
   val bottom = minOf(h - 1, band.last + 5)
   if (bottom - top > 55) continue
   // Repair only columns showing evidence of corruption in this band.
   for (x in 0 until w) {
    var hits = 0
    for (yy in top..bottom) if (flags[yy*w+x]) hits++
    if (hits == 0) continue
    val from = maxOf(0, top - 4)
    val to = minOf(h-1, bottom + 4)
    for (yy in top..bottom) {
     val t = (yy-from).toFloat()/(to-from)
     val a = pixels[from*w+x]; val b = pixels[to*w+x]
     fun mix(s: Int): Int = (channel(a,s)*(1f-t)+channel(b,s)*t).toInt().coerceIn(0,255)
     out[yy*w+x] = (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
     changed++
    }
   }
  }
  return if (changed == 0) source else Bitmap.createBitmap(out,w,h,Bitmap.Config.ARGB_8888)
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
  // Repair high-confidence multicolor horizontal corruption before upscaling.
  // This is deliberately conservative: arbitrary unusual objects are not defects.
  val cleaned = repairColorBand(source)
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
