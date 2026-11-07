package com.unboundaistudio

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.min
import kotlin.random.Random

/**
 * Experimental native MobileI2V ONNX adapter. Unlike the Nightmare video path,
 * it actually feeds a source photo through the imported VAE, MobileI2V denoiser,
 * and video decoder, and encodes the decoded frames as an MP4.
 *
 * IMPORTANT: official hybrid_371.pth contains no trained text cross-attention.
 * Video is conditioned on the source photo, not on motion-prompt instructions.
 * Output quality must be verified on an actual Android device.
 * Models are loaded sequentially to reduce peak Android memory usage.
 */
internal object MobileI2VVideoEngine {
 private const val SIDE = 512            // 16x16 video latents instead of unusable 2x2
 private const val FRAMES = 17
 private const val CHANNELS = 128
 private const val STEPS = 12
 private const val PROCESS_BUDGET_KB = 4L * 1024 * 1024
 private const val PROCESS_GUARD_KB = 384L * 1024

 private fun checkProcessBudget(stage: String) {
  val usage = android.os.Debug.MemoryInfo()
  android.os.Debug.getMemoryInfo(usage)
  val pssKb = usage.totalPss.toLong()
  Log.i("MobileI2V", "$stage process PSS=${pssKb / 1024} MB")
  if (pssKb >= PROCESS_BUDGET_KB - PROCESS_GUARD_KB) {
   throw IllegalStateException("MobileI2V stopped at $stage before exceeding the 4 GB process memory budget (PSS ${pssKb / 1024} MB). A smaller denoiser is required.")
  }
 }

