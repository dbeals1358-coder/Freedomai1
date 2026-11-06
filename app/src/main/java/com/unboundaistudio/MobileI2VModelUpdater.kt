package com.unboundaistudio

import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** SHA-256 verified replacement compatible with official MobileI2V checkpoint. */
internal object MobileI2VModelUpdater {
 private const val RELEASE = "https://github.com/dbeals1358-coder/Freedomai1/releases/download/mobilei2v-models-v2/"
 private const val MODEL = "mobilei2v_unet.onnx"

 fun install(modelsDir: File, progress: (Int) -> Unit): String {
  modelsDir.mkdirs()
  val manifest = try {
   val bytes = downloadSmall(RELEASE + "validation_report.json")
   JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject(MODEL)
  } catch (e: Exception) {
   error("Compatible MobileI2V model has not been published yet, or the network is unavailable: " + e.message)
  }
  val expectedBytes = manifest.getLong("bytes")
  val expectedHash = manifest.getString("sha256").lowercase()
  val inputs = manifest.getJSONArray("inputs")
  val names = (0 until inputs.length()).map { inputs.getString(it) }.toSet()
  require("latent" in names && "timestep" in names && "text_emb" !in names) {
   "Published model does not match official trained MobileI2V checkpoint."
  }
  require(expectedBytes in 200_000_000L..2_000_000_000L &&
          expectedHash.matches(Regex("[0-9a-f]{64}"))) {
   "Invalid model release manifest"
  }
  val target = File(modelsDir, MODEL)
  if (target.isFile && target.length() == expectedBytes &&
      sha256(target) == expectedHash) {
   File(modelsDir, "mobilei2v_unet_manifest.json").writeText(manifest.toString())
   return "Compatible MobileI2V model is already installed."
  }

  val free = StatFs(modelsDir.absolutePath).availableBytes
  require(free > expectedBytes + 128L * 1024 * 1024) {
   "Not enough free storage for MobileI2V model. Need about " +
    ((expectedBytes + 128L * 1024 * 1024) / 1048576) + " MB."
  }
  val temp = File(modelsDir, MODEL + ".downloading")
  val backup = File(modelsDir, MODEL + ".backup")
  temp.delete()
  try {
   val digest = MessageDigest.getInstance("SHA-256")
   val connection = (URL(RELEASE + MODEL).openConnection() as HttpURLConnection)
   try {
    connection.connectTimeout = 30000
    connection.readTimeout = 30000
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("User-Agent", "FreedomAI-MobileI2V/2.5")
    require(connection.responseCode == 200) {
     "Model download failed with HTTP " + connection.responseCode
    }
    var copied = 0L
    connection.inputStream.use { input ->
     temp.outputStream().buffered().use { output ->
      val block = ByteArray(1024 * 1024)
      while (true) {
       if (Thread.currentThread().isInterrupted) throw InterruptedException("Download cancelled")
       val amount = input.read(block)
       if (amount < 0) break
       copied += amount
       require(copied <= expectedBytes) { "Unexpectedly large model download" }
       output.write(block, 0, amount)
       digest.update(block, 0, amount)
       progress((copied * 100L / expectedBytes).toInt().coerceIn(0, 99))
      }
     }
    }
    require(copied == expectedBytes) { "Model download was incomplete" }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    require(actual == expectedHash) { "Downloaded MobileI2V model failed SHA-256 verification" }
   } finally { connection.disconnect() }

   backup.delete()
   if (target.exists()) require(target.renameTo(backup)) { "Could not back up previous model" }
   if (!temp.renameTo(target)) {
    if (backup.exists()) backup.renameTo(target)
    error("Could not finish installing the corrected MobileI2V model")
   }
   backup.delete()
   File(modelsDir, "mobilei2v_unet_manifest.json").writeText(manifest.toString())
   progress(100)
   return "Compatible MobileI2V model installed. The official checkpoint does not follow motion prompts. Your other three models were preserved."
  } finally { temp.delete() }
 }

 private fun downloadSmall(path: String): ByteArray {
  val connection = (URL(path).openConnection() as HttpURLConnection)
  try {
   connection.connectTimeout = 20000
   connection.readTimeout = 20000
   connection.instanceFollowRedirects = true
   connection.setRequestProperty("User-Agent", "FreedomAI-MobileI2V/2.5")
   require(connection.responseCode == 200) { "HTTP " + connection.responseCode }
   return connection.inputStream.use { input ->
    input.readBytes().also { require(it.size < 1_000_000) { "Invalid model manifest" } }
   }
  } finally { connection.disconnect() }
 }

 private fun sha256(file: File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().buffered().use { input ->
   val buffer = ByteArray(1024 * 1024)
   while (true) {
    val n = input.read(buffer)
    if (n < 0) break
    digest.update(buffer, 0, n)
   }
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
 }
}
