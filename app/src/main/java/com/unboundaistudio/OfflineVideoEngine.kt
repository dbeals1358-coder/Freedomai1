package com.unboundaistudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.math.sin
import kotlin.math.cos

/** On-device photo animation (pan/zoom), not generative AI or articulated human motion. */
object OfflineVideoEngine {
 fun renderFrames(frames: List<Bitmap>, seconds: Int, output: File, progress: (Int) -> Unit) {
  require(frames.isNotEmpty()) { "No decoded MobileI2V frames" }
  render(frames.first(), seconds, "", output, progress, frames)
 }
 fun render(photo: Bitmap, seconds: Int, motion: String, output: File, progress: (Int) -> Unit, decodedFrames: List<Bitmap>? = null) {
  require(seconds in 1..120)
  val w = decodedFrames?.firstOrNull()?.width ?: 512
  val h = decodedFrames?.firstOrNull()?.height ?: 512
  require(w in 16..1024 && h in 16..1024 && w % 2 == 0 && h % 2 == 0) { "Unsupported video frame dimensions" }
  val fps = 24
  val total = seconds * fps
  val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
   setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
   setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
   setInteger(MediaFormat.KEY_FRAME_RATE, fps)
   setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
  }
  val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
  val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
  var started = false
  var track = -1
  val info = MediaCodec.BufferInfo()
  val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(frame)
  val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
  val pixels = IntArray(w * h)
  var submitted = 0
  var ended = false
  var eosQueued = false
  val lower = motion.lowercase()
  try {
   codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
   codec.start()
   while (!ended) {
    if (submitted < total) {
     val inputIndex = codec.dequeueInputBuffer(10000)
     if (inputIndex >= 0) {
      val t = submitted.toDouble() / fps
      val cycle = 2.0 * Math.PI * t / seconds
      val zoom = if ("zoom out" in lower) 1.15 - 0.13 * submitted / total else if ("zoom" in lower) 1.02 + 0.13 * submitted / total else 1.07 + 0.04 * sin(cycle)
      val horizontal = if ("left" in lower) -0.06 * submitted / total else if ("right" in lower) 0.06 * submitted / total else 0.025 * sin(cycle)
      val vertical = if ("up" in lower) -0.05 * submitted / total else if ("down" in lower) 0.05 * submitted / total else 0.012 * cos(cycle)
      canvas.drawColor(Color.BLACK)
      val selected = decodedFrames?.let { frames ->
       frames[(submitted.toLong() * frames.size / total).toInt().coerceIn(0, frames.lastIndex)]
      } ?: photo
      val effectiveZoom = if (decodedFrames == null) zoom.toFloat() else 1f
      val scale = maxOf(w.toFloat() / selected.width, h.toFloat() / selected.height) * effectiveZoom
      val sw = selected.width * scale
      val sh = selected.height * scale
      val dx = (w - sw) / 2f + (if (decodedFrames == null) horizontal.toFloat() * w else 0f)
      val dy = (h - sh) / 2f + (if (decodedFrames == null) vertical.toFloat() * h else 0f)
      canvas.drawBitmap(selected, null, RectF(dx, dy, dx + sw, dy + sh), paint)
      frame.getPixels(pixels, 0, w, 0, 0, w, h)
      val yuv = codec.getInputImage(inputIndex) ?: error("Encoder does not expose YUV input")
      try {
       val planes = yuv.planes
       for (y in 0 until h step 2) for (x in 0 until w step 2) {
        var uSum = 0; var vSum = 0
        for (dy2 in 0..1) for (dx2 in 0..1) {
         val xx = x + dx2; val yy = y + dy2
         val c = pixels[yy * w + xx]
         val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
         val yyv = (16 + (66*r + 129*g + 25*b + 128)/256).coerceIn(0,255)
         val uv = ((-38*r - 74*g + 112*b + 128)/256 + 128).coerceIn(0,255)
         val vv = ((112*r - 94*g - 18*b + 128)/256 + 128).coerceIn(0,255)
         val yp = planes[0]
         yp.buffer.put(yy * yp.rowStride + xx * yp.pixelStride, yyv.toByte())
         uSum += uv; vSum += vv
        }
        val up = planes[1]; val vp = planes[2]
        up.buffer.put((y/2) * up.rowStride + (x/2) * up.pixelStride, (uSum/4).toByte())
        vp.buffer.put((y/2) * vp.rowStride + (x/2) * vp.pixelStride, (vSum/4).toByte())
       }
      } finally { yuv.close() }
      codec.queueInputBuffer(inputIndex, 0, w * h * 3 / 2, submitted * 1_000_000L / fps, 0)
      submitted++
      if (submitted % fps == 0) progress(submitted * 100 / total)
     }
    } else if (!eosQueued) {
     val index = codec.dequeueInputBuffer(10000)
     if (index >= 0) { codec.queueInputBuffer(index, 0, 0, total * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosQueued = true }
    }
    while (true) {
     val outIndex = codec.dequeueOutputBuffer(info, 10000)
     if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
     if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
      check(!started)
      track = muxer.addTrack(codec.outputFormat)
      muxer.start(); started = true
     } else if (outIndex >= 0) {
      if (info.size > 0) {
       check(started)
       val data = codec.getOutputBuffer(outIndex) ?: error("Missing encoded data")
       data.position(info.offset); data.limit(info.offset + info.size)
       muxer.writeSampleData(track, data, info)
      }
      ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
      codec.releaseOutputBuffer(outIndex, false)
     }
    }
   }
  } finally {
   frame.recycle()
   runCatching { codec.stop() }; codec.release()
   if (started) runCatching { muxer.stop() }
   muxer.release()
  }
 }
}
