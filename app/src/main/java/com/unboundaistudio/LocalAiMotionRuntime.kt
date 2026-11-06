package com.unboundaistudio

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Validates local ONNX model artifacts before attempting expensive inference.
 * A successful validation is NOT proof that MobileI2V inference is implemented.
 */
class LocalAiMotionRuntime(private val modelsDir: File) : AutoCloseable {
 private val environment by lazy { OrtEnvironment.getEnvironment() }
 private val sessions = mutableListOf<OrtSession>()
 private val required = listOf("vae_encoder.onnx", "qwen2_encoder.onnx", "mobilei2v_unet.onnx", "turbo_vaed.onnx")
 data class Readiness(val ready: Boolean, val reason: String)


 private val expected = mapOf(
  "vae_encoder.onnx" to Pair(721865762L, "2fad3a342d7562da942133be3dab1b863e50a59d458cfcb7446ba9a5c856e21e"),
  "qwen2_encoder.onnx" to Pair(1977089035L, "7b4509ea975627516ae29048c444e5b9cdec994ae23b11dd0ab107716e437ff4"),
  "mobilei2v_unet.onnx" to Pair(1138902583L, "359e4cd9a8bb2e1186608b9604db64d835a7fb868bec95a3100145a019f010cc"),
  "turbo_vaed.onnx" to Pair(167881867L, "660819e18086da2d7f366e78c4cbafc6a0826c3e7beef7434328103adc0d3e19")
 )
 /** Validate imported model bytes against the provided manifest. Call on Dispatchers.IO. */
 fun verifyImportedFiles(): Readiness {
  val missing = required.filter { !File(modelsDir, it).isFile }
  if (missing.isNotEmpty()) return Readiness(false, "Missing models: " + missing.joinToString())
  for (name in required) {
   val file = File(modelsDir, name)
   val (originalSize, originalHash) = expected.getValue(name)
   val correctedManifest = File(modelsDir, name.removeSuffix(".onnx") + "_manifest.json")
   val corrected = if (name in listOf("mobilei2v_unet.onnx", "turbo_vaed.onnx") && correctedManifest.isFile) {
    runCatching {
     val manifest = JSONObject(correctedManifest.readText())
     val names = manifest.getJSONArray("inputs").toString()
     require(names.contains("latent"))
     if (name == "mobilei2v_unet.onnx") require(names.contains("timestep") && !names.contains("text_emb"))
     Pair(manifest.getLong("bytes"), manifest.getString("sha256"))
    }.getOrNull()
   } else null
   val (size, hash) = corrected ?: Pair(originalSize, originalHash)
   if (file.length() != size) return Readiness(false, "$name size mismatch")
   if (!sha256(name).equals(hash, ignoreCase = true)) return Readiness(false, "$name SHA-256 mismatch. Reimport model.")
  }
  return Readiness(true, "All four MobileI2V model files passed SHA-256 verification. Video inference and motion quality still require device testing.")
 }

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
   Readiness(true, "All four ONNX graphs loaded. Video still needs real-device testing. " + diagnostics.joinToString("; "))
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
