package com.unboundaistudio

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import java.net.HttpURLConnection
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContent {
   val prefs = remember { getSharedPreferences("server", MODE_PRIVATE) }
   var server by remember { mutableStateOf(prefs.getString("url", "") ?: "") }
   var localMode by remember { mutableStateOf(prefs.getBoolean("local", true)) }
   var busy by remember { mutableStateOf(false) }
   var status by remember { mutableStateOf("") }
   var result by remember { mutableStateOf<Bitmap?>(null) }
   val scope = rememberCoroutineScope()
   var tab by remember { mutableStateOf(-1) }
   var showConnection by remember { mutableStateOf(false) }
   var prompt by remember { mutableStateOf("") }
   var original by remember { mutableStateOf<Bitmap?>(null) }
   var brightness by remember { mutableFloatStateOf(0f) }
   var saturation by remember { mutableFloatStateOf(1f) }
   val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    if (uri != null) try {
     val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
     contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
     var sample = 1
     while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
     original = contentResolver.openInputStream(uri)?.use {
      BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
     }
     brightness = 0f; saturation = 1f
    } catch (e: Exception) { toast("Couldn't open image") }
   }
   val edited = remember(original, brightness, saturation) { original?.let { adjust(it, brightness, saturation) } }
   MaterialTheme(colorScheme = darkColorScheme(
    primary = UiColor(0xFFB8A0FF), onPrimary = UiColor(0xFF21133F),
    secondary = UiColor(0xFF8AC7FF), background = UiColor(0xFF101116),
    surface = UiColor(0xFF191B24), surfaceVariant = UiColor(0xFF252836)
   )) {
    Scaffold(bottomBar = {
     NavigationBar {
      NavigationBarItem(selected = tab == -1, onClick = { tab = -1 }, icon = { Text("⌂") }, label = { Text("Home") })
      NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("✦") }, label = { Text("Create") })
      NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("◈") }, label = { Text("Edit") })
     }
    }) { inset ->
     Column(Modifier.padding(inset).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
      Text("FreedomAI", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
      Text(if (tab == -1) "What will you create?" else if (tab == 0) "Turn your ideas into images" else "Give your photos a fresh look", style = MaterialTheme.typography.titleLarge)
      if (tab == -1) {
       Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(UiColor(0xFF332451), UiColor(0xFF192F49))), RoundedCornerShape(28.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("✦", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.secondary)
        Text("A little imagination.\nEndless possibilities.", style = MaterialTheme.typography.headlineMedium)
        Text("Start with an idea or a photo. Make something your own.", color = UiColor(0xFFD3D0E3))
        Button(onClick = { tab = 0 }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Create Image") }
        OutlinedButton(onClick = { tab = 1 }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Edit Photo") }
       }
       Text("Start with an idea", style = MaterialTheme.typography.titleMedium)
       listOf("A mountain lake under a sky full of stars", "A cozy cabin in an autumn forest", "A neon city street in the rain").forEach { idea ->
        OutlinedButton(onClick = { prompt = idea; tab = 0 }, modifier = Modifier.fillMaxWidth()) { Text(idea) }
       }
      }
      TextButton(onClick = { showConnection = !showConnection }) { Text(if (showConnection) "Hide connection settings ↑" else "Connection settings ↓") }
      if (showConnection) {
      Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
       Text("Local Dream on this phone")
       Switch(checked = localMode, enabled = !busy, onCheckedChange = { localMode = it; prefs.edit().putBoolean("local", it).apply() })
      }
      if (localMode) {
       Text("1. Open Local Dream and load CyberRealistic v10.\n2. Keep its model screen open, then switch back here.\n3. Check connection, then generate.")
       OutlinedButton(enabled = !busy, onClick = {
        val intent = packageManager.getLaunchIntentForPackage("io.github.xororz.localdream")
        if (intent != null) startActivity(intent) else toast("Open Local Dream from your phone's app list")
       }) { Text("Open Local Dream") }
       OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
         try {
          withContext(Dispatchers.IO) { LocalDreamClient.checkConnection() }
          status = "Connected to Local Dream. Ready to generate."
         } catch (e: kotlinx.coroutines.CancellationException) { throw e
         } catch (e: Exception) { status = "Local Dream isn't ready. Open it, load a model, and keep it running. Try split screen if needed."
         } finally { busy = false }
        }
       }) { Text("Check connection") }
      } else {
       OutlinedTextField(value = server, onValueChange = { server = it }, label = { Text("Image server HTTPS URL") }, placeholder = { Text("https://your-server.example") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
       Text("Connect an AUTOMATIC1111 server. Photos and prompts go to that server when you generate or AI edit.")
      }
      }
      }
      }
      if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      if (status.isNotEmpty()) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Text(status, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
      fun requestImage(input: Bitmap?) {
       if (prompt.isBlank()) { status = "Describe the image first"; return }
       val address = server.trim().trimEnd('/')
       if (!localMode && runCatching { URL(address).let { it.protocol == "https" && it.host.isNotBlank() && it.userInfo == null && it.query == null && it.ref == null } }.getOrDefault(false).not()) {
        status = "Enter a valid HTTPS server URL"; return
       }
       prefs.edit().putString("url", address).apply()
       val useLocal = localMode
       val requestedPrompt = prompt
       busy = true; status = "Creating image…"
       scope.launch {
        try {
         result = withContext(Dispatchers.IO) {
          if (useLocal) {
           val encoded = input?.let { bitmap ->
            ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP) }
           }
           val frame = LocalDreamClient.generate(requestedPrompt, encoded) { step, total ->
            runOnUiThread { status = "Creating image: step $step of $total" }
           }
           Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
          } else generateImage(address, requestedPrompt, input)
         }
         status = "Image ready"
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { status = if (useLocal) "Local Dream: ${e.message ?: "connection failed"}. Keep the model loaded; try split screen if disconnected." else "Could not create image: ${e.message ?: "connection failed"}"
        } finally { busy = false }
       }
      }
      if (tab == 0) {
       OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Describe your image") }, placeholder = { Text("A peaceful beach at sunset, warm light, gentle waves…") }, modifier = Modifier.fillMaxWidth().height(170.dp))
       Button(onClick = { requestImage(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Creating…" else "Create Image") }

      } else if (tab == 1) {
       Button(enabled = !busy, onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Choose Photo") }
       edited?.let { bitmap ->
        Image(bitmap.asImageBitmap(), contentDescription = "Photo preview", modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
        OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Describe your AI edit") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { requestImage(bitmap) }, enabled = !busy) { Text("AI Edit Photo") }
        Text("Brightness")
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -0.5f..0.5f)
        Text("Saturation")
        Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..2f)
        Button(onClick = { save(bitmap) }, modifier = Modifier.fillMaxWidth()) { Text("Save Photo") }
        OutlinedButton(onClick = { brightness = 0f; saturation = 1f }) { Text("Reset") }
       } ?: Text("Pick a photo to edit its brightness and color saturation.")
      }
      result?.let { image ->
       Text("Your creation", style = MaterialTheme.typography.titleLarge)
       Image(image.asImageBitmap(), contentDescription = "Generated image", modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
       Button(onClick = { save(image) }) { Text("Save Image") }
       OutlinedButton(onClick = { original = image; brightness = 0f; saturation = 1f; result = null; tab = 1 }) { Text("Edit this image") }
      }
     }
    }
   }
  }
 }
 private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
 private fun save(bitmap: Bitmap) {
  val values = ContentValues().apply {
   put(MediaStore.Images.Media.DISPLAY_NAME, "Unbound_${System.currentTimeMillis()}.jpg")
   put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
   put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Unbound AI Studio")
  }
  try {
   val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values.apply { put(MediaStore.Images.Media.IS_PENDING, 1) }) ?: error("Unable to create image")
   try {
    val output = contentResolver.openOutputStream(uri) ?: error("Unable to open photo")
    output.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    toast("Saved to Pictures")
   } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
  } catch (e: Exception) { toast("Couldn't save photo") }
 }
}
private fun adjust(bitmap: Bitmap, brightness: Float, saturation: Float): Bitmap {
 val w = bitmap.width; val h = bitmap.height
 val pixels = IntArray(w * h)
 bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
 for (i in pixels.indices) {
  val c = pixels[i]; val r = Color.red(c).toFloat(); val g = Color.green(c).toFloat(); val b = Color.blue(c).toFloat()
  val gray = 0.299f * r + 0.587f * g + 0.114f * b
  fun channel(v: Float) = (gray + (v - gray) * saturation + brightness * 255f).roundToInt().coerceIn(0, 255)
  pixels[i] = Color.argb(Color.alpha(c), channel(r), channel(g), channel(b))
 }
 return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
}

