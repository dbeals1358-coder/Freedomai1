package com.unboundaistudio

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
   var nightmareMode by remember { mutableStateOf(prefs.getBoolean("nightmare", true)) }
   var nightmareToken by remember { mutableStateOf(prefs.getString("nightmare_token", "") ?: "") }
   var nightmareEditFlow by remember { mutableStateOf(prefs.getString("nightmare_edit_flow", "img2img") ?: "img2img") }
   var videoUrl by remember { mutableStateOf(prefs.getString("video_url", "") ?: "") }
   var motionPrompt by remember { mutableStateOf("") }
   var duration by remember { mutableIntStateOf(5) }
   var customSeconds by remember { mutableStateOf("") }
   var lastVideo by remember { mutableStateOf<Uri?>(null) }
   var busy by remember { mutableStateOf(false) }
   var status by remember { mutableStateOf("") }
   var result by remember { mutableStateOf<Bitmap?>(null) }
   var originalGeneratedHere by remember { mutableStateOf(false) }
   var resultGeneratedHere by remember { mutableStateOf(false) }
   val scope = rememberCoroutineScope()
   var tab by remember { mutableStateOf(-1) }
   var videoMode by remember { mutableStateOf(false) }
   var comparePosition by remember { mutableFloatStateOf(0.5f) }
   var adultConfirmed by remember { mutableStateOf(false) }
   var showAdultConfirm by remember { mutableStateOf(false) }
   var fictionalAdultMode by remember { mutableStateOf(false) }
   var characterAge by remember { mutableStateOf("30") }
   var appearance by remember { mutableStateOf("") }
   var bodyDescription by remember { mutableStateOf("") }
   var pose by remember { mutableStateOf("") }
   var setting by remember { mutableStateOf("") }
   var photoStyle by remember { mutableStateOf("Photorealistic natural lighting") }
   var highQuality by remember { mutableStateOf(true) }
   var showConnection by remember { mutableStateOf(false) }
   var prompt by remember { mutableStateOf("") }
   var showRemoveClothingConfirm by remember { mutableStateOf(false) }
   var original by remember { mutableStateOf<Bitmap?>(null) }
   var brightness by remember { mutableFloatStateOf(0f) }
   var saturation by remember { mutableFloatStateOf(1f) }
   var editStrength by remember { mutableFloatStateOf(0.30f) }
    var preserveOriginal by remember { mutableStateOf("High") }
    var precisionEdit by remember { mutableStateOf(false) }
    var resolution by remember { mutableIntStateOf(prefs.getInt("resolution", 1024)) }
    var generationPercent by remember { mutableStateOf(0) }
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
     brightness = 0f; saturation = 1f; strokes.clear(); result = if (videoMode) original else null; originalGeneratedHere = false; resultGeneratedHere = false
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
      Text("Boobs v1.4", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
      Text(if (videoMode) "Create video from a photo" else if (tab == -1) "What will you create?" else if (tab == 0) "Turn your ideas into images" else if (tab == 2) "Private 18+ studio" else "Give your photos a fresh look", style = MaterialTheme.typography.titleLarge)
      if (tab == -1) {
       Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(UiColor(0xFF332451), UiColor(0xFF192F49))), RoundedCornerShape(28.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("✦", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.secondary)
        Text("A little imagination.\nEndless possibilities.", style = MaterialTheme.typography.headlineMedium)
        Text("Start with an idea or a photo. Make something your own.", color = UiColor(0xFFD3D0E3))
        Button(onClick = { tab = 0 }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Create Image") }
        OutlinedButton(onClick = { tab = 1 }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Edit Photo") }
        OutlinedButton(onClick = { videoMode = true; tab = 1; result = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Create Video") }
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
      Text("Nightmare (RealVis XL V5)", style = MaterialTheme.typography.titleMedium)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
       Text("Use Nightmare for images")
       Switch(checked = nightmareMode, enabled = !busy, onCheckedChange = { nightmareMode = it; prefs.edit().putBoolean("nightmare", it).apply() })
      }
      OutlinedTextField(value = nightmareToken, onValueChange = {
       nightmareToken = it.trim()
       prefs.edit().putString("nightmare_token", nightmareToken).apply()
      }, label = { Text("Nightmare API token (kept on this phone)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      Text("Creation uses your saved RealVis flow. Editing uses the selected Nightmare workflow.")
      OutlinedTextField(value = nightmareEditFlow, onValueChange = { nightmareEditFlow = it.trim(); prefs.edit().putString("nightmare_edit_flow", nightmareEditFlow).apply() }, label = { Text("Nightmare edit workflow ID") }, supportingText = { Text("Use current for the workflow open in Nightmare, img2img for its standard image-to-image recipe, or saved:Your Flow Name.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedButton(enabled = !busy, onClick = {
       busy = true
       scope.launch {
        try {
         val version = withContext(Dispatchers.IO) { NightmareClient.checkConnection() }
         status = "Nightmare v$version is reachable on port 8820."
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
         status = "Cannot reach Nightmare on port 8820: ${e.message ?: "connection failed"}. Enable its API in Advanced settings."
        } finally { busy = false }
       }
      }) { Text("Check Nightmare connection") }
      HorizontalDivider()
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
      if (tab == 0 || tab == 2) {
       Text("Image resolution")
       Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(512, 768, 1024).forEach { size ->
         OutlinedButton(onClick = { resolution = size; prefs.edit().putInt("resolution", size).apply() }) { Text(if (resolution == size) "✓ $size" else "$size") }
        }
       }
      }
      if (busy) {
        LinearProgressIndicator(progress = { generationPercent / 100f }, modifier = Modifier.fillMaxWidth())
        Text("Creating image: $generationPercent% (estimated until image arrives)")
       }
      if (status.isNotEmpty()) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Text(status, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
      fun requestImage(input: Bitmap?, promptOverride: String? = null) {
       if ((promptOverride ?: prompt).isBlank() && !(tab == 2 && fictionalAdultMode && (appearance.isNotBlank() || bodyDescription.isNotBlank()))) { status = "Describe the image first"; return }
       val address = server.trim().trimEnd('/')
       if (!nightmareMode && !localMode && runCatching { URL(address).let { it.protocol == "https" && it.host.isNotBlank() && it.userInfo == null && it.query == null && it.ref == null } }.getOrDefault(false).not()) {
        status = "Enter a valid HTTPS server URL"; return
       }
       prefs.edit().putString("url", address).apply()
       val useLocal = localMode
       val useNightmare = nightmareMode
       val tokenForRun = nightmareToken
       val editFlowForRun = nightmareEditFlow
       val requestedPrompt = if (tab == 2 && fictionalAdultMode && input == null) {
        val age = characterAge.toIntOrNull()
        if (age == null || age < 18 || age > 100) { status = "Enter an adult age from 18 to 100"; return }
        listOf("Original fictional adult woman, age $age", appearance, bodyDescription, pose, setting, photoStyle, prompt).filter { it.isNotBlank() }.joinToString(", ")
       } else (promptOverride ?: prompt)
       val requestedStrength = if (input != null) editStrength.coerceIn(0.10f, 0.80f) else editStrength
       val requestedHighQuality = highQuality
       val editPrompt = if (input != null) "Edit the supplied source image. Apply only the requested modification. Preserve all unrelated facial features, body proportions, pose, camera framing, lighting, colors, and background. Do not regenerate a different person or scene. Requested change: $requestedPrompt" else requestedPrompt
       if (input != null && precisionEdit && (!maskMode || strokes.isEmpty())) {
        precisionEdit = false // Fall back to full-frame edit instead of silently blocking.
       }
       val mask = if (input != null && maskMode && strokes.isNotEmpty()) makeMask(input.width, input.height, strokes.toList(), brush) else null
              busy = true; generationPercent = 0; status = if (input != null) "Editing photo…" else "Creating image…"
        // Nightmare does not currently expose generation progress through this client.
        // Show an explicitly estimated percentage, capped below 100 until an image arrives.
        val progressJob = scope.launch {
         while (isActive && busy) {
          delay(1500)
          if (busy) generationPercent = (generationPercent + 2).coerceAtMost(95)
         }
        }
       scope.launch {
        try {
         result = withContext(Dispatchers.IO) {
          if (useNightmare) {
           val generated = NightmareClient.generate(editPrompt, tokenForRun, input, requestedStrength, requestedHighQuality, resolution, editFlowForRun)
           check(!isNearlyWhiteImage(generated)) { "The AI returned a blank white image. Try a different flow or restart the model." }
           if (input != null && isSameImage(input, generated)) error("Nightmare returned the original image unchanged; verify the img2img flow and its image input.")
           if (mask != null && input != null) compositeMaskedEdit(input, generated, mask) else if (input != null) preserveDetectedFace(input, preserveFraming(input, generated)) else generated
          } else if (useLocal) {
           val encoded = input?.let { bitmap ->
            ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP) }
           }
           if (mask != null) error("Painted-area edits require an HTTPS AUTOMATIC1111 server")
           val frame = LocalDreamClient.generate(editPrompt, encoded, requestedStrength) { step, total ->
            runOnUiThread { status = "Creating image: step $step of $total" }
           }
           Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
          } else {
           val generated = generateImage(address, editPrompt, input, requestedStrength, mask, requestedHighQuality)
           if (input != null && mask == null) preserveFraming(input, generated) else generated
          }
         }
         if (result != null && isNearlyWhiteImage(result!!)) {
          result = null
          status = "The AI returned a blank white image. Your original photo is safe. Try again with lower edit strength or restart the model."
         } else {
          generationPercent = 100
           resultGeneratedHere = if (input == null) true else originalGeneratedHere
           status = if (input != null) "AI edit complete — review the result below" else "Image ready"
         }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { status = when {
          useNightmare -> "Nightmare ${if (input != null) "edit" else "generation"} failed (flow: ${if (input != null) editFlowForRun else "saved:FreedomAI RealVis"}): ${e.message ?: "connection failed"}. Open the matching flow in Nightmare, check its model and image input."
          useLocal -> "Local Dream: ${e.message ?: "connection failed"}. Keep the model loaded; try split screen if disconnected."
          else -> "Could not create image: ${e.message ?: "connection failed"}"
         }
        } finally { busy = false; progressJob.cancel() }
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
          OutlinedTextField(value = characterAge, onValueChange = { characterAge = it.filter(Char::isDigit).take(3) }, label = { Text("Fictional character age (18+)") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = appearance, onValueChange = { appearance = it }, label = { Text("Appearance: hair, face, eyes") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = bodyDescription, onValueChange = { bodyDescription = it }, label = { Text("Body proportions and physique") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = pose, onValueChange = { pose = it }, label = { Text("Pose") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = setting, onValueChange = { setting = it }, label = { Text("Background / location") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = photoStyle, onValueChange = { photoStyle = it }, label = { Text("Photography and lighting style") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Additional details (optional)") }, modifier = Modifier.fillMaxWidth().height(110.dp))
          Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("High quality", modifier = Modifier.weight(1f)); Switch(checked = highQuality, onCheckedChange = { highQuality = it }) }
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
         Text("With Nightmare, only the painted area is copied from the AI edit; everything outside stays pixel-identical. For true mask-guided generation, a compatible inpainting workflow is still needed.")
        }
        OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Describe your AI edit") }, placeholder = { Text("Make the bikini top smaller; keep the face, pose and background unchanged.") }, modifier = Modifier.fillMaxWidth())
        Text(if (originalGeneratedHere) "Image source: generated in FreedomAI (this session)" else "Image source: imported or unverified")
        Button(onClick = { showRemoveClothingConfirm = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Nude") }
        if (showRemoveClothingConfirm) {
         AlertDialog(
          onDismissRequest = { showRemoveClothingConfirm = false },
          title = { Text("Remove Clothing — eligibility check") },
          text = { Text("Only use this option with original, fictional AI-generated adults aged 18 or older. Do not use photos of real people or anyone whose age is uncertain.") },
          confirmButton = { TextButton(onClick = {
           showRemoveClothingConfirm = false
           prompt = "Image-to-image edit of the PROVIDED SOURCE IMAGE, not a new scene. Keep exactly the same fictional adult character, blonde hair, facial features, identity, body proportions, seated pose, camera framing, beach, rocks, trees, water, lighting and colors. Change only the requested clothing area. Do not replace the person, change hair color, change the background, or move the camera. Photorealistic adult character aged 25 or older."
           precisionEdit = false; maskMode = false
           preserveOriginal = "High"; editStrength = 0.35f
           status = "Clothing edit instructions ready. Starting image edit…"
           requestImage(bitmap, "Image-to-image edit of the PROVIDED SOURCE IMAGE, not a new scene. Keep exactly the same fictional adult character, blonde hair, facial features, identity, body proportions, seated pose, camera framing, beach, rocks, trees, water, lighting and colors. Change only the requested clothing area. Do not replace the person, change hair color, change the background, or move the camera. Photorealistic adult character aged 25 or older.")
          }) { Text("Eligible fictional adult") } },
          dismissButton = { TextButton(onClick = { showRemoveClothingConfirm = false }) { Text("Cancel") } }
         )
        }
        Button(onClick = { status = "Starting AI photo edit…"; requestImage(result ?: bitmap) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("AI Edit") }
        Text("Quick clothing edits", style = MaterialTheme.typography.titleMedium)
        listOf(
         "Make the bikini top smaller while keeping the person and background the same",
         "Change the outfit to a red bikini top and denim shorts",
         "Change the outfit color while preserving the face and pose"
        ).forEach { suggestion ->
         OutlinedButton(onClick = { prompt = suggestion }, modifier = Modifier.fillMaxWidth()) { Text(suggestion) }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
         Text("Precision Edit (painted area only)", modifier = Modifier.weight(1f))
         Switch(checked = precisionEdit, enabled = !busy, onCheckedChange = { precisionEdit = it; if (it) maskMode = true })
        }
        Text("Precision Edit requires painting a selection; pixels outside it stay unchanged. Switch off for full-photo edits.")
        Text("Preserve Original")
         Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf("High", "Medium", "Low").forEach { level ->
           OutlinedButton(onClick = {
            preserveOriginal = level
            editStrength = when (level) { "High" -> 0.16f; "Medium" -> 0.35f; else -> 0.60f }
           }) { Text(if (preserveOriginal == level) "✓ $level" else level) }
          }
         }
         Text("High keeps more of the original; Low allows larger changes. Paint an area to protect the rest of the photo exactly.")
         Text("AI edit strength: ${(editStrength * 100).roundToInt()}%")
        Slider(value = editStrength, onValueChange = { editStrength = it }, valueRange = 0.15f..0.85f)
        Text("For clothing edits, start around 25–40%. Higher strength may change the face or background. Precise clothing-only editing needs a compatible inpainting model.")
        Text("Brightness")
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -0.5f..0.5f)
        Text("Saturation")
        Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..2f)
        Button(onClick = { if (tab == 2) savePrivate(bitmap) else save(bitmap) }, modifier = Modifier.fillMaxWidth()) { Text(if (tab == 2) "Save Privately" else "Save Photo") }
        OutlinedButton(onClick = { brightness = 0f; saturation = 1f }) { Text("Reset") }
       } ?: Text("Pick a photo to edit its brightness and color saturation.")
      }
      result?.let { image ->
       Text(if (videoMode) "Video source image" else "Your creation", style = MaterialTheme.typography.titleLarge)
       Image(image.asImageBitmap(), contentDescription = "Generated image", modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
       if (!videoMode && original != null && tab == 1) {
        val before = original!!
        Text("Before / After comparison", style = MaterialTheme.typography.titleMedium)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
         val w = constraints.maxWidth.coerceAtLeast(1)
         val h = (w.toFloat() * image.height / image.width).toInt().coerceAtLeast(1)
         val beforePixels = remember(before, image) {
          val base = Bitmap.createScaledBitmap(before, w.coerceAtMost(1080), (w.coerceAtMost(1080) * h / w).coerceAtLeast(1), true)
          base
         }
         Box(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
          Image(image.asImageBitmap(), contentDescription = "After", modifier = Modifier.fillMaxWidth())
          Image(beforePixels.asImageBitmap(), contentDescription = "Before", modifier = Modifier.fillMaxWidth().drawWithContent {
           clipRect(right = size.width * comparePosition) { this@drawWithContent.drawContent() }
          })
         }
        }
        Text("Before  ←  Slide to compare  →  After")
        Slider(value = comparePosition, onValueChange = { comparePosition = it }, valueRange = 0f..1f)
       }
       Button(onClick = { if (tab == 2) savePrivate(image) else save(image) }) { Text(if (tab == 2) "Save Privately" else "Save Image") }
       Text("Create Video", style = MaterialTheme.typography.titleLarge)
       Text("Video rendering requires a connected engine. Offline realistic AI motion is not yet installed.")
       OutlinedTextField(value = videoUrl, onValueChange = {
        videoUrl = it.trim()
        prefs.edit().putString("video_url", videoUrl).apply()
       }, label = { Text("HTTPS video API endpoint") }, modifier = Modifier.fillMaxWidth())
       OutlinedTextField(value = motionPrompt, onValueChange = { motionPrompt = it },
        label = { Text("Describe motion") }, modifier = Modifier.fillMaxWidth())
       Text("Duration")
       listOf(3, 5, 10, 15, 30, 60).chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
         row.forEach { seconds ->
          OutlinedButton(onClick = { duration = seconds; customSeconds = "" },
           modifier = Modifier.weight(1f)) { Text(if (duration == seconds && customSeconds.isEmpty()) "✓ ${seconds}s" else "${seconds}s") }
         }
        }
       }
       OutlinedTextField(value = customSeconds, onValueChange = { customSeconds = it.filter { c -> c.isDigit() }.take(3) },
        label = { Text("Custom duration in seconds") }, modifier = Modifier.fillMaxWidth())
       Button(enabled = !busy, onClick = {
        val seconds = customSeconds.toIntOrNull() ?: duration
        if (seconds !in 1..120) { status = "Choose 1–120 seconds" }
        else if (motionPrompt.isBlank()) { status = "Describe motion first" }
        else if (!videoUrl.startsWith("https://")) { status = "Offline AI video engine is not installed yet. Video cannot be generated without an engine." }
        else {
         busy = true
         status = "Creating video…"
         scope.launch {
          try {
           val mp4 = withContext(Dispatchers.IO) { generateVideoMp4(videoUrl, image, motionPrompt, seconds) }
           val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "FreedomAI_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FreedomAI")
            put(MediaStore.Video.Media.IS_PENDING, 1)
           }
           val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Cannot save video")
           try {
            withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)?.use { it.write(mp4) } ?: error("Cannot write MP4") }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            lastVideo = uri
            status = "Video saved to Movies/FreedomAI"
           } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
          } catch (e: Exception) { status = "Video failed: ${e.message}" }
          finally { busy = false }
         }
        }
       }, modifier = Modifier.fillMaxWidth()) { Text("Generate Video") }
       lastVideo?.let { uri ->
        OutlinedButton(onClick = {
         startActivity(Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(uri, "video/mp4")
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
         })
        }) { Text("Play saved MP4") }
       }
       OutlinedButton(onClick = { videoMode = false; original = image; originalGeneratedHere = resultGeneratedHere; brightness = 0f; saturation = 1f; strokes.clear(); maskMode = false; precisionEdit = false; result = null; tab = 1 }) { Text("Edit this image") }
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

private fun generateImage(server: String, prompt: String, input: Bitmap?, editStrength: Float, mask: Bitmap? = null, highQuality: Boolean = true): Bitmap {
 val payload = JSONObject().put("prompt", prompt).put("negative_prompt", "cartoon, anime, illustration, CGI, plastic skin, waxy skin, blurry, distorted anatomy, extra fingers, deformed face, watermark, text").put("steps", if (highQuality) 35 else 20).put("cfg_scale", 6.0).put("width", if (input == null && highQuality) 768 else ((input?.width ?: 512).coerceIn(256, 1024) / 8 * 8)).put("height", if (input == null && highQuality) 1024 else ((input?.height ?: 512).coerceIn(256, 1024) / 8 * 8)).put("batch_size", 1)
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

/** Preserve the original pixels outside the user-painted region after Nightmare img2img. */
private fun compositeMaskedEdit(original: Bitmap, generated: Bitmap, mask: Bitmap): Bitmap {
 val width = original.width
 val height = original.height
 val resized = if (generated.width != width || generated.height != height) Bitmap.createScaledBitmap(generated, width, height, true) else generated
 val result = original.copy(Bitmap.Config.ARGB_8888, true)
 val canvas = android.graphics.Canvas(result)
 val maskAlpha = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
 val maskPixels = IntArray(width * height)
 mask.getPixels(maskPixels, 0, width, 0, 0, width, height)
 for (i in maskPixels.indices) {
  val p = maskPixels[i]
  val v = Color.red(p)
  maskPixels[i] = Color.argb(v, 255, 255, 255)
 }
 maskAlpha.setPixels(maskPixels, 0, width, 0, 0, width, height)
 val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
 canvas.drawBitmap(resized, 0f, 0f, null)
 val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
  xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
 }
 canvas.drawBitmap(maskAlpha, 0f, 0f, paint)
 paint.xfermode = null
 canvas.restoreToCount(layer)
 maskAlpha.recycle()
 return result
}

/** Match the original image canvas dimensions for full-frame edits. */
private fun preserveFraming(original: Bitmap?, generated: Bitmap): Bitmap {
 if (original == null || (original.width == generated.width && original.height == generated.height)) return generated
 return Bitmap.createScaledBitmap(generated, original.width, original.height, true)
}

/**
 * Automatically restore the source character's face after a strong full-frame edit.
 * Android's built-in face detector can miss angled or stylized faces; in that case
 * leave the generated result untouched rather than guessing a face location.
 * The soft elliptical blend avoids a sharp pasted-face rectangle.
 */
private fun preserveDetectedFace(original: Bitmap, generated: Bitmap): Bitmap {
 return runCatching {
  val width = original.width
  val height = original.height
  val evenWidth = if (width % 2 == 0) width else width - 1
  if (evenWidth < 2 || height < 2) return@runCatching generated
  val detectorInput = Bitmap.createBitmap(evenWidth, height, Bitmap.Config.RGB_565)
  android.graphics.Canvas(detectorInput).drawBitmap(original, 0f, 0f, null)
  val faces = arrayOfNulls<android.media.FaceDetector.Face>(5)
  val count = android.media.FaceDetector(evenWidth, height, faces.size).findFaces(detectorInput, faces)
  detectorInput.recycle()
  if (count != 1) return@runCatching generated
  val face = faces[0] ?: return@runCatching generated
  val midpoint = android.graphics.PointF()
  face.getMidPoint(midpoint)
  val eyeDistance = face.eyesDistance()
  if (eyeDistance <= 1f) return@runCatching generated
  val cx = midpoint.x
  val cy = midpoint.y + eyeDistance * 0.55f
  val rx = eyeDistance * 1.75f
  val ry = eyeDistance * 2.25f
  val result = generated.copy(Bitmap.Config.ARGB_8888, true)
  val canvas = android.graphics.Canvas(result)
  val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
  canvas.drawBitmap(original, 0f, 0f, null)
  val radial = android.graphics.RadialGradient(
   0f, 0f, 1f, intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
   floatArrayOf(0f, 0.68f, 1f), android.graphics.Shader.TileMode.CLAMP
  )
  val transform = android.graphics.Matrix().apply { setScale(rx, ry); postTranslate(cx, cy) }
  radial.setLocalMatrix(transform)
  val maskPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
   shader = radial
   xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
  }
  canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
  maskPaint.xfermode = null
  canvas.restoreToCount(layer)
  result
 }.getOrDefault(generated)
}

private fun isSameImage(a: Bitmap, b: Bitmap): Boolean {
 if (a.width != b.width || a.height != b.height) return false
 val sx = (a.width / 24).coerceAtLeast(1)
 val sy = (a.height / 24).coerceAtLeast(1)
 var different = 0
 for (y in 0 until a.height step sy) for (x in 0 until a.width step sx) {
  val c = a.getPixel(x, y); val d = b.getPixel(x, y)
  if (kotlin.math.abs(Color.red(c)-Color.red(d)) + kotlin.math.abs(Color.green(c)-Color.green(d)) + kotlin.math.abs(Color.blue(c)-Color.blue(d)) > 24) different++
 }
 return different < 3
}

/** API contract: POST JSON image_base64, prompt, duration_seconds; response video/mp4 bytes. */
private fun generateVideoMp4(endpoint: String, bitmap: Bitmap, prompt: String, seconds: Int): ByteArray {
 val url = URL(endpoint)
 require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) { "HTTPS required" }
 val png = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
 val payload = JSONObject().put("image_base64", Base64.encodeToString(png, Base64.NO_WRAP))
  .put("prompt", prompt).put("duration_seconds", seconds)
 val connection = url.openConnection() as HttpURLConnection
 try {
  connection.requestMethod = "POST"
  connection.connectTimeout = 15000
  connection.readTimeout = 300000
  connection.instanceFollowRedirects = false
  connection.doOutput = true
  connection.setRequestProperty("Content-Type", "application/json")
  connection.setRequestProperty("Accept", "video/mp4")
  connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
  check(connection.responseCode in 200..299) { "Video API HTTP ${connection.responseCode}" }
  check(connection.contentType?.substringBefore(';') == "video/mp4") { "Video API must return video/mp4" }
  val output = ByteArrayOutputStream()
  connection.inputStream.use { input ->
   val buffer = ByteArray(8192)
   while (true) {
    val n = input.read(buffer)
    if (n < 0) break
    check(output.size() + n <= 200 * 1024 * 1024) { "Video exceeds 200MB" }
    output.write(buffer, 0, n)
   }
  }
  val bytes = output.toByteArray()
  check(bytes.size > 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp") { "Invalid MP4" }
  return bytes
 } finally { connection.disconnect() }
}
