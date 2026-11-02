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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
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
   var adultConfirmed by remember { mutableStateOf(false) }
   var showAdultConfirm by remember { mutableStateOf(false) }
   var fictionalAdultMode by remember { mutableStateOf(false) }
   var showConnection by remember { mutableStateOf(false) }
   var prompt by remember { mutableStateOf("") }
   var original by remember { mutableStateOf<Bitmap?>(null) }
   var brightness by remember { mutableFloatStateOf(0f) }
   var saturation by remember { mutableFloatStateOf(1f) }
   var editStrength by remember { mutableFloatStateOf(0.35f) }
   var maskMode by remember { mutableStateOf(false) }
   var brush by remember { mutableFloatStateOf(0.06f) }
   val strokes = remember { mutableStateListOf<List<Offset>>() }
   var drawing by remember { mutableStateOf<List<Offset>>(emptyList()) }
   val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    if (uri != null) try {
     val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
     contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
     var sample = 1
     while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
     original = contentResolver.openInputStream(uri)?.use {
      BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
     }
     brightness = 0f; saturation = 1f; strokes.clear()
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
      Text(if (tab == -1) "What will you create?" else if (tab == 0) "Turn your ideas into images" else if (tab == 2) "Private 18+ studio" else "Give your photos a fresh look", style = MaterialTheme.typography.titleLarge)
      if (tab == -1) {
       Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(UiColor(0xFF332451), UiColor(0xFF192F49))), RoundedCornerShape(28.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("✦", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.secondary)
        Text("A little imagination.\nEndless possibilities.", style = MaterialTheme.typography.headlineMedium)
        Text("Start with an idea or a photo. Make something your own.", color = UiColor(0xFFD3D0E3))
        Button(onClick = { tab = 0 }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Create Image") }
        OutlinedButton(onClick = { tab = 1 }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Edit Photo") }
        OutlinedButton(onClick = { showAdultConfirm = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("18+ Private Studio") }
       }
       Text("Start with an idea", style = MaterialTheme.typography.titleMedium)
       listOf("A mountain lake under a sky full of stars", "A cozy cabin in an autumn forest", "A neon city street in the rain").forEach { idea ->
        OutlinedButton(onClick = { prompt = idea; tab = 0 }, modifier = Modifier.fillMaxWidth()) { Text(idea) }
       }
      }
      if (showAdultConfirm) AlertDialog(onDismissRequest = { showAdultConfirm = false }, title = { Text("18+ private editor") }, text = { Text("Only continue if you are 18 or older and have permission to use the images. Imported photos remain on this device unless you choose an online AI server. This is an age confirmation, not identity verification.") }, confirmButton = { TextButton(onClick = { adultConfirmed = true; showAdultConfirm = false; tab = 2; fictionalAdultMode = false; result = null }) { Text("I am 18 or older") } }, dismissButton = { TextButton(onClick = { showAdultConfirm = false }) { Text("Cancel") } })
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
       val requestedStrength = editStrength
       val mask = if (input != null && maskMode && strokes.isNotEmpty()) makeMask(input.width, input.height, strokes.toList(), brush) else null
       val inputForValidation = input
       busy = true; status = "Creating image…"
       scope.launch {
        try {
         result = withContext(Dispatchers.IO) {
          if (useLocal) {
           val encoded = input?.let { bitmap ->
            ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP) }
           }
           if (mask != null) error("Painted-area edits require an HTTPS AUTOMATIC1111 server")
           val frame = LocalDreamClient.generate(requestedPrompt, encoded, requestedStrength) { step, total ->
            runOnUiThread { status = "Creating image: step $step of $total" }
           }
           Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
          } else generateImage(address, requestedPrompt, input, requestedStrength, mask)
         }
         if (inputForValidation != null && result != null && isNearlyWhiteImage(result!!)) {
          result = null
          status = "The AI returned a blank white edit. Your original photo is safe. Try again with lower edit strength (25–35%) or restart the model."
         } else {
          status = "Image ready"
         }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { status = if (useLocal) "Local Dream: ${e.message ?: "connection failed"}. Keep the model loaded; try split screen if disconnected." else "Could not create image: ${e.message ?: "connection failed"}"
        } finally { busy = false }
       }
      }
      if (tab == 2 && adultConfirmed) {
       Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
         Text("Adult fictional image creation", style = MaterialTheme.typography.titleMedium)
         Text("Create original, clearly adult fictional characters. Do not use real people's photos to fabricate intimate images or depict anyone under 18.")
         Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
          Text("Create fictional adult image", modifier = Modifier.weight(1f))
          Switch(checked = fictionalAdultMode, onCheckedChange = { fictionalAdultMode = it; result = null })
         }
         if (fictionalAdultMode) {
          OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Describe an original adult character") }, modifier = Modifier.fillMaxWidth().height(150.dp))
          Text("Generation depends on the connected model's capabilities. This switch does not bypass model restrictions.")
          Button(onClick = { requestImage(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Generate Fictional Adult Image") }
         }
        }
       }
      }
      if (tab == 0) {
       OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Describe your image") }, placeholder = { Text("A peaceful beach at sunset, warm light, gentle waves…") }, modifier = Modifier.fillMaxWidth().height(170.dp))
       Button(onClick = { requestImage(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Creating…" else "Create Image") }

      } else if (tab == 1 || (tab == 2 && adultConfirmed && !fictionalAdultMode)) {
       if (tab == 2) Text("Private editing: adjust brightness and saturation, then save to app-private storage. AI edits may send your photo to the selected model/server. Only use images you have permission to edit.")
       Button(enabled = !busy, onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Choose Photo") }
       edited?.let { bitmap ->
        BoxWithConstraints(Modifier.fillMaxWidth()) {
         val displayHeight = maxWidth * bitmap.height.toFloat() / bitmap.width.toFloat()
         Image(bitmap.asImageBitmap(), contentDescription = "Photo preview", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxWidth().height(displayHeight))
         if (maskMode) Canvas(Modifier.fillMaxWidth().height(displayHeight).pointerInput(bitmap, brush) {
          detectDragGestures(onDragStart = { p -> drawing = listOf(Offset(p.x / size.width, p.y / size.height)) },
           onDrag = { change, _ -> change.consume(); drawing = drawing + Offset(change.position.x / size.width, change.position.y / size.height) },
           onDragEnd = { if (drawing.isNotEmpty()) strokes.add(drawing); drawing = emptyList() })
         }) {
          (strokes.toList() + listOf(drawing)).forEach { points ->
           if (points.isNotEmpty()) {
            val path = Path().apply {
             moveTo(points[0].x * size.width, points[0].y * size.height)
             points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
            }
            if (points.size == 1) drawCircle(UiColor.Red.copy(alpha = 0.55f), brush * size.width / 2, Offset(points[0].x * size.width, points[0].y * size.height))
            else drawPath(path, UiColor.Red.copy(alpha = 0.55f), style = Stroke(width = brush * size.width, cap = StrokeCap.Round))
           }
          }
         }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
         Text("Paint area to change", modifier = Modifier.weight(1f))
         Switch(checked = maskMode, onCheckedChange = { maskMode = it })
        }
        if (maskMode) {
         Text("Drag over the area you want to edit. Red marks show the selected area.")
         Slider(value = brush, onValueChange = { brush = it }, valueRange = 0.015f..0.15f)
         OutlinedButton(onClick = { strokes.clear(); drawing = emptyList() }) { Text("Clear selection") }
         Text("Requires an HTTPS AUTOMATIC1111 inpainting server.")
        }
        OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Describe your AI edit") }, placeholder = { Text("Make the bikini top smaller; keep the face, pose and background unchanged.") }, modifier = Modifier.fillMaxWidth())
        Text("Quick clothing edits", style = MaterialTheme.typography.titleMedium)
        listOf(
         "Make the bikini top smaller while keeping the person and background the same",
         "Change the outfit to a red bikini top and denim shorts",
         "Change the outfit color while preserving the face and pose"
        ).forEach { suggestion ->
         OutlinedButton(onClick = { prompt = suggestion }, modifier = Modifier.fillMaxWidth()) { Text(suggestion) }
        }
        Text("AI edit strength: ${(editStrength * 100).roundToInt()}%")
        Slider(value = editStrength, onValueChange = { editStrength = it }, valueRange = 0.15f..0.85f)
        Text("For clothing edits, start around 25–40%. Higher strength may change the face or background. Precise clothing-only editing needs a compatible inpainting model.")
        Button(onClick = { requestImage(bitmap) }, enabled = !busy) { Text("AI Edit Photo") }
        Text("Brightness")
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -0.5f..0.5f)
        Text("Saturation")
        Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..2f)
        Button(onClick = { if (tab == 2) savePrivate(bitmap) else save(bitmap) }, modifier = Modifier.fillMaxWidth()) { Text(if (tab == 2) "Save Privately" else "Save Photo") }
        OutlinedButton(onClick = { brightness = 0f; saturation = 1f }) { Text("Reset") }
       } ?: Text("Pick a photo to edit its brightness and color saturation.")
      }
      result?.let { image ->
       Text("Your creation", style = MaterialTheme.typography.titleLarge)
       Image(image.asImageBitmap(), contentDescription = "Generated image", modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
       Button(onClick = { if (tab == 2) savePrivate(image) else save(image) }) { Text(if (tab == 2) "Save Privately" else "Save Image") }
       OutlinedButton(onClick = { original = image; brightness = 0f; saturation = 1f; result = null; tab = 1 }) { Text("Edit this image") }
      }
     }
    }
   }
  }
 }
 private fun savePrivate(bitmap: Bitmap) {
  try {
   val dir = java.io.File(filesDir, "private_photos").apply { mkdirs() }
   val file = java.io.File(dir, "Photo_${System.currentTimeMillis()}.jpg")
   file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
   toast("Saved inside FreedomAI private storage")
  } catch (e: Exception) { toast("Couldn't save private photo") }
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

private fun generateImage(server: String, prompt: String, input: Bitmap?, editStrength: Float, mask: Bitmap? = null): Bitmap {
 val payload = JSONObject().put("prompt", prompt).put("steps", 20).put("width", 512).put("height", 512).put("batch_size", 1)
 if (input != null) {
  val bytes = ByteArrayOutputStream().use { stream -> input.compress(Bitmap.CompressFormat.PNG, 100, stream); stream.toByteArray() }
  payload.put("init_images", JSONArray().put(Base64.encodeToString(bytes, Base64.NO_WRAP))).put("denoising_strength", editStrength.toDouble())
  if (mask != null) {
   val maskBytes = ByteArrayOutputStream().use { stream -> mask.compress(Bitmap.CompressFormat.PNG, 100, stream); stream.toByteArray() }
   payload.put("mask", Base64.encodeToString(maskBytes, Base64.NO_WRAP)).put("inpainting_mask_invert", 0).put("inpainting_fill", 1).put("inpaint_full_res", true).put("inpaint_full_res_padding", 32)
  }
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


private fun makeMask(width: Int, height: Int, strokes: List<List<Offset>>, brush: Float): Bitmap {
 val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
 val canvas = android.graphics.Canvas(bitmap)
 canvas.drawColor(Color.BLACK)
 val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
  color = Color.WHITE
  strokeWidth = brush * width
  strokeCap = android.graphics.Paint.Cap.ROUND
  strokeJoin = android.graphics.Paint.Join.ROUND
  style = android.graphics.Paint.Style.STROKE
 }
 strokes.forEach { points ->
  if (points.size == 1) canvas.drawCircle(points[0].x * width, points[0].y * height, brush * width / 2, paint)
  else if (points.isNotEmpty()) {
   val path = android.graphics.Path().apply {
    moveTo(points[0].x * width, points[0].y * height)
    points.drop(1).forEach { lineTo(it.x * width, it.y * height) }
   }
   canvas.drawPath(path, paint)
  }
 }
 return bitmap
}

/** Reject near-uniform white output from image-to-image generation instead of showing it as a successful edit. */
private fun isNearlyWhiteImage(bitmap: Bitmap): Boolean {
 val width = bitmap.width
 val height = bitmap.height
 if (width <= 0 || height <= 0) return true
 var white = 0
 var checked = 0
 val stepX = (width / 32).coerceAtLeast(1)
 val stepY = (height / 32).coerceAtLeast(1)
 for (y in 0 until height step stepY) {
  for (x in 0 until width step stepX) {
   val pixel = bitmap.getPixel(x, y)
   if (Color.red(pixel) >= 248 && Color.green(pixel) >= 248 && Color.blue(pixel) >= 248) white++
   checked++
  }
 }
 return checked > 0 && white.toFloat() / checked >= 0.985f
}