 fun generate(context: Context, modelsDir: File, cacheDir: File, photo: Bitmap, prompt: String,
              seconds: Int, progress: (Int) -> Unit): NightmareVideoClient.RenderedVideo {
  require(seconds in 1..10) { "Choose 1–10 seconds" }
  val targetSeconds = seconds.coerceAtLeast(3) // Never silently deliver a one-second MobileI2V clip.
  require(photo.width > 0 && photo.height > 0) { "Choose a photo first" }
  val names = listOf("vae_encoder.onnx", "mobilei2v_unet.onnx", "turbo_vaed.onnx")
  for (name in names) {
   val file = File(modelsDir, name)
   require(file.isFile && file.length() > 1_000_000L) { "Missing MobileI2V model: " + name }
  }
  // The old UNet has no text input. An unsupported motion prompt is never
  // silently sent to Nightmare or confused with a working text-to-video model.
  // A motion prompt is optional for the official checkpoint.

  // Encourage collection of no-longer-referenced UI/image allocations before
  // opening the largest native inference session. This cannot reclaim other apps' RAM.
  System.gc()
  System.runFinalization()
  // Native ONNX allocations are outside the JVM heap. Check headroom first.
  val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
  val memory = ActivityManager.MemoryInfo()
  manager.getMemoryInfo(memory)
  if (memory.lowMemory || memory.availMem < 2L * 1024 * 1024 * 1024) {
   throw IllegalStateException("Insufficient free RAM for MobileI2V: " +
    (memory.availMem / (1024 * 1024)) + " MB available; need at least 2048 MB")
  }
  // Check the peak-memory denoiser BEFORE opening any native ONNX sessions.
  // Opening the UNet just to inspect inputs can itself trigger Android's low-memory killer.
  val denoiserBytes = File(modelsDir, "mobilei2v_unet.onnx").length()
  val reserveBytes = 768L * 1024 * 1024
  // Sequential CPU execution with arena and memory-pattern reuse disabled avoids
  // the large extra graph copies seen with full graph optimization. This is a
  // conservative admission estimate, not a measured peak-memory guarantee.
  val estimatedNeed = denoiserBytes * 3 + reserveBytes
  val requiredBytes = estimatedNeed + memory.threshold
  Log.i("MobileI2V", "Preflight before native ONNX: available=" + memory.availMem +
   " requiredEstimate=" + requiredBytes)
  if (memory.lowMemory || memory.availMem < requiredBytes) {
   throw IllegalStateException("MobileI2V needs more free memory than this phone currently has. " +
    "Available " + (memory.availMem / 1048576) + " MB; estimated requirement " +
    (requiredBytes / 1048576) + " MB. Use a smaller optimized model or remote inference.")
  }
  Log.i("MobileI2V", "Starting inference; available RAM MB=" + (memory.availMem / (1024 * 1024)))
  val env = OrtEnvironment.getEnvironment()
  lateinit var encoded: FloatArray
  var height = 0
  var width = 0
  checkProcessBudget("before encoder")
  progress(3)
  open(env, File(modelsDir, "vae_encoder.onnx")).use { session ->
   require("pixel_values" in session.inputNames) { "Incompatible VAE encoder" }
   val source = Bitmap.createScaledBitmap(photo, SIDE, SIDE, true)
   try {
    val argb = IntArray(SIDE * SIDE)
    source.getPixels(argb, 0, SIDE, 0, 0, SIDE, SIDE)
    val rgb = FloatArray(3 * SIDE * SIDE)
    for (p in argb.indices) {
     val pixel = argb[p]
     rgb[p] = (Color.red(pixel) / 127.5f) - 1f
     rgb[SIDE * SIDE + p] = (Color.green(pixel) / 127.5f) - 1f
     rgb[2 * SIDE * SIDE + p] = (Color.blue(pixel) / 127.5f) - 1f
    }
    floatTensor(env, rgb, 1, 3, SIDE, SIDE).use { input ->
     session.run(mapOf("pixel_values" to input)).use { result ->
      val latent = result[0] as OnnxTensor
      val shape = latent.info.shape
      require(shape.size == 4 && shape[0] == 1L && shape[1] == CHANNELS.toLong()) {
       "VAE returned incompatible latents: " + shape.contentToString()
      }
      height = shape[2].toInt()
      width = shape[3].toInt()
      require(height in 1..16 && width in 1..16) { "Unsafe latent size for mobile decoder" }
      encoded = readFloats(latent)
     }
    }
   } finally { if (source !== photo) source.recycle() }
  }

  checkProcessBudget("after encoder")
  progress(17)
  val spatial = height * width
  val total = CHANNELS * FRAMES * spatial
  val videoLatent = FloatArray(total)
  val rng = java.util.Random()
  for (ch in 0 until CHANNELS) for (t in 0 until FRAMES) for (px in 0 until spatial) {
   val offset = (ch * FRAMES + t) * spatial + px
   videoLatent[offset] = if (t == 0) encoded[ch * spatial + px] else rng.nextGaussian().toFloat()
  }

  // Official MobileI2V v2 is image-conditioned, not text-conditioned.
  // Do not open the huge UNet merely to inspect its input names: doing so
  // allocates native weights and can kill Android before the 22% checkpoint.
  // The actual input contract is checked after the memory preflight below.
  progress(19)

  // The denoiser is the peak-memory stage (the reported 22% crash).
  // Stop before opening the native ONNX session if Android cannot safely host it.
  manager.getMemoryInfo(memory)
  Log.i("MobileI2V", "Pre-denoiser available=" + memory.availMem +
   " requiredEstimate=" + requiredBytes)
  if (memory.lowMemory || memory.availMem < requiredBytes) {
   throw IllegalStateException("MobileI2V stopped before denoising to protect Android memory. " +
    "Available " + (memory.availMem / 1048576) + " MB; estimated requirement " +
    (requiredBytes / 1048576) + " MB. Use a smaller optimized model or remote inference.")
  }

  checkProcessBudget("before denoiser")
  open(env, File(modelsDir, "mobilei2v_unet.onnx")).use { session ->
   checkProcessBudget("denoiser loaded")
   val latentShape = (session.inputInfo["latent"]?.info as? ai.onnxruntime.TensorInfo)?.shape
   require(latentShape != null && latentShape.size == 5 &&
           latentShape[1] == CHANNELS.toLong() && latentShape[2] == FRAMES.toLong() &&
           (latentShape[3] <= 0 || latentShape[3] == height.toLong()) &&
           (latentShape[4] <= 0 || latentShape[4] == width.toLong())) {
    "MobileI2V UNet spatial grid does not match the photo latents. " +
    "Install the latest compatible MobileI2V model from Video settings."
   }
   val inputs = session.inputNames
   require("latent" in inputs && "timestep" in inputs) {
    "Unsupported MobileI2V denoiser inputs: " + inputs.joinToString()
   }
   require(inputs.all { it == "latent" || it == "timestep" }) {
    "Unsupported MobileI2V ONNX graph inputs: " + inputs.joinToString()
   }
   for (step in 0 until STEPS) {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("MobileI2V cancelled")
    // FlowMatch Euler schedule with shift=1.0 from official MobileI2V configuration.
    val sigma = flowSigma(step, STEPS)
    val nextSigma = if (step + 1 == STEPS) 0f else flowSigma(step + 1, STEPS)
    val time = (1000f * sigma).toLong().coerceAtLeast(1)
    floatTensor(env, videoLatent, 1, CHANNELS, FRAMES, height, width).use { latent ->
     longTensor(env, longArrayOf(time), 1).use { timestep ->
      session.run(mapOf("latent" to latent, "timestep" to timestep)).use { result ->
       val denoised = result[0] as OnnxTensor
       val shape = denoised.info.shape
       require(shape.contentEquals(longArrayOf(1, CHANNELS.toLong(), FRAMES.toLong(), height.toLong(), width.toLong()))) {
        "MobileI2V output shape mismatch: " + shape.contentToString()
       }
       val prediction = readFloats(denoised)
       val dt = nextSigma - sigma
       for (idx in videoLatent.indices) {
        val value = videoLatent[idx] + prediction[idx] * dt
        require(value.isFinite()) { "MobileI2V denoiser returned invalid values" }
        videoLatent[idx] = value.coerceIn(-20f, 20f)
       }
       // Freeze the source image latent as the first frame throughout sampling.
       for (ch in 0 until CHANNELS) for (px in 0 until spatial) {
        videoLatent[ch * FRAMES * spatial + px] = encoded[ch * spatial + px]
       }
       }
     }
    }
    checkProcessBudget("denoise step ${step + 1}")
    manager.getMemoryInfo(memory)
    Log.i("MobileI2V", "Denoise step " + (step + 1) + "/" + STEPS +
     "; available RAM MB=" + (memory.availMem / (1024 * 1024)))
    if (memory.lowMemory || memory.availMem < 1536L * 1024 * 1024) {
     throw IllegalStateException("Video stopped: Android is running low on memory")
    }
    progress(18 + (step + 1) * 50 / STEPS)
   }
  }

  checkProcessBudget("before decoder")
  val frames = mutableListOf<Bitmap>()
  progress(69)
  try {
   // Avoid decoding the full clip into a single potentially multi-GB tensor.
   open(env, File(modelsDir, "turbo_vaed.onnx")).use { session ->
    require("latent" in session.inputNames) { "Unsupported Turbo-VAED decoder" }
    var decodedCount = 0
    // Overlap decoder windows by one latent frame. Independent temporal
    // windows otherwise reset decoder context and can introduce hard cuts.
    for (offset in 0 until FRAMES step 4) {
     val window = min(5, FRAMES - offset)
     // Turbo-VAED ONNX was traced with exactly five 16x16 latent frames.
     // Repeat the last real latent in the final incomplete chunk.
     val decoderFrames = 5
     val chunk = FloatArray(CHANNELS * decoderFrames * spatial)
     for (ch in 0 until CHANNELS) for (t in 0 until decoderFrames) for (px in 0 until spatial) {
      val sourceTime = min(FRAMES - 1, offset + t)
      chunk[(ch * decoderFrames + t) * spatial + px] =
       videoLatent[(ch * FRAMES + sourceTime) * spatial + px]
     }
     floatTensor(env, chunk, 1, CHANNELS, decoderFrames, height, width).use { input ->
      session.run(mapOf("latent" to input)).use { result ->
       val decoded = result[0] as OnnxTensor
       val shape = decoded.info.shape
       require(shape.size == 5 && shape[0] == 1L && shape[1] == 3L) {
        "Video decoder returned unexpected shape: " + shape.contentToString()
       }
       val tCount = shape[2].toInt()
       val outH = shape[3].toInt()
       val outW = shape[4].toInt()
       require(tCount in 1..80 && outH in 16..512 && outW in 16..512) {
        "Decoder output is too large for safe on-device rendering: " + shape.contentToString()
       }
       val buffer = decoded.floatBuffer
       val area = outH * outW
       // Turbo-VAED produces up to 8 actual frames per latent frame.
       // Keep all decoded frames (up to 136) instead of throwing away
       // seven of every eight: 5-10s clips now retain smooth real motion.
       val validFrameCount = if (window == decoderFrames) tCount else
        (tCount * window + decoderFrames - 1) / decoderFrames
       require(frames.size + validFrameCount <= 192) { "Too many decoded video frames" }
       val overlapFrames = if (offset == 0) 0 else (tCount / decoderFrames).coerceAtLeast(1)
       for (t in overlapFrames until validFrameCount) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Video rendering cancelled")
        val sourceFrame = t
        // Downsample using a 2x2 box filter instead of skipping 3 of 4 pixels.
        // Point sampling aliases decoder patterns into blocky/checkerboard video.
        // RGB_565 keeps the retained frame memory bounded on 4 GB devices.
        val previewW = (outW / 2).coerceAtLeast(1)
        val previewH = (outH / 2).coerceAtLeast(1)
        val pixels = IntArray(previewW * previewH)
        for (p in pixels.indices) {
         val x0 = p % previewW * 2
         val y0 = p / previewW * 2
         var r = 0; var g = 0; var b = 0; var count = 0
         for (dy in 0..1) for (dx in 0..1) {
          val x = min(outW - 1, x0 + dx)
          val y = min(outH - 1, y0 + dy)
          val src = y * outW + x
          r += rgbByte(buffer.get(sourceFrame * area + src))
          g += rgbByte(buffer.get((tCount + sourceFrame) * area + src))
          b += rgbByte(buffer.get((2 * tCount + sourceFrame) * area + src))
          count++
         }
         pixels[p] = Color.rgb(r / count, g / count, b / count)
        }
        val bitmap = Bitmap.createBitmap(previewW, previewH, Bitmap.Config.RGB_565)
        bitmap.setPixels(pixels, 0, previewW, 0, 0, previewW, previewH)
        frames.add(bitmap)
       }
      }
     }
     checkProcessBudget("decoded $decodedCount frames")
     decodedCount = min(FRAMES, offset + window)
     progress(70 + (decodedCount * 17 / FRAMES))
    }
   }
   require(frames.size >= FRAMES) { "MobileI2V did not produce enough frames" }
   // Detect sudden frame collapse/corruption instead of delivering a broken MP4.
   // This is a guardrail, not a substitute for validating the model decoder.
   fun frameDelta(a: Bitmap, b: Bitmap): Double {
    val stepX = (min(a.width, b.width) / 24).coerceAtLeast(1)
    val stepY = (min(a.height, b.height) / 24).coerceAtLeast(1)
    var sum = 0L
    var count = 0
    for (y in 0 until min(a.height, b.height) step stepY)
     for (x in 0 until min(a.width, b.width) step stepX) {
      val p = a.getPixel(x, y)
      val q = b.getPixel(x, y)
      sum += kotlin.math.abs(Color.red(p) - Color.red(q)) +
       kotlin.math.abs(Color.green(p) - Color.green(q)) +
       kotlin.math.abs(Color.blue(p) - Color.blue(q))
      count++
     }
    return sum.toDouble() / (count * 3)
   }
   val deltas = (1 until frames.size).map { frameDelta(frames[it - 1], frames[it]) }
   val sorted = deltas.sorted()
   val typical = sorted[sorted.size / 2].coerceAtLeast(1.0)
   val abrupt = deltas.withIndex().firstOrNull { it.value > maxOf(45.0, typical * 8.0) }
   require(abrupt == null) {
    "MobileI2V detected a corrupted frame transition near frame ${abrupt?.index?.plus(1)}; video was not saved."
   }
   // Reject static or near-static decoder output rather than labeling it AI motion.
   val samples = listOf(0, frames.size / 4, frames.size / 2, frames.size * 3 / 4, frames.lastIndex)
   val reference = frames[samples.first()]
   val strideX = (reference.width / 16).coerceAtLeast(1)
   val strideY = (reference.height / 16).coerceAtLeast(1)
   var maxDifference = 0.0
   for (index in samples.drop(1)) {
    val other = frames[index]
    var sum = 0L
    var count = 0
    for (y in 0 until min(reference.height, other.height) step strideY)
     for (x in 0 until min(reference.width, other.width) step strideX) {
      val a = reference.getPixel(x, y)
      val b = other.getPixel(x, y)
      sum += kotlin.math.abs(Color.red(a) - Color.red(b)) +
       kotlin.math.abs(Color.green(a) - Color.green(b)) +
       kotlin.math.abs(Color.blue(a) - Color.blue(b))
      count++
     }
    maxDifference = maxOf(maxDifference, sum.toDouble() / (count * 3))
   }
   require(maxDifference >= 2.0) { "MobileI2V decoder produced almost no visible motion; refusing to save a static clip." }
   val out = File.createTempFile("mobilei2v_", ".mp4", cacheDir)
   try {
    OfflineVideoEngine.renderFrames(frames, targetSeconds, out) { p -> progress(88 + p * 12 / 100) }
    val mp4 = out.readBytes()
    require(mp4.size > 12 && String(mp4, 4, 4, Charsets.US_ASCII) == "ftyp") {
     "MobileI2V did not create a valid MP4"
    }
    progress(100)
    return NightmareVideoClient.RenderedVideo(mp4, targetSeconds, targetSeconds.toString(), 512, 512, false)
   } finally { out.delete() }
  } finally { frames.forEach { if (!it.isRecycled) it.recycle() } }
 }

 private fun flowSigma(step: Int, steps: Int): Float {
  // Official MobileI2V_300M_img512.yaml uses flow_shift: 1.0.
  return (1f - step.toFloat() / steps).coerceIn(0f, 1f)
 }

 private fun rgbByte(value: Float): Int {
  require(value.isFinite()) { "Video decoder returned NaN/Inf pixels" }
  return (((value.coerceIn(-1f, 1f) + 1f) * 127.5f).toInt()).coerceIn(0, 255)
 }

 private fun floatTensor(env: OrtEnvironment, data: FloatArray, vararg dimensions: Int): OnnxTensor {
  val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
  buffer.put(data)
  buffer.rewind()
  return OnnxTensor.createTensor(env, buffer, dimensions.map { it.toLong() }.toLongArray())
 }

 private fun longTensor(env: OrtEnvironment, data: LongArray, vararg dimensions: Int): OnnxTensor {
  val buffer = ByteBuffer.allocateDirect(data.size * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
  buffer.put(data)
  buffer.rewind()
  return OnnxTensor.createTensor(env, buffer, dimensions.map { it.toLong() }.toLongArray())
 }

 private fun readFloats(tensor: OnnxTensor): FloatArray {
  val info = tensor.info.shape
  val size = info.fold(1L) { acc, n -> acc * n }
  require(size in 1..(40L * 1024 * 1024)) { "Unsafe model output size: " + size }
  return FloatArray(size.toInt()).also { tensor.floatBuffer.get(it) }
 }

 private fun open(env: OrtEnvironment, file: File): OrtSession {
  val options = OrtSession.SessionOptions()
  try {
   // Bound ONNX Runtime parallel work on memory-constrained Android devices.
   // Graph optimization can reduce intermediate allocations; sequential execution
   // avoids simultaneous operator scratch buffers during video inference.
   options.setCPUArenaAllocator(false)
   options.setMemoryPatternOptimization(false)
   // Avoid retaining extra packed copies of constant model weights.
   // Avoid unsupported session configuration API on the bundled ONNX Runtime version.
   options.setIntraOpNumThreads(1)
   options.setInterOpNumThreads(1)
   options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
   // Extended/all graph optimization can duplicate large constant tensors during
   // session initialization. Prefer basic fusions for mobile memory stability.
   options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
   return env.createSession(file.absolutePath, options)
  } finally { options.close() }
 }
}
