package com.unboundaistudio

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.security.MessageDigest

/**
 * Validates local ONNX model artifacts before attempting expensive inference.
 * A successful validation is NOT proof that MobileI2V inference is implemented.
 */
class LocalAiMotionRuntime(private val modelsDir: File) : AutoCloseable {
 private val environment by lazy { OrtEnvironment.getEnvironment() }
 private val sessions = mutableListOf<OrtSession>()
 private val required = listOf("vae_encoder.onnx", "qwen2_encoder.onnx", "mobilei2v_unet.onnx", "turbo_vaed.onnx")
 data class Readiness(val ready: Boolean, val reason: String)

 fun readiness(): Readiness {
  val missing = required.filter { !File(modelsDir, it).isFile }
  if (missing.isNotEmpty()) return Readiness(false, "Missing ONNX files: " + missing.joinToString())
  val empty = required.filter { File(modelsDir, it).length() < 1024 }
  if (empty.isNotEmpty()) return Readiness(false, "Invalid or truncated models: " + empty.joinToString())
  return try {
   val diagnostics = required.map { filename ->
    val session = openModel(filename)
    try {
     val inputs = session.inputInfo.keys.joinToString()
     val outputs = session.outputInfo.keys.joinToString()
     require(inputs.isNotBlank() && outputs.isNotBlank()) { "$filename has no usable inputs or outputs" }
     "$filename: inputs=[$inputs], outputs=[$outputs]"
    } finally {
     sessions.remove(session)
     session.close()
    }
   }
   Readiness(false, "All four ONNX graphs loaded successfully. Inference pipeline is not implemented yet. " + diagnostics.joinToString("; "))
  } catch (e: Exception) {
   Readiness(false, "ONNX model compatibility check failed: " + (e.message ?: e.javaClass.simpleName))
  }
 }

 /** Loads an actual ONNX graph and exposes its tensor names for integration diagnostics. */
 fun inspectModel(filename: String): String {
  require(filename in required) { "Unexpected model filename" }
  val file = File(modelsDir, filename)
  require(file.isFile && file.length() >= 1024) { "Missing or truncated model: $filename" }
  val session = openModel(filename)
  return "$filename inputs=${session.inputNames.joinToString()} outputs=${session.outputNames.joinToString()}"
 }

 fun openModel(filename: String): OrtSession {
  require(filename in required)
  val file = File(modelsDir, filename)
  require(file.isFile && file.length() >= 1024) { "Missing or truncated model: $filename" }
  val options = OrtSession.SessionOptions()
  try {
   val session = environment.createSession(file.absolutePath, options)
   sessions.add(session)
   return session
  } finally {
   options.close()
  }
 }

 fun sha256(filename: String): String {
  require(filename in required)
  val digest = MessageDigest.getInstance("SHA-256")
  File(modelsDir, filename).inputStream().buffered().use { stream ->
   val buffer = ByteArray(1024 * 1024)
   while (true) {
    val n = stream.read(buffer)
    if (n < 0) break
    digest.update(buffer, 0, n)
   }
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
 }

 override fun close() {
  sessions.forEach { it.close() }
  sessions.clear()
 }
}
