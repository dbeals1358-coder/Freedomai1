package com.unboundaistudio

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * Native ONNX inference foundation for a future converted MobileI2V model.
 * The upstream .pth checkpoint is NOT an ONNX model. A complete, validated
 * export (text encoder, image encoder, denoiser and video decoder) is required.
 * This class deliberately refuses to fake generated frames.
 */
class LocalAiMotionRuntime(private val modelsDir: File) : AutoCloseable {
 private val environment = OrtEnvironment.getEnvironment()
 private val sessions = mutableListOf<OrtSession>()

 data class Readiness(val ready: Boolean, val reason: String)

 fun readiness(): Readiness {
  val required = listOf("text_encoder.onnx", "image_encoder.onnx", "denoiser.onnx", "video_decoder.onnx")
  val missing = required.filter { !File(modelsDir, it).isFile }
  if (missing.isNotEmpty()) return Readiness(false, "Missing converted on-device AI models: " + missing.joinToString())
  return Readiness(false, "Model files found, but tensor contracts and full MobileI2V inference pipeline are not validated.")
 }

 fun openModel(filename: String): OrtSession {
  require(filename in setOf("text_encoder.onnx", "image_encoder.onnx", "denoiser.onnx", "video_decoder.onnx"))
  val file = File(modelsDir, filename)
  require(file.isFile) { "Missing ONNX model: $filename" }
  val session = environment.createSession(file.absolutePath, OrtSession.SessionOptions())
  sessions.add(session)
  return session
 }

 override fun close() {
  sessions.forEach { it.close() }
  sessions.clear()
 }
}
