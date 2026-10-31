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
   var busy by remember { mutableStateOf(false) }
   var status by remember { mutableStateOf("") }
   var result by remember { mutableStateOf<Bitmap?>(null) }
   val scope = rememberCoroutineScope()
   var tab by remember { mutableStateOf(0) }
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
   MaterialTheme(colorScheme = darkColorScheme()) {
    Scaffold(bottomBar = {
     NavigationBar {
      NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("✦") }, label = { Text("Generate") })
      NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("◈") }, label = { Text("Edit") })
     }
    }) { inset ->
     Column(Modifier.padding(inset).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
      Text("UNBOUND AI STUDIO", style = MaterialTheme.typography.headlineMedium)
      Text("Create freely. Edit creatively.")
      OutlinedTextField(value = server, onValueChange = { server = it }, label = { Text("Image server HTTPS URL") }, placeholder = { Text("https://your-server.example") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
      Text("Connect your AUTOMATIC1111 server with its API enabled. Photos and prompts are sent only when you tap Generate or AI Edit.")
      if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      if (status.isNotEmpty()) Text(status)
      fun requestImage(input: Bitmap?) {
       if (prompt.isBlank()) { status = "Describe the image first"; return }
       val address = server.trim().trimEnd('/')
       if (runCatching { URL(address).let { it.protocol == "https" && it.host.isNotBlank() && it.userInfo == null && it.query == null && it.ref == null } }.getOrDefault(false).not()) {
        status = "Enter a valid HTTPS server URL"; return
       }
       prefs.edit().putString("url", address).apply()
       busy = true; status = "Creating image…"
       scope.launch {
        try {
         result = withContext(Dispatchers.IO) { generateImage(address, prompt, input) }
         status = "Image ready"
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { status = "Could not create image: ${e.message ?: "connection failed"}"
        } finally { busy = false }
       }
      }
      if (tab == 0) {
       OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Describe your image") }, modifier = Modifier.fillMaxWidth().height(170.dp))
       Button(onClick = { requestImage(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Generate Image") }

      } else {
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