private fun generateImage(server: String, prompt: String, input: Bitmap?): Bitmap {
 val payload = JSONObject().put("prompt", prompt).put("steps", 20).put("width", 512).put("height", 512).put("batch_size", 1)
 if (input != null) {
  val bytes = ByteArrayOutputStream().use { stream -> input.compress(Bitmap.CompressFormat.PNG, 100, stream); stream.toByteArray() }
  payload.put("init_images", JSONArray().put(Base64.encodeToString(bytes, Base64.NO_WRAP))).put("denoising_strength", 0.55)
 }
 val endpoint = if (input == null) "txt2img" else "img2img"
 val connection = URL("$server/sdapi/v1/$endpoint").openConnection() as HttpURLConnection
 try {
  connection.requestMethod = "POST"
  connection.instanceFollowRedirects = false
  connection.connectTimeout = 15000
  connection.readTimeout = 180000
  connection.doOutput = true
  connection.setRequestProperty("Content-Type", "application/json")
  connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
  val code = connection.responseCode
  check(code in 200..299) { "Server returned HTTP $code. Check the URL and API access." }
  val data = connection.inputStream.use { stream ->
   val buffer = ByteArray(8192)
   val out = ByteArrayOutputStream()
   while (true) {
    val count = stream.read(buffer)
    if (count < 0) break
    check(out.size() + count <= 32 * 1024 * 1024) { "Server response is too large" }
    out.write(buffer, 0, count)
   }
   val bytes = out.toByteArray()
   check(bytes.size <= 32 * 1024 * 1024) { "Server response is too large" }
   bytes.toString(Charsets.UTF_8)
  }
  val encoded = JSONObject(data).getJSONArray("images").getString(0).substringAfter(",")
  val bytes = Base64.decode(encoded, Base64.DEFAULT)
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  var sample = 1
  while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
  return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Server did not return a readable image")
 } finally { connection.disconnect() }
}

