package com.unboundaistudio

import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Install both checkpoint-compatible MobileI2V graphs from the same release.
 * The v1 decoder was traced for the wrong spatial grid and cannot decode
 * 512px frames; keeping it while replacing only the denoiser fails inference.
 * Do not touch the user's source VAE, Qwen encoder, or other model folders.
 */
internal object MobileI2VModelUpdater {
 private const val RELEASE =
  "https://github.com/dbeals1358-coder/Freedomai1/releases/download/mobilei2v-models-v2/"
 private val required = listOf("mobilei2v_unet.onnx", "turbo_vaed.onnx")
 private data class Entry(val name: String, val size: Long, val hash: String)

 fun install(modelsDir: File, progress: (Int) -> Unit): String {
  modelsDir.mkdirs()
  val manifest = try {
   JSONObject(String(downloadSmall(RELEASE + "validation_report.json"), Charsets.UTF_8))
  } catch (e: Exception) {
   error("Compatible MobileI2V model release is unavailable: " + e.message)
  }
  val entries = required.map { name ->
   val record = manifest.getJSONObject(name)
   val size = record.getLong("bytes")
   val hash = record.getString("sha256").lowercase()
   val array = record.getJSONArray("inputs")
   val names = (0 until array.length()).map { array.getString(it) }.toSet()
   require("latent" in names && (name != "mobilei2v_unet.onnx" ||
     ("timestep" in names && "text_emb" !in names))) {
    "Incorrect MobileI2V release contract: " + name
   }
   require(size in 100_000_000L..2_000_000_000L &&
     hash.matches(Regex("[0-9a-f]{64}"))) {
    "Invalid MobileI2V release record: " + name
   }
   Entry(name, size, hash)
  }
  fun manifestFile(entry: Entry) =
   File(modelsDir, entry.name.removeSuffix(".onnx") + "_manifest.json")

  val changed = entries.filter { e ->
   val file = File(modelsDir, e.name)
   !(file.isFile && file.length() == e.size && sha256(file) == e.hash)
  }
  if (changed.isEmpty()) {
   entries.forEach { e -> manifestFile(e).writeText(manifest.getJSONObject(e.name).toString()) }
   progress(100)
   return "Both compatible MobileI2V models are already installed."
  }

  val totalDownload = changed.sumOf { it.size }
  require(StatFs(modelsDir.absolutePath).availableBytes > totalDownload + 128L * 1024 * 1024) {
   "Insufficient free storage; need about " +
    ((totalDownload + 128L * 1024 * 1024) / 1048576) + " MB."
  }
  val installed = mutableListOf<Entry>()
  var received = 0L
  try {
   // Download everything and verify hashes before replacing either original.
   for (e in changed) {
    val temporary = File(modelsDir, e.name + ".downloading")
    temporary.delete()
    val digest = MessageDigest.getInstance("SHA-256")
    val connection = URL(RELEASE + e.name).openConnection() as HttpURLConnection
    try {
     connection.connectTimeout = 30000
     connection.readTimeout = 30000
     connection.instanceFollowRedirects = true
     connection.setRequestProperty("User-Agent", "FreedomAI-MobileI2V/2.5")
     require(connection.responseCode == 200) {
      "Download failed for " + e.name + ": HTTP " + connection.responseCode
     }
     var copied = 0L
     connection.inputStream.use { source ->
      temporary.outputStream().buffered().use { target ->
       val block = ByteArray(1024 * 1024)
       while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Download cancelled")
        val count = source.read(block)
        if (count < 0) break
        copied += count
        require(copied <= e.size) { "Model exceeds release manifest size: " + e.name }
        target.write(block, 0, count)
        digest.update(block, 0, count)
        received += count
        progress((received * 99 / totalDownload).toInt().coerceIn(0, 99))
       }
      }
     }
     require(copied == e.size) { "Incomplete MobileI2V download: " + e.name }
     require(digest.digest().joinToString("") { "%02x".format(it) } == e.hash) {
      "MobileI2V SHA-256 mismatch: " + e.name
     }
    } finally { connection.disconnect() }
   }
   for (e in changed) {
    val target = File(modelsDir, e.name)
    val backup = File(modelsDir, e.name + ".backup")
    val temporary = File(modelsDir, e.name + ".downloading")
    backup.delete()
    if (target.exists()) require(target.renameTo(backup)) {
     "Unable to back up old model: " + e.name
    }
    if (!temporary.renameTo(target)) {
     if (backup.exists()) backup.renameTo(target)
     error("Unable to install model: " + e.name)
    }
    installed.add(e)
   }
   entries.forEach { e -> manifestFile(e).writeText(manifest.getJSONObject(e.name).toString()) }
   installed.forEach { e -> File(modelsDir, e.name + ".backup").delete() }
   progress(100)
   return "MobileI2V denoiser and decoder installed and verified. Source VAE and Qwen unchanged."
  } catch (e: Exception) {
   installed.asReversed().forEach { entry ->
    File(modelsDir, entry.name).delete()
    val backup = File(modelsDir, entry.name + ".backup")
    if (backup.exists()) backup.renameTo(File(modelsDir, entry.name))
   }
   throw e
  } finally {
   changed.forEach { File(modelsDir, it.name + ".downloading").delete() }
  }
 }

 private fun downloadSmall(path: String): ByteArray {
  val conn = URL(path).openConnection() as HttpURLConnection
  try {
   conn.connectTimeout = 20000
   conn.readTimeout = 20000
   conn.instanceFollowRedirects = true
   conn.setRequestProperty("User-Agent", "FreedomAI-MobileI2V/2.5")
   require(conn.responseCode == 200) { "HTTP " + conn.responseCode }
   return conn.inputStream.use { stream ->
    stream.readBytes().also { require(it.size < 1_000_000) { "Invalid model manifest" } }
   }
  } finally { conn.disconnect() }
 }

 private fun sha256(file: File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().buffered().use { stream ->
   val buffer = ByteArray(1024 * 1024)
   while (true) {
    val n = stream.read(buffer)
    if (n < 0) break
    digest.update(buffer, 0, n)
   }
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
 }
}
