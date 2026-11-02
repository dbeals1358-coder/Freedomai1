package com.unboundaistudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** Offline photo animation: pan/zoom MP4, NOT generative AI motion. */
internal object PhotoMotionEncoder {
 fun render(photo: Bitmap, output: File, motion: String = "Zoom in") {
  val width = 512; val height = 512; val fps = 24; val frames = 72
  val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
   setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
   setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
   setInteger(MediaFormat.KEY_FRAME_RATE, fps)
   setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
  }
  val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
  var muxer: MediaMuxer? = null
  var started = false
  var track = -1
  try {
   codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
   val surface = codec.createInputSurface()
   codec.start()
   muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
   val info = MediaCodec.BufferInfo()
   fun drain(waitForEnd: Boolean) {
    var attempts = 0
    while (true) {
     val index = codec.dequeueOutputBuffer(info, if (waitForEnd) 10000 else 0)
     if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
      if (!waitForEnd || ++attempts > 500) break
     } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
      check(!started) { "Encoder changed format twice" }
      track = muxer!!.addTrack(codec.outputFormat)
      muxer!!.start(); started = true
     } else if (index >= 0) {
      val buffer = codec.getOutputBuffer(index) ?: error("Missing encoded frame")
      if (info.size > 0) {
       check(started) { "Encoder produced data before format" }
       buffer.position(info.offset); buffer.limit(info.offset + info.size)
       muxer!!.writeSampleData(track, buffer, info)
      }
      val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
      codec.releaseOutputBuffer(index, false)
      if (done) break
     }
    }
   }
   val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
   for (frame in 0 until frames) {
    val canvas: Canvas = surface.lockCanvas(null)
    try {
     canvas.drawColor(Color.BLACK)
     val t = frame.toFloat() / (frames - 1)
     val eased = t * t * (3f - 2f * t)
     val scale = when (motion) {
      "Zoom out" -> 1.16f - 0.12f * eased
      "Pan left", "Pan right" -> 1.12f
      else -> 1.04f + 0.12f * eased
     }
     val fit = maxOf(width.toFloat() / photo.width, height.toFloat() / photo.height) * scale
     val dw = photo.width * fit; val dh = photo.height * fit
     val horizontal = when (motion) {
      "Pan left" -> 0.85f - 0.7f * eased
      "Pan right" -> 0.15f + 0.7f * eased
      else -> 0.5f
     }
     val x = (width - dw) * horizontal
     val y = (height - dh) / 2
     canvas.drawBitmap(photo, null, android.graphics.RectF(x, y, x + dw, y + dh), paint)
    } finally { surface.unlockCanvasAndPost(canvas) }
    drain(false)
   }
   codec.signalEndOfInputStream()
   drain(true)
   surface.release()
  } finally {
   runCatching { codec.stop() }
   codec.release()
   runCatching { if (started) muxer?.stop() }
   muxer?.release()
  }
 }
}
