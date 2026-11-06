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

 fun generate(context: Context, modelsDir: File, cacheDir: File, photo: Bitmap, prompt: String,
              seconds: Int, progress: (Int) -> Unit): NightmareVideoClient.RenderedVideo {
  require(seconds in 1..10) { "Choose 1–10 seconds" }
  require(photo.width > 0 && photo.height > 0) { "Choose a photo first" }
  val names = listOf("vae_encoder.onnx", "mobilei2v_unet.onnx", "turbo_vaed.onnx")
  for (name in names) {
   val file = File(modelsDir, name)
   require(file.isFile && file.length() > 1_000_000L) { "Missing MobileI2V model: " + name }
  }
  // The old UNet has no text input. An unsupported motion prompt is never
  // silently sent to Nightmare or confused with a working text-to-video model.
  // A motion prompt is optional for the official checkpoint.

  // Native ONNX allocations are outside the JVM heap. Check headroom first.
  val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
  val memory = ActivityManager.MemoryInfo()
  manager.getMemoryInfo(memory)
  if (memory.lowMemory || memory.availMem < 2L * 1024 * 1024 * 1024) {
   throw IllegalStateException("Insufficient free RAM for MobileI2V: " +
    (memory.availMem / (1024 * 1024)) + " MB available; need at least 2048 MB")
  }
  Log.i("MobileI2V", "Starting inference; available RAM MB=" + (memory.availMem / (1024 * 1024)))
  val env = OrtEnvironment.getEnvironment()
  lateinit var encoded: FloatArray
  var height = 0
  var width = 0
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

  progress(17)
  val spatial = height * width
  val total = CHANNELS * FRAMES * spatial
  val videoLatent = FloatArray(total)
  val rng = java.util.Random()
  for (ch in 0 until CHANNELS) for (t in 0 until FRAMES) for (px in 0 until spatial) {
   val offset = (ch * FRAMES + t) * spatial + px
   videoLatent[offset] = if (t == 0) encoded[ch * spatial + px] else rng.nextGaussian().toFloat()
  }

  // Determine the actual ONNX contract before allocating the Qwen encoder.
  // Corrected exports retain text_emb, older exports have no prompt input.
  val hasText = open(env, File(modelsDir, "mobilei2v_unet.onnx")).use { "text_emb" in it.inputNames }
  var textData: FloatArray? = null
  var textLength = 0
  if (hasText) {
   require(prompt.isNotBlank()) { "This text-conditioned model requires a prompt" }
   val qwenFile = File(modelsDir, "qwen2_encoder.onnx")
   require(qwenFile.isFile) { "Import qwen2_encoder.onnx to enable prompt-conditioned MobileI2V" }
   val (ids, mask) = Qwen2MotionTokenizer.fromAssets(context).encode(prompt)
   textLength = ids.size
   open(env, qwenFile).use { qwen ->
    longTensor(env, ids, 1, ids.size).use { inputIds ->
     longTensor(env, mask, 1, mask.size).use { attentionMask ->
      qwen.run(mapOf("input_ids" to inputIds, "attention_mask" to attentionMask)).use { result ->
       val hidden = result[0] as OnnxTensor
       val shape = hidden.info.shape
       require(shape.contentEquals(longArrayOf(1, textLength.toLong(), 896))) {
        "Qwen2 encoder output incompatible with MobileI2V: " + shape.contentToString()
       }
       textData = readFloats(hidden)
      }
     }
    }
   }
   progress(19)
  }

  // The denoiser is the peak-memory stage (the reported 22% crash).
  // Stop before opening the native ONNX session if Android cannot safely host it.
  manager.getMemoryInfo(memory)
  val denoiserBytes = File(modelsDir, "mobilei2v_unet.onnx").length()
  val reserveBytes = 1024L * 1024 * 1024
  val estimatedNeed = denoiserBytes * 3 + reserveBytes
  Log.i("MobileI2V", "Preflight denoiser: available=" + memory.availMem +
   " requiredEstimate=" + estimatedNeed + " threshold=" + memory.threshold)
  if (memory.lowMemory || memory.availMem < estimatedNeed + memory.threshold) {
   throw IllegalStateException("MobileI2V stopped before denoising to protect Android memory. " +
    "Available " + (memory.availMem / 1048576) + " MB; estimated requirement " +
    ((estimatedNeed + memory.threshold) / 1048576) + " MB. Use a smaller optimized model or remote inference.")
  }

  open(env, File(modelsDir, "mobilei2v_unet.onnx")).use { session ->
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
   require(inputs.all { it == "latent" || it == "timestep" || it == "text_emb" }) {
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
      val embeddings = textData?.let { floatTensor(env, it, 1, textLength, 896) }
      try {
       val feeds = linkedMapOf<String, OnnxTensor>("latent" to latent, "timestep" to timestep)
       if (embeddings != null) feeds["text_emb"] = embeddings
       session.run(feeds).use { result ->
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
      } finally { embeddings?.close() }
     }
    }
    manager.getMemoryInfo(memory)
    Log.i("MobileI2V", "Denoise step " + (step + 1) + "/" + STEPS +
     "; available RAM MB=" + (memory.availMem / (1024 * 1024)))
    if (memory.lowMemory || memory.availMem < 512L * 1024 * 1024) {
     throw IllegalStateException("Video stopped: Android is running low on memory")
    }
    progress(18 + (step + 1) * 50 / STEPS)
   }
  }

  val frames = mutableListOf<Bitmap>()
  progress(69)
  try {
   // Avoid decoding the full clip into a single potentially multi-GB tensor.
   open(env, File(modelsDir, "turbo_vaed.onnx")).use { session ->
    require("latent" in session.inputNames) { "Unsupported Turbo-VAED decoder" }
    var decodedCount = 0
    for (offset in 0 until FRAMES step 5) {
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
       for (t in 0 until validFrameCount) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Video rendering cancelled")
        val sourceFrame = t
        val pixels = IntArray(area)
        for (p in 0 until area) {
         val r = rgbByte(buffer.get(sourceFrame * area + p))
         val g = rgbByte(buffer.get((tCount + sourceFrame) * area + p))
         val b = rgbByte(buffer.get((2 * tCount + sourceFrame) * area + p))
         pixels[p] = Color.rgb(r, g, b)
        }
        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        frames.add(bitmap)
       }
      }
     }
     decodedCount += window
     progress(70 + (decodedCount * 17 / FRAMES))
    }
   }
   require(frames.size >= FRAMES) { "MobileI2V did not produce enough frames" }
   val out = File.createTempFile("mobilei2v_", ".mp4", cacheDir)
   try {
    OfflineVideoEngine.renderFrames(frames, seconds, out) { p -> progress(88 + p * 12 / 100) }
    val mp4 = out.readBytes()
    require(mp4.size > 12 && String(mp4, 4, 4, Charsets.US_ASCII) == "ftyp") {
     "MobileI2V did not create a valid MP4"
    }
    progress(100)
    return NightmareVideoClient.RenderedVideo(mp4, seconds, seconds.toString(), 512, 512, false)
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
   options.setIntraOpNumThreads(1)
   options.setInterOpNumThreads(1)
   options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
   options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
   return env.createSession(file.absolutePath, options)
  } finally { options.close() }
 }
}
