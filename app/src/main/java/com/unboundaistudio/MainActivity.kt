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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
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
   // Standalone v3 upgrade: preserve user data and already-downloaded models.
   remember(prefs) {
    if (!prefs.getBoolean("standalone_v3_initialized", false)) {
     prefs.edit().putBoolean("nightmare", false).putBoolean("local", false)
      .putBoolean("native_qwen", true).putBoolean("online_qwen", false)
      .putBoolean("video_mobilei2v_experimental", true)
      .putBoolean("standalone_v3_initialized", true).apply()
     getSharedPreferences("assistant", MODE_PRIVATE).edit()
      .putBoolean("use_offline_assistant", true).apply()
    }
    true
   }
   var server by remember { mutableStateOf(prefs.getString("url", "") ?: "") }
   var localMode by remember { mutableStateOf(prefs.getBoolean("local", false)) }
   var nightmareMode by remember { mutableStateOf(prefs.getBoolean("nightmare", false)) }
   var nightmareToken by remember { mutableStateOf((prefs.getString("nightmare_token", "") ?: "").ifBlank { BuildConfig.NIGHTMARE_API_TOKEN }) }
   var nightmareEditFlow by remember {
    // Migrate the old img2img default. That flow restyles instead of making precise changes.
    if (!prefs.getBoolean("edit_flow_migrated_v3", false)) {
     val old = prefs.getString("nightmare_edit_flow", "img2img") ?: "img2img"
     prefs.edit().putString("nightmare_edit_flow", if (old == "img2img") "auto" else old)
      .putBoolean("edit_flow_migrated_v3", true).apply()
    }
    mutableStateOf(prefs.getString("nightmare_edit_flow", "auto") ?: "auto")
   }
   var videoUrl by remember { mutableStateOf(prefs.getString("video_url", "") ?: "") }
   var motionPrompt by remember { mutableStateOf("") }
   var nightmareVideoFlow by remember { mutableStateOf(prefs.getString("nightmare_video_flow", "auto") ?: "auto") }
   var nightmareImageFlow by remember {
    mutableStateOf(prefs.getString("nightmare_image_flow", "saved:FreedomAI RealVis")?.ifBlank { "saved:FreedomAI RealVis" } ?: "saved:FreedomAI RealVis")
   }
   var useMobileI2V by remember { mutableStateOf(prefs.getBoolean("video_mobilei2v_experimental", true)) }
    var runwayKey by remember { mutableStateOf(prefs.getString("runway_key", "") ?: "") }
   var duration by remember { mutableIntStateOf(prefs.getInt("video_duration_seconds", 5).coerceIn(1, 10)) }
   var customSeconds by remember { mutableStateOf("") }
   // The most recently saved MP4 remains playable when the app is reopened.
   var lastVideo by remember {
    mutableStateOf(prefs.getString("last_video_uri", null)?.let { Uri.parse(it) })
   }
   var showVideoPlayer by remember { mutableStateOf(false) }
   // Find videos exported by older app versions, even if they did not save the URI.
   LaunchedEffect(Unit) {
    if (lastVideo == null) {
     val found = withContext(Dispatchers.IO) {
      runCatching {
       contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID),
        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
        arrayOf("Movies/FreedomAI/%"),
        "${MediaStore.Video.Media.DATE_ADDED} DESC"
       )?.use { cursor ->
        if (cursor.moveToFirst()) {
         android.content.ContentUris.withAppendedId(
          MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
          cursor.getLong(0)
         )
        } else null
       }
      }.getOrNull()
     }
     if (found != null && lastVideo == null) {
      lastVideo = found
      prefs.edit().putString("last_video_uri", found.toString()).apply()
     }
    }
   }
   var lastVideoGenerator by remember { mutableStateOf(prefs.getString("last_video_generator", "Not recorded") ?: "Not recorded") }
   var busy by remember { mutableStateOf(false) }
   var generationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
   // Native Qwen editor is fully on-device. Image generation still uses the selected backend.
   var nativeQwen by remember { mutableStateOf(prefs.getBoolean("native_qwen", true)) }
   var imageAspect by remember {
    mutableStateOf(prefs.getString("image_aspect", "Portrait")?.takeIf { it in listOf("Square", "Portrait", "Landscape") } ?: "Portrait")
   }
   var onlineQwen by remember { mutableStateOf(prefs.getBoolean("online_qwen", false)) }
   var onlineQwenUrl by remember { mutableStateOf(prefs.getString("online_qwen_url", "") ?: "") }
   var onlineQwenModel by remember { mutableStateOf(prefs.getString("online_qwen_model", "qwen-image-2.1-turbo") ?: "qwen-image-2.1-turbo") }
   var onlineQwenKey by remember { mutableStateOf(prefs.getString("online_qwen_key", "") ?: "") }
   var qwenTier by remember { mutableStateOf(prefs.getString("qwen_tier", "FAST") ?: "FAST") }
   var qwenReady by remember { mutableStateOf(runCatching { NativeQwenEditor.ready(this@MainActivity) }.getOrDefault(false)) }
   var qwenDownloading by remember { mutableStateOf(false) }
   var qwenDownloadPercent by remember { mutableIntStateOf(0) }
   var qwenDownloadText by remember { mutableStateOf("") }
   var nativeProgress by remember { mutableStateOf(false) }
   var videoElapsedSeconds by remember { mutableIntStateOf(0) }
   var videoJob by remember { mutableStateOf<Job?>(null) }
   var status by remember { mutableStateOf("") }
   var nightmareConnectionFailed by remember { mutableStateOf(false) }
   var result by remember { mutableStateOf<Bitmap?>(null) }
   var originalGeneratedHere by remember { mutableStateOf(false) }
   var resultGeneratedHere by remember { mutableStateOf(false) }
   val scope = rememberCoroutineScope()
   // Use Android's native Save As picker; leave the original MP4 in Movies/FreedomAI.
   var exportVideoSource by remember { mutableStateOf<Uri?>(null) }
   val exportVideoLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("video/mp4")
   ) { destination: Uri? ->
    val source = exportVideoSource
    exportVideoSource = null
    if (destination != null && source != null) {
     scope.launch {
      try {
       withContext(Dispatchers.IO) {
        contentResolver.openInputStream(source)?.use { input ->
         contentResolver.openOutputStream(destination, "w")?.use { output ->
          input.copyTo(output)
          output.flush()
         } ?: error("Could not write video to the selected folder.")
        } ?: error("Original video is no longer accessible.")
       }
       status = "Video saved to your selected folder. Original remains in Movies/FreedomAI."
      } catch (e: Exception) {
       status = "Save Video failed: ${e.message ?: "Unable to copy MP4"}"
      }
     }
    }
   }
   var tab by remember { mutableStateOf(-1) }
   var videoMode by remember { mutableStateOf(false) }
   var showStripeFix by remember { mutableStateOf(false) }
   var stripePosition by remember { mutableFloatStateOf(0.14f) }
   var stripeRepairUndo by remember { mutableStateOf<Bitmap?>(null) }
   var comparePosition by remember { mutableFloatStateOf(0.5f) }
   var adultConfirmed by remember { mutableStateOf(false) }
   var showAdultConfirm by remember { mutableStateOf(false) }
   var fictionalAdultMode by remember { mutableStateOf(false) }
   var characterAge by remember { mutableStateOf("30") }
   var anatomyChoice by remember {
    mutableStateOf(prefs.getString("fictional_anatomy", "Default")?.takeIf { it in listOf("Default", "Male anatomy", "Female anatomy", "Both anatomies") } ?: "Default")
   }
   var sceneCount by remember {
    mutableStateOf(prefs.getString("fictional_scene_count", "Single")?.takeIf { it in listOf("Single", "Couple", "Group") } ?: "Single")
   }
   var characterSubject by remember {
    mutableStateOf(prefs.getString("fictional_adult_subject", "Man")?.takeIf { it in listOf("Man", "Woman", "Person") } ?: "Man")
   }
   var appearance by remember { mutableStateOf("") }
   var bodyDescription by remember { mutableStateOf("") }
   var pose by remember { mutableStateOf("") }
   var setting by remember { mutableStateOf("") }
   var photoStyle by remember { mutableStateOf("Photorealistic natural lighting") }
   var highQuality by remember { mutableStateOf(true) }
   var showConnection by remember { mutableStateOf(false) }
   var showAdvancedConnection by remember { mutableStateOf(false) }
   var prompt by remember { mutableStateOf("") }
    var cartoonMode by remember { mutableStateOf(false) }
    var cartoonStyle by remember { mutableStateOf(CartoonImagePrompt.DEFAULT_STYLE) }
   var pendingAssistantImage by remember { mutableStateOf<String?>(null) }
   var showRemoveClothingConfirm by remember { mutableStateOf(false) }
   var original by remember { mutableStateOf<Bitmap?>(null) }
   var brightness by remember { mutableFloatStateOf(0f) }
   var saturation by remember { mutableFloatStateOf(1f) }
   var editStrength by remember { mutableFloatStateOf(0.30f) }
    var preserveOriginal by remember { mutableStateOf("High") }
    var precisionEdit by remember { mutableStateOf(false) }
    var resolution by remember { mutableIntStateOf(prefs.getInt("resolution", 1024)) }
    var generationPercent by remember { mutableStateOf(0) }
    var generationSpeed by remember { mutableStateOf(prefs.getString("generation_speed", "BALANCED") ?: "BALANCED") }
    // Keep photo generation quality separate from the faster default AI editing mode.
    var editSpeed by remember { mutableStateOf(prefs.getString("edit_speed", "FAST") ?: "FAST") }
    var imageElapsedSeconds by remember { mutableIntStateOf(0) }
    var renderingPercent by remember { mutableIntStateOf(0) }
   LaunchedEffect(videoMode, busy) {
    if (videoMode && busy) {
     videoElapsedSeconds = 0
     while (isActive && busy) {
      delay(1000)
      if (busy) videoElapsedSeconds++
     }
    }
   }
   fun cancelGeneration() {
    // Disconnect blocking Nightmare HTTP I/O as well as cancelling the coroutine.
    NightmareVideoClient.cancelActive()
    generationJob?.cancel()
    videoJob?.cancel()
    generationJob = null
    videoJob = null
    busy = false
    nativeProgress = false
    generationPercent = 0
    imageElapsedSeconds = 0
    videoElapsedSeconds = 0
    status = "Cancelled on phone. Native model may finish its current step; cancelled output will be discarded."
   }

   var maskMode by remember { mutableStateOf(false) }
   var brush by remember { mutableFloatStateOf(0.06f) }
   val strokes = remember { mutableStateListOf<List<Offset>>() }
   var drawing by remember { mutableStateOf<List<Offset>>(emptyList()) }
   val promptSpeechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
    val spoken = response.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
    if (!spoken.isNullOrBlank()) {
     prompt = listOf(prompt.trim(), spoken.trim()).filter { it.isNotBlank() }.joinToString(" ")
     status = "Voice prompt added. Review it before generating."
    } else { status = "No speech captured. Try the microphone again." }
   }
   fun listenForPrompt() {
    try {
     promptSpeechLauncher.launch(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
      putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Describe what you want")
     })
    } catch (e: Exception) { status = "Speech recognition unavailable. Check voice input services." }
   }
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
    } catch (e: Exception) { Toast.makeText(this@MainActivity, "Couldn't open image", Toast.LENGTH_LONG).show() }
   }
   var motionModelMessage by remember { mutableStateOf("") }
   val motionModelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
     scope.launch {
      busy = true
      try {
       motionModelMessage = "Importing motion model…"
       val filename = android.provider.OpenableColumns.DISPLAY_NAME.let { column ->
        contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
         if (cursor.moveToFirst()) cursor.getString(0) else null
        }
       } ?: ""
       val allowed = setOf("vae_encoder.onnx", "qwen2_encoder.onnx", "mobilei2v_unet.onnx", "turbo_vaed.onnx")
       require(filename in allowed) { "Choose one of: " + allowed.joinToString() }
       val dir = java.io.File(filesDir, "models/video-v1").apply { mkdirs() }
       val target = java.io.File(dir, filename)
       withContext(Dispatchers.IO) {
        val temp = java.io.File(dir, filename + ".partial")
        try {
         contentResolver.openInputStream(uri)?.use { input ->
          temp.outputStream().use { output -> input.copyTo(output) }
         } ?: error("Cannot open model file")
         require(temp.length() >= 1024) { "Model file is empty or truncated" }
         if (target.exists()) target.delete()
         check(temp.renameTo(target)) { "Cannot save model file" }
        } finally { temp.delete() }
       }
       motionModelMessage = "Imported $filename (" + (target.length() / 1048576) + " MB)."
      } catch (e: Exception) {
       motionModelMessage = "Model import failed: " + (e.message ?: "Unknown error")
      } finally { busy = false }
     }
    }
   }
   var expandedPhoto by remember { mutableStateOf<Bitmap?>(null) }
   val edited = remember(original, brightness, saturation) { original?.let { adjust(it, brightness, saturation) } }
   fun openNightmare() {
    val intent = packageManager.getLaunchIntentForPackage("com.abrah.nightmare")
    if (intent == null) {
     status = "Nightmare app not found. Open Nightmare from your phone's app list."
    } else {
     runCatching { startActivity(intent) }.onFailure {
      status = "Could not open Nightmare: ${it.message ?: "Try launching it from your app list."}"
     }
    }
   }
   fun checkNightmareConnection() {
    busy = true
    scope.launch {
     try {
      val version = withContext(Dispatchers.IO) { NightmareClient.checkConnection() }
      nightmareConnectionFailed = false
      status = "Nightmare v$version is connected on port 8820. You can generate now."
     } catch (e: kotlinx.coroutines.CancellationException) { throw e
     } catch (e: Exception) {
      nightmareConnectionFailed = true
      status = NightmareConnectionDiagnostics.helpFor(e)
       ?: "Nightmare API check failed: ${e.message ?: "unexpected response"}. Open Nightmare and confirm its API is enabled."
     } finally { busy = false }
    }
   }
   fun installBuiltInQwenModels() {
     if (qwenDownloading || busy) return
     qwenDownloading = true
     qwenDownloadText = "Installing built-in Qwen model"
     qwenDownloadPercent = 0
     scope.launch {
      try {
       withContext(Dispatchers.IO) {
        NativeQwenEditor.download(this@MainActivity) { name, percent ->
         runOnUiThread {
          qwenDownloadText = name
          qwenDownloadPercent = percent
         }
        }
       }
       qwenReady = NativeQwenEditor.ready(this@MainActivity)
       status = if (qwenReady) "Built-in Qwen image AI ready; Nightmare is not needed." else "Qwen model download incomplete. Tap Install to resume."
      } catch (e: kotlinx.coroutines.CancellationException) { throw e
      } catch (e: Exception) {
       status = "Qwen download: " + (e.message ?: "failed") + ". Tap Install to resume."
      } finally { qwenDownloading = false }
     }
    }
    MaterialTheme(colorScheme = darkColorScheme(
    primary = UiColor(0xFF98F5D0), onPrimary = UiColor(0xFF10372A),
    secondary = UiColor(0xFF8AC7FF), background = UiColor(0xFF101116),
    surface = UiColor(0xFF191B24), surfaceVariant = UiColor(0xFF252836)
   )) {
    if (showVideoPlayer && lastVideo != null) {
     val playbackUri = lastVideo!!
     androidx.compose.ui.window.Dialog(
      onDismissRequest = { showVideoPlayer = false },
      properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
     ) {
      Column(Modifier.fillMaxSize().background(UiColor.Black).padding(16.dp),
       verticalArrangement = Arrangement.spacedBy(12.dp)) {
       Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Your generated video", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = { showVideoPlayer = false }) { Text("Close") }
       }
       androidx.compose.runtime.key(playbackUri) {
        androidx.compose.ui.viewinterop.AndroidView(
         factory = { ctx ->
          android.widget.VideoView(ctx).apply {
           setMediaController(android.widget.MediaController(ctx))
           setOnErrorListener { _, _, _ ->
            runOnUiThread {
             showVideoPlayer = false
             status = "Could not play the MP4. Try opening Movies/FreedomAI in Gallery."
            }
            true
           }
           setVideoURI(playbackUri)
           setOnPreparedListener { player ->
            player.isLooping = false
            start()
           }
          }
         }, modifier = Modifier.fillMaxWidth().height(280.dp)
        )
       }
       Button(enabled = !busy, onClick = {
         exportVideoSource = playbackUri
         exportVideoLauncher.launch("FreedomAI_Video_${System.currentTimeMillis()}.mp4")
        }, modifier = Modifier.fillMaxWidth()) { Text("Save Video") }
        Text("Automatically saved in Movies/FreedomAI. Save Video lets you choose another folder.",
        color = UiColor.White)
      }
     }
    }
    expandedPhoto?.let { enlarged ->
     androidx.compose.ui.window.Dialog(
      onDismissRequest = { expandedPhoto = null },
      properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
     ) {
      var zoom by remember(enlarged) { mutableFloatStateOf(1f) }
      var pan by remember(enlarged) { mutableStateOf(Offset.Zero) }
      Box(Modifier.fillMaxSize().background(UiColor.Black)) {
       Image(enlarged.asImageBitmap(), contentDescription = "Expanded photo; pinch to zoom and drag to pan",
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
         .pointerInput(enlarged) {
          detectTransformGestures { _, drag, scale, _ ->
           zoom = (zoom * scale).coerceIn(1f, 8f)
           pan = if (zoom <= 1f) Offset.Zero else Offset(
            (pan.x + drag.x).coerceIn(-size.width * (zoom - 1f) / 2f, size.width * (zoom - 1f) / 2f),
            (pan.y + drag.y).coerceIn(-size.height * (zoom - 1f) / 2f, size.height * (zoom - 1f) / 2f))
          }
         }
         .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y })
       Row(modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd)) {
        TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("Reset zoom") }
        TextButton(onClick = { expandedPhoto = null }) { Text("Close") }
       }
      }
     }
    }
    Scaffold(bottomBar = {
     NavigationBar {
      NavigationBarItem(selected = tab == -1, onClick = { tab = -1; videoMode = false; prompt = ""; motionPrompt = ""; status = ""; result = null; original = null; generationPercent = 0; brightness = 0f; saturation = 1f; strokes.clear(); drawing = emptyList(); maskMode = false; expandedPhoto = null; pendingAssistantImage = null; originalGeneratedHere = false; resultGeneratedHere = false }, icon = { Text("⌂") }, label = { Text("Home") })
      NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("✦") }, label = { Text("Create") })
      NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("◈") }, label = { Text("Edit") })
      NavigationBarItem(selected = tab == 3, onClick = { tab = 3; videoMode = false }, icon = { Text("AI") }, label = { Text("Assistant") })
     }
    }) { inset ->
     Column(Modifier.padding(inset).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
      Text("Boob's V-3.0", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
      Text(if (videoMode) "Create video from a photo" else if (tab == -1) "What will you create?" else if (tab == 0) "Turn your ideas into images" else if (tab == 3) "Your conversational AI" else if (tab == 2) "Private 18+ studio" else "Give your photos a fresh look", style = MaterialTheme.typography.titleLarge)
      if (tab == 3) {
       AssistantScreen(
         onCreateImage = { request -> prompt = request; cartoonMode = false; videoMode = false; tab = 0; pendingAssistantImage = request; status = "Assistant is starting image generation…" },
         onEditImage = { request -> prompt = request; videoMode = false; tab = 1; status = "Edit instructions ready. Select an image and tap AI Edit." },
         onCreateVideo = { request -> motionPrompt = request; videoMode = true; tab = 1; status = "Video request ready. Select a photo and tap Generate AI Video." }
        )
      }
      if (tab == -1) {
       Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(UiColor(0xFF164A3C), UiColor(0xFF192F49))), RoundedCornerShape(28.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("✦", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Text("Build it, Dream it, Crank it!", style = MaterialTheme.typography.headlineLarge)
        Text("Your AI Studio — create or edit photos and videos.", color = UiColor(0xFFD3D0E3))
        Text("Image engine: ${ImageEngineSelection.imageLabel(nightmareMode, localMode, nativeQwen)}", color = UiColor.White)
        OutlinedButton(enabled = !busy, onClick = {
         val choice = ImageEngineSelection.withoutNightmare()
         nightmareMode = choice.nightmare
         localMode = choice.localDream
         useMobileI2V = choice.offlineVideo
         nativeQwen = true
         onlineQwen = false
         nightmareConnectionFailed = false
         prefs.edit().putBoolean("nightmare", nightmareMode).putBoolean("local", localMode)
          .putBoolean("video_mobilei2v_experimental", useMobileI2V)
          .putBoolean("native_qwen", true).putBoolean("online_qwen", false).apply()
         status = "Nightmare off. Built-in Qwen generates and edits images once installed; MobileI2V video is experimental."
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
         Text(if (!nightmareMode && !localMode && nativeQwen && useMobileI2V) "✓ Without Nightmare Selected" else "Use Without Nightmare")
        }
        if (!nightmareMode) Text("No Nightmare required. Qwen image generation and editing run locally after their model files are installed. MobileI2V video is experimental.", color = UiColor(0xFFD3D0E3))
        Button(onClick = { cartoonMode = false; videoMode = false; tab = 0 }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Create Image") }
         OutlinedButton(onClick = {
          cartoonMode = true
          videoMode = false
          tab = 0
          prompt = ""
          cartoonStyle = CartoonImagePrompt.DEFAULT_STYLE
          result = null
          status = "Describe any cartoon you want. Customize the art style or leave the default."
         }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Create Cartoon Image") }
        OutlinedButton(onClick = { tab = 1 }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Edit Photo") }
        OutlinedButton(onClick = {
         videoMode = false
         tab = 1
         maskMode = false
         strokes.clear()
         prompt = "Create a new photorealistic image very similar to this reference photo. Preserve the same subject, facial features, hairstyle, pose, camera angle, lighting, composition and background, with only subtle natural variations. Maintain realistic detail and proportions."
         editStrength = 0.30f
         status = "Choose a reference image, then tap AI Edit to generate a similar variation. Adjust the prompt if desired."
         picker.launch("image/*")
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Create Similar Image") }

        OutlinedButton(onClick = { videoMode = true; tab = 1; result = null; status = "" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Create Video") }
        OutlinedButton(onClick = { showAdultConfirm = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("18+ Private Studio") }
        OutlinedButton(onClick = { tab = 3 }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("AI Assistant") }
        OutlinedButton(onClick = {
         val search = Uri.parse("https://www.google.com/search").buildUpon()
          .appendQueryParameter("tbm", "isch")
          .appendQueryParameter("q", "nude photos of girls 18-80")
          .build()
         runCatching {
          startActivity(Intent(Intent.ACTION_VIEW, search).addCategory(Intent.CATEGORY_BROWSABLE))
         }.onFailure { status = "Could not open web image search: ${it.message}" }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Search Nude Women Images (18+)") }

       }
      }
      if (showAdultConfirm) AlertDialog(onDismissRequest = { showAdultConfirm = false }, title = { Text("18+ private editor") }, text = { Text("Only continue if you are 18 or older and have permission to use the images. Imported photos remain on this device unless you choose an online AI server. This is an age confirmation, not identity verification.") }, confirmButton = { TextButton(onClick = { adultConfirmed = true; showAdultConfirm = false; tab = 2; fictionalAdultMode = false; result = null }) { Text("I am 18 or older") } }, dismissButton = { TextButton(onClick = { showAdultConfirm = false }) { Text("Cancel") } })
      if (tab != -1) TextButton(onClick = { showConnection = !showConnection }) { Text(if (showConnection) "Hide connections ↑" else "Connections & settings ↓") }
      if (showConnection) {
      Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Image engine (Nightmare optional)", style = MaterialTheme.typography.titleMedium)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
       Text("Use Nightmare for images (optional)")
       Switch(checked = nightmareMode, enabled = !busy, onCheckedChange = { nightmareMode = it; prefs.edit().putBoolean("nightmare", it).apply() })
      }
      Text(if (nightmareMode) "Nightmare uses its API on port 8820. Turn it off to use built-in Qwen instead." else "Nightmare is OFF. Built-in Qwen is the default image engine; no separate app or image server required.")
      if (nightmareMode) OutlinedButton(enabled = !busy, onClick = { openNightmare() },
       modifier = Modifier.fillMaxWidth()) { Text("Open Nightmare") }
      if (nightmareMode) OutlinedButton(enabled = !busy, onClick = { checkNightmareConnection() },
       modifier = Modifier.fillMaxWidth()) { Text("Check Nightmare connection") }
      if (nightmareMode) TextButton(onClick = { showAdvancedConnection = !showAdvancedConnection }) { Text(if (showAdvancedConnection) "Hide advanced options ↑" else "Advanced options ↓") }
      if (nightmareMode && showAdvancedConnection) {
      OutlinedTextField(value = nightmareToken, onValueChange = {
       nightmareToken = it.trim()
       prefs.edit().putString("nightmare_token", nightmareToken).apply()
      }, label = { Text("Nightmare API token (kept on this phone)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      Text("Image generation uses the selected saved Nightmare workflow (RealVis by default). Auto Edit uses the appropriate Nightmare editing workflow. A compatible installed image model determines the details it can produce.")
      OutlinedTextField(value = nightmareImageFlow, onValueChange = {
        nightmareImageFlow = it.trim()
        prefs.edit().putString("nightmare_image_flow", nightmareImageFlow).apply()
       }, label = { Text("Nightmare image-generation workflow") },
        supportingText = { Text("Default: saved:FreedomAI RealVis. For a different installed image generator, use saved:Your Workflow Name or current.") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
       OutlinedTextField(value = nightmareEditFlow, onValueChange = { nightmareEditFlow = it.trim(); prefs.edit().putString("nightmare_edit_flow", nightmareEditFlow).apply() }, label = { Text("Nightmare edit workflow") }, supportingText = { Text("Recommended: auto. Others: inpaint, flux_edit, img2img (restyle only), current, or saved:Your Flow Name.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      }
      HorizontalDivider()
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
       Text("Local Dream (no Nightmare needed)")
       Switch(checked = localMode, enabled = !busy, onCheckedChange = { localMode = it; prefs.edit().putBoolean("local", it).apply() })
      }
      if (localMode) {
       Text("1. Open Local Dream and load CyberRealistic v10.\n2. Keep its model screen open, then switch back here.\n3. Check connection, then generate.")
       OutlinedButton(enabled = !busy, onClick = {
        val intent = packageManager.getLaunchIntentForPackage("io.github.xororz.localdream")
        if (intent != null) startActivity(intent) else Toast.makeText(this@MainActivity, "Open Local Dream from your phone's app list", Toast.LENGTH_LONG).show()
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
      if (!nightmareMode && !localMode && nativeQwen && (tab == -1 || tab == 0 || tab == 1 || tab == 2)) {
       if (qwenReady) {
        Text("✓ Built-in Qwen image AI ready — no Nightmare needed.")
       } else {
        Text("Install the free on-device Qwen model once (~11 GB) to generate or edit pictures without Nightmare.")
        if (qwenDownloading) {
         LinearProgressIndicator(progress = { qwenDownloadPercent / 100f }, modifier = Modifier.fillMaxWidth())
         Text("Installing: " + qwenDownloadPercent + "% — " + qwenDownloadText)
         OutlinedButton(onClick = { NativeQwenEditor.cancelDownload() }) { Text("Cancel model download") }
        } else {
         Button(onClick = { installBuiltInQwenModels() }, enabled = !busy,
          modifier = Modifier.fillMaxWidth()) { Text("Install AI Image Model (one time)") }
        }
       }
      }
      if (tab == 1 || tab == 2) {
       Text("AI edit speed")
       Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("FAST", "BALANCED", "MAX QUALITY").forEach { speed ->
         OutlinedButton(enabled = !busy, onClick = {
          editSpeed = speed
          prefs.edit().putString("edit_speed", speed).apply()
         }) { Text(if (editSpeed == speed) "✓ $speed" else speed) }
        }
       }
       Text(if (!nightmareMode && !localMode && nativeQwen) "Built-in Qwen editing: FAST 320px, BALANCED 384px, MAX QUALITY 512px; larger sizes require more RAM." else "Nightmare editing uses its installed image model.")
      }
      if (tab == 0 || tab == 2) {
       Text("Generation speed")
       Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("FAST", "BALANCED", "MAX QUALITY").forEach { speed ->
         OutlinedButton(onClick = { generationSpeed = speed; prefs.edit().putString("generation_speed", speed).apply() }) { Text(if (generationSpeed == speed) "✓ $speed" else speed) }
        }
       }
       Text(if (!nightmareMode && !localMode && nativeQwen) "Built-in Qwen Turbo: FAST 320px • BALANCED 384px • MAX QUALITY 512px (six diffusion steps)." else "External engine: FAST 768px/14 steps • BALANCED 22 steps • MAX QUALITY 35 steps.")
       if (!nightmareMode && !localMode && nativeQwen) {
        Text("Image shape")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
         listOf("Square", "Portrait", "Landscape").forEach { shape ->
          OutlinedButton(onClick = {
           imageAspect = shape
           prefs.edit().putString("image_aspect", shape).apply()
          }, enabled = !busy, modifier = Modifier.weight(1f)) {
           Text(if (imageAspect == shape) "✓ $shape" else shape)
          }
         }
        }
       } else {
        Text("Image resolution")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
         listOf(512, 768, 1024).forEach { size ->
          OutlinedButton(onClick = { resolution = size; prefs.edit().putInt("resolution", size).apply() }) {
           Text(if (resolution == size) "✓ $size" else "$size")
          }
         }
        }
       }
      }
      if (busy) {
        Button(onClick = { cancelGeneration() }, colors = ButtonDefaults.buttonColors(containerColor = UiColor(0xFFB3261E), contentColor = UiColor.White), modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        if (!nativeProgress && generationPercent >= 80) {
         LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
         Text("Waiting for AI result — progress unavailable. You can cancel if it takes too long.")
        } else {
         LinearProgressIndicator(progress = { generationPercent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        Text("Elapsed: ${imageElapsedSeconds}s")
        Text(if (nativeProgress) "Qwen on-device generation/edit: $generationPercent% (actual model progress)" else if (generationPercent >= 80) "AI is still processing — 80% is an estimate, not a rendering checkpoint." else "AI is processing your image… $generationPercent% estimated")
        if (!nativeProgress) {
         Text(if (renderingPercent >= 95 || generationPercent >= 80) "Rendering: waiting for AI result (actual progress unavailable)" else "Rendering: $renderingPercent% estimated")
         if (renderingPercent >= 95 || generationPercent >= 80) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { renderingPercent / 100f }, modifier = Modifier.fillMaxWidth())
        }
       }
      if (status.isNotEmpty() && !status.startsWith("Nightmare v")) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Text(status, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
      if (nightmareMode && nightmareConnectionFailed) {
       OutlinedButton(enabled = !busy, onClick = { openNightmare() },
        modifier = Modifier.fillMaxWidth()) { Text("Open Nightmare") }
       OutlinedButton(enabled = !busy, onClick = { checkNightmareConnection() },
        modifier = Modifier.fillMaxWidth()) { Text("Check Nightmare connection") }
      }
      fun requestImage(input: Bitmap?, promptOverride: String? = null) {
        val faceDetailRun = input != null && promptOverride == FaceDetailEnhancement.REFINE_PROMPT
       if ((promptOverride ?: prompt).isBlank() && !(tab == 2 && fictionalAdultMode && input == null)) { status = "Describe the image first"; return }
       // FAST: a conservative, color-only bikini recolor can use source pixels
       // without AI inference. If fabric recognition fails, use the selected editor.
       val useOnlineEdit = input != null && onlineQwen && !nightmareMode
       val useNativeEdit = input != null && nativeQwen && !useOnlineEdit && !nightmareMode && !localMode
       val useBuiltInGenerate = input == null && nativeQwen && !nightmareMode && !localMode
       val autoColorRequest = if (input != null && editSpeed == "FAST" &&
         !maskMode && !precisionEdit) AutomaticClothingEdit.parse(promptOverride ?: prompt) else null
       if (useOnlineEdit && (onlineQwenUrl.isBlank() || onlineQwenModel.isBlank() || onlineQwenKey.isBlank())) {
         status = "Online Qwen needs an HTTPS image-edit endpoint, model name, and API key. Configure these in Edit settings; no edit was sent to Nightmare."
         return
        }
        if (useNativeEdit || useBuiltInGenerate) {
         qwenReady = NativeQwenEditor.ready(this@MainActivity)
         if (!qwenReady) {
          status = "Built-in Qwen model missing. Tap Install AI Image Model (one-time, approximately 11 GB)."
          return
         }
        }
       val address = server.trim().trimEnd('/')
       if (autoColorRequest == null && !useNativeEdit && !useBuiltInGenerate && !useOnlineEdit && !nightmareMode && !localMode && runCatching { URL(address).let { it.protocol == "https" && it.host.isNotBlank() && it.userInfo == null && it.query == null && it.ref == null } }.getOrDefault(false).not()) {
        status = "Enter a valid HTTPS server URL"; return
       }
       prefs.edit().putString("url", address).apply()
       val useLocal = localMode
       val useNightmare = nightmareMode && !useOnlineEdit && !useNativeEdit
       val tokenForRun = nightmareToken
       // Respect Auto: garment changes need auto-masked inpainting; forcing flux_edit
        // here incorrectly bypassed the selected editing workflow on every photo edit.
        val editFlowForRun = if (faceDetailRun && nightmareMode) "flux_edit" else nightmareEditFlow
       val requestedPrompt = if (tab == 2 && fictionalAdultMode && input == null) {
        val age = characterAge.toIntOrNull()
        if (age == null || age < 18 || age > 100) { status = "Enter an adult age from 18 to 100"; return }
        FictionalAdultPrompt.build(age, characterSubject, appearance, bodyDescription, pose, setting, photoStyle, prompt, anatomyChoice, sceneCount)
       } else (promptOverride ?: prompt)
       val requestedStrength = if (faceDetailRun) 0.45f else if (input != null) editStrength.coerceIn(0.10f, 0.80f) else editStrength
       val requestedSpeed = if (faceDetailRun) "MAX QUALITY" else if (input != null) editSpeed else generationSpeed
       val requestedHighQuality = requestedSpeed == "MAX QUALITY"
       val requestedResolution = if (input == null && requestedSpeed == "FAST") 768 else resolution
       val editPrompt = if (input != null) {
         if (faceDetailRun) requestedPrompt else "$requestedPrompt. Keep the same person, face, pose, camera and background."
        } else if (cartoonMode && tab == 0) CartoonImagePrompt.build(requestedPrompt, cartoonStyle)
         else FaceDetailEnhancement.generationPrompt(requestedPrompt)
       if (input != null && !faceDetailRun && precisionEdit && (!maskMode || strokes.isEmpty())) {
        status = "Precision Edit requires a painted area. Paint the region to change or disable Precision Edit."
        return
       }
       val mask = if (input != null && !faceDetailRun && maskMode && strokes.isNotEmpty()) makeMask(input.width, input.height, strokes.toList(), brush) else null
              result = null // Do not leave a previous variation visible as a failed new edit.
              busy = true; generationPercent = 0; renderingPercent = 0; nativeProgress = useNativeEdit || useBuiltInGenerate
              nightmareConnectionFailed = false
              status = if (useOnlineEdit) "Editing with online Qwen…" else if (useNativeEdit) "Editing locally with Qwen Turbo…" else if (input != null) "Editing photo…" else "Creating image…" 
        // Nightmare does not currently expose generation progress through this client.
        // Show an explicitly estimated percentage, capped below 100 until an image arrives.
        val progressJob = if (useNativeEdit || useBuiltInGenerate) null else scope.launch {
         imageElapsedSeconds = 0
         while (isActive && busy) {
          delay(1000)
          if (busy) {
           imageElapsedSeconds++
           generationPercent = (generationPercent + 1).coerceAtMost(80)
            // Remote rendering has no actual progress callback. Display a
            // clearly labeled estimate that never reaches 100% before completion.
            if (!nativeProgress) renderingPercent = (renderingPercent + 1).coerceAtMost(95)
          }
         }
        }
       generationJob = scope.launch {
        val editEngine = if (useBuiltInGenerate) "Built-in Qwen image generator" else if (useOnlineEdit) "Online Qwen" else if (useNativeEdit) "On-device Qwen editor" else if (useNightmare) "Nightmare" else if (useLocal) "Local Dream" else "Remote image server"
        var usedFastLocalEdit = false
        val editStartedAt = android.os.SystemClock.elapsedRealtime()
        var inferenceFinishedAt = editStartedAt
        try {
         status = "$editEngine: processing image (elapsed time shown below)"
         val completedImage = withContext(Dispatchers.IO) {
          val fastLocalColor = if (input != null && autoColorRequest != null)
           AutomaticClothingEdit.recolor(input, autoColorRequest) else null
          if (fastLocalColor != null) {
           usedFastLocalEdit = true
           fastLocalColor
          } else if (useBuiltInGenerate) {
            val generated = NativeQwenEditor.generate(
             this@MainActivity, editPrompt, requestedSpeed, imageAspect
            ) { percentage -> runOnUiThread {
             if (generationJob?.isActive == true) {
              generationPercent = percentage
              status = "Qwen Turbo image generation: " + percentage + "% complete"
             }
            } }
            check(!isNearlyWhiteImage(generated)) { "Qwen returned a blank image." }
            generated
           } else if (useOnlineEdit && input != null) {
            val generated = OnlineQwenEditor.edit(input, editPrompt, onlineQwenUrl, onlineQwenModel, onlineQwenKey)
            check(!isNearlyWhiteImage(generated)) { "Online editor returned a blank image." }
            if (faceDetailRun) compositeRefinedFaces(input, generated) ?: error("No faces could be located for face-only refinement.") else if (mask != null) compositeMaskedEdit(input, generated, mask) else generated
           } else if (useNativeEdit && input != null) {
           val generated = NativeQwenEditor.edit(
            this@MainActivity, input, editPrompt,
            com.scsonic.qwenimage21.QwenImage21.Size.Tier.valueOf(if (faceDetailRun) "STANDARD" else qwenTier)
           ) { percentage -> runOnUiThread {
            if (generationJob?.isActive == true) {
             generationPercent = percentage
             status = "Qwen Turbo: $percentage% completed"
            }
           } }
           check(!isNearlyWhiteImage(generated)) { "Qwen returned a blank image." }
           if (faceDetailRun) compositeRefinedFaces(input, generated) ?: error("No faces could be located for face-only refinement.") else if (mask != null) compositeMaskedEdit(input, generated, mask) else generated
          } else if (useNightmare) {
           val generated = try {
            NightmareClient.generate(editPrompt, tokenForRun, input, requestedStrength, requestedHighQuality, requestedResolution, editFlowForRun, requestedSpeed, nightmareImageFlow)
           } catch (nightmareError: Exception) {
            val maskFailure = nightmareError.message.orEmpty()
             val maskUnavailable = input != null &&
              (maskFailure.contains("auto-mask is unavailable", ignoreCase = true) ||
               maskFailure.contains("cannot automatically mask", ignoreCase = true) ||
               maskFailure.contains("no working instruction-edit fallback", ignoreCase = true))
            if (maskUnavailable && qwenReady) {
             runOnUiThread { status = "Nightmare mask unavailable. Trying installed Qwen editor…" }
             NativeQwenEditor.edit(this@MainActivity, input!!, editPrompt,
              com.scsonic.qwenimage21.QwenImage21.Size.Tier.valueOf(if (faceDetailRun) "STANDARD" else qwenTier)
             ) { percentage -> runOnUiThread { generationPercent = percentage } }
            } else if (maskUnavailable) {
             // Do not launch a second expensive Nightmare run after a missing-mask failure.
             // That fallback cannot guarantee a localized clothing edit and can appear frozen at 80%.
             error("Nightmare cannot automatically mask the requested clothing. Original photo preserved. Install/enable Clothes Auto Mask in Nightmare, use Paint area to change, or install the Qwen editor in FreedomAI.")
            } else throw nightmareError
           }
           check(!isNearlyWhiteImage(generated)) { "The AI returned a blank white image. Try a different flow or restart the model." }
           if (input != null && isSameImage(input, generated)) error("Nightmare returned the original image unchanged; verify the img2img flow and its image input.")
           if (mask != null && input != null) compositeMaskedEdit(input, generated, mask) else if (faceDetailRun && input != null) {
             compositeRefinedFaces(input, generated) ?: error("No faces could be automatically located for face-only refinement. Original photo preserved.")
            } else if (input != null) preserveDetectedFace(input, preserveFraming(input, generated)) else generated
          } else if (useLocal) {
           val encoded = input?.let { bitmap ->
            ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP) }
           }
           if (mask != null) error("Painted-area edits require an HTTPS AUTOMATIC1111 server")
           val frame = LocalDreamClient.generate(editPrompt, encoded, requestedStrength) { step, total ->
            runOnUiThread { if (generationJob?.isActive == true) status = "Creating image: step $step of $total" }
           }
           Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888).let { generated ->
             if (faceDetailRun && input != null) compositeRefinedFaces(input, generated) ?: error("No faces located for face-only refinement.") else generated
            }
          } else {
           val generated = generateImage(address, editPrompt, input, requestedStrength, mask, requestedHighQuality, cartoonMode && tab == 0 && input == null)
           if (faceDetailRun && input != null) compositeRefinedFaces(input, generated) ?: error("No faces located for face-only refinement.") else if (input != null && mask == null) preserveFraming(input, generated) else generated
          }
         }
         inferenceFinishedAt = android.os.SystemClock.elapsedRealtime()
         kotlinx.coroutines.currentCoroutineContext().ensureActive()
         stripeRepairUndo = null
         result = completedImage
         if (result != null && isNearlyWhiteImage(result!!)) {
          result = null
          status = "The AI returned a blank white image. Your original photo is safe. Try again with lower edit strength or restart the model."
         } else {
          generationPercent = 100
           resultGeneratedHere = if (input == null) true else originalGeneratedHere
           status = if (usedFastLocalEdit) "Fast local recolor complete. Only detected fabric pixels were changed; no AI render was needed." else if (useBuiltInGenerate) "Image generated with built-in Qwen Turbo. No Nightmare needed. Tap Save Image." else if (useOnlineEdit) "Online Qwen edit complete. Check the result against the original." else if (useNativeEdit) "Qwen Turbo edit complete. Compare the face, pose and background with the original." else if (input != null && useNightmare) "Nightmare local edit complete (requested flow: ${EditRequestRouting.chooseFlow(editFlowForRun, requestedPrompt)}). Actual model must be confirmed in Nightmare run details." else if (input != null) "AI returned an edit — compare it with your requested change below" else "Image ready"
         }
         val totalSeconds = (android.os.SystemClock.elapsedRealtime() - editStartedAt) / 1000
         val engineSeconds = (inferenceFinishedAt - editStartedAt) / 1000
         status += " | Engine: ${if (usedFastLocalEdit) "On-device color edit" else editEngine} | Processing: ${engineSeconds}s | Total: ${totalSeconds}s"
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
         val nightmareHelp = if (useNightmare) NightmareConnectionDiagnostics.helpFor(e) else null
         nightmareConnectionFailed = nightmareHelp != null
         status = when {
          nightmareHelp != null -> nightmareHelp
          useBuiltInGenerate -> "Built-in Qwen image generation failed: " + (e.message ?: "Model failure") + ". No Nightmare connection is required."
           useOnlineEdit -> "Online Qwen edit failed: ${e.message ?: "Check your endpoint, model and token"}. Original image unchanged."
           useNativeEdit -> "Native Qwen edit failed: ${e.message ?: "Unknown model error"}. Your original image is unchanged."
          useNightmare -> "Nightmare ${if (input != null) "edit" else "generation"} failed (flow: ${if (input != null) editFlowForRun else nightmareImageFlow}): ${e.message ?: "connection failed"}. Open the matching flow in Nightmare, check its model and image input."
          useLocal -> "Local Dream: ${e.message ?: "connection failed"}. Keep the model loaded; try split screen if disconnected."
          else -> "Could not create image: ${e.message ?: "connection failed"}"
         }
        } finally { progressJob?.cancel(); if (generationJob == kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) { busy = false; generationJob = null } }
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
          Text("Fictional adult subject")
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
           listOf(listOf("Man", "Woman"), listOf("Both People", "Person")).forEach { options ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
             options.forEach { choice ->
              OutlinedButton(onClick = {
               characterSubject = choice
               if (choice == "Both People" && sceneCount == "Single") {
                sceneCount = "Couple"
                prefs.edit().putString("fictional_scene_count", sceneCount).apply()
               }
               prefs.edit().putString("fictional_adult_subject", choice).apply()
              }, enabled = !busy, modifier = Modifier.weight(1f)) {
               Text(if (characterSubject == choice) "✓ $choice" else choice)
              }
             }
            }
           }
          }
          Text("Both People places an adult man and woman in the same image.")
          Text("Anatomy details")
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
           listOf(listOf("Default", "Male anatomy"), listOf("Female anatomy", "Both anatomies")).forEach { options ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
             options.forEach { choice ->
              OutlinedButton(onClick = {
               anatomyChoice = choice
               prefs.edit().putString("fictional_anatomy", choice).apply()
              }, enabled = !busy, modifier = Modifier.weight(1f)) {
               Text(if (anatomyChoice == choice) "✓ $choice" else choice)
              }
             }
            }
           }
          }
          Text("Both anatomies requests both characteristics on one fictional adult. Default follows the selected subject.")
          Text("People in image")
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
           listOf("Single", "Couple", "Group").forEach { choice ->
            OutlinedButton(onClick = {
             sceneCount = choice
             prefs.edit().putString("fictional_scene_count", sceneCount).apply()
            }, enabled = !busy && !(characterSubject == "Both People" && choice == "Single"),
             modifier = Modifier.weight(1f)) { Text(if (sceneCount == choice) "✓ $choice" else choice) }
           }
          }
          Text("Results depend on the selected image generator and installed model. These controls guide the prompt but do not guarantee requested details.")
          OutlinedTextField(value = characterAge, onValueChange = { characterAge = it.filter(Char::isDigit).take(3) }, label = { Text("Fictional character age (18+)") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = appearance, onValueChange = { appearance = it }, label = { Text("Appearance: hair, face, eyes") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = bodyDescription, onValueChange = { bodyDescription = it }, label = { Text("Body proportions and physique") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = pose, onValueChange = { pose = it }, label = { Text("Pose") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = setting, onValueChange = { setting = it }, label = { Text("Background / location") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = photoStyle, onValueChange = { photoStyle = it }, label = { Text("Photography and lighting style") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Additional details (optional)") }, modifier = Modifier.fillMaxWidth().height(110.dp))
       OutlinedButton(onClick = { listenForPrompt() }, enabled = !busy) { Text("🎤 Speak prompt") }
          Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("High quality", modifier = Modifier.weight(1f)); Switch(checked = highQuality, onCheckedChange = { highQuality = it }) }
          Text("Offline generation depends on the installed Qwen model. No Nightmare is needed once the model is installed.")
          Button(onClick = { requestImage(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Generate Fictional Adult Image") }
         }
        }
       }
      }
      LaunchedEffect(pendingAssistantImage) {
       val request = pendingAssistantImage
       if (request != null) {
        pendingAssistantImage = null
        requestImage(null, request)
       }
      }
      if (tab == 0) {
        if (cartoonMode) {
         Text("Cartoon image creator", style = MaterialTheme.typography.titleMedium)
         Text("You choose the subject and style. Try 2D, anime, comic book, 3D, watercolor, or describe any other cartoon style.")
         OutlinedTextField(value = cartoonStyle, onValueChange = { cartoonStyle = it },
          label = { Text("Cartoon style — type anything") },
          placeholder = { Text("e.g. 2D, comic, anime, 3D, hand-drawn") },
          modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(value = prompt, onValueChange = { prompt = it },
         label = { Text(if (cartoonMode) "Describe your cartoon" else "Describe your image") },
         placeholder = { Text(if (cartoonMode) "A cheerful dragon riding a bicycle through a colorful city…" else "A peaceful beach at sunset, warm light, gentle waves…") },
         modifier = Modifier.fillMaxWidth().height(170.dp))
       OutlinedButton(onClick = { listenForPrompt() }, enabled = !busy) { Text("🎤 Speak prompt") }
       Button(onClick = { requestImage(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Creating…" else if (cartoonMode) "Create Cartoon" else "Create Image") }
       if (cartoonMode) Text("Uses your selected generator. For best results, select a compatible cartoon model in that generator.")

      } else if (!videoMode && (tab == 1 || (tab == 2 && adultConfirmed && !fictionalAdultMode))) {
       if (tab == 2) Text("Private editing: adjust brightness and saturation, then save to app-private storage. AI edits may send your photo to the selected model/server. Only use images you have permission to edit.")
       var showAdvancedEditing by remember { mutableStateOf(false) }
       Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
         Text("AI editing", style = MaterialTheme.typography.titleMedium)
         Text(if (nightmareMode) "Nightmare installed Qwen (no extra download)" else if (onlineQwen) "Online Qwen" else if (nativeQwen) "Offline Qwen" else "Automatic", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { showAdvancedEditing = !showAdvancedEditing }) {
         Text(if (showAdvancedEditing) "Hide settings" else "Advanced")
        }
       }
       if (showAdvancedEditing) {
       Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
         Text("Editing options", style = MaterialTheme.typography.titleMedium)
          Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
           Text("Use online Qwen for AI Edit", modifier = Modifier.weight(1f))
           Switch(checked = onlineQwen, enabled = !busy, onCheckedChange = {
            onlineQwen = it; prefs.edit().putBoolean("online_qwen", it).apply()
           })
          }
          if (onlineQwen) {
           Text("Online editing uploads the selected photo to your chosen provider. Alibaba Model Studio supports Qwen 2.1 Turbo. Free introductory quotas, when available, are limited; usage may be billed.")
           OutlinedButton(onClick = {
            onlineQwenModel = "qwen-image-2.1-turbo"
            prefs.edit().putString("online_qwen_model", onlineQwenModel).apply()
           }, enabled = !busy) { Text("Use Alibaba Qwen 2.1 Turbo") }
           Text("Alibaba setup: paste your Model Studio HTTPS multimodal-generation endpoint (including your Workspace ID) and API key. Other providers can use compatible multipart /images/edits endpoints.")
           OutlinedTextField(value = onlineQwenUrl, onValueChange = {
            onlineQwenUrl = it; prefs.edit().putString("online_qwen_url", it.trim()).apply()
           }, label = { Text("Alibaba or other image-edit API endpoint (HTTPS)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
           OutlinedTextField(value = onlineQwenModel, onValueChange = {
            onlineQwenModel = it; prefs.edit().putString("online_qwen_model", it.trim()).apply()
           }, label = { Text("Online model (default: qwen-image-2.1-turbo)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
           OutlinedTextField(value = onlineQwenKey, onValueChange = {
            onlineQwenKey = it; prefs.edit().putString("online_qwen_key", it).apply()
           }, label = { Text("Provider API key (required)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
          }
          Text("Built-in Qwen image AI (offline)")
         Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
          Text("Use built-in Qwen image AI", modifier = Modifier.weight(1f))
          Switch(checked = nativeQwen, enabled = !busy, onCheckedChange = {
           nativeQwen = it; prefs.edit().putBoolean("native_qwen", it).apply()
          })
         }
         Text(if (qwenReady) "Built-in Qwen image model installed ✓" else "Built-in Qwen model files missing. One-time download ~11 GB into FreedomAI storage; Nightmare files cannot be used across app sandboxes.")
         Text("Built-in Qwen creates and edits images on the phone; Nightmare and online services are optional.")
         Text("Qwen editing quality (Fast preserves identity better in upstream testing):")
         Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf("TINY" to "320", "FAST" to "384", "STANDARD" to "512").forEach { (tier, label) ->
           OutlinedButton(enabled = !busy, onClick = {
            qwenTier = tier; prefs.edit().putString("qwen_tier", tier).apply()
           }) { Text(if (qwenTier == tier) "✓ $label" else label) }
          }
         }
         if (qwenDownloading) {
          LinearProgressIndicator(progress = { qwenDownloadPercent / 100f }, modifier = Modifier.fillMaxWidth())
          Text("Downloading: $qwenDownloadPercent% · $qwenDownloadText")
          OutlinedButton(onClick = { NativeQwenEditor.cancelDownload() }) { Text("Cancel download") }
         } else if (!qwenReady) {
          Button(enabled = !busy, onClick = { installBuiltInQwenModels() },
           modifier = Modifier.fillMaxWidth()) { Text("Install Built-In Qwen Image Model") }
         }
        }
       }
       }
       Button(enabled = !busy, onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Choose Photo") }
       edited?.let { bitmap ->
        BoxWithConstraints(Modifier.fillMaxWidth()) {
         val displayHeight = maxWidth * bitmap.height.toFloat() / bitmap.width.toFloat()
         Image(bitmap.asImageBitmap(), contentDescription = "Photo preview, tap to enlarge", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxWidth().height(displayHeight).clickable { expandedPhoto = bitmap })
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
       OutlinedButton(onClick = { listenForPrompt() }, enabled = !busy) { Text("🎤 Speak prompt") }
        var showEditHelp by remember { mutableStateOf(false) }
        TextButton(onClick = { showEditHelp = !showEditHelp }) { Text(if (showEditHelp) "ⓘ Hide editing help" else "ⓘ Help") }
        if (showEditHelp) {
         Text("No-paint color edit: try 'red bikini' or 'make the bikini top blue'. For detectable vivid fabric, the app recolors original pixels without regenerating the person. Other garments and shape edits still depend on your AI model.")
         Text(if (originalGeneratedHere) "Image source: generated in FreedomAI (this session)" else "Image source: imported or unverified")
        }
        Button(onClick = { prompt = "remove clothing naked nude show tits show pussy realistic"; status = "Prompt entered. Tap AI Edit when ready." }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Nude") }
        Button(onClick = { status = "Starting AI photo edit…"; requestImage(result ?: bitmap) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("AI Edit") }
        Text("Quick clothing edits", style = MaterialTheme.typography.titleMedium)
        listOf(
         "nude naked",
         "remove clothing",
         "show boobs, show pussy"
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
        Button(onClick = { if (tab == 2) this@MainActivity.savePrivate(bitmap) else this@MainActivity.save(bitmap) }, modifier = Modifier.fillMaxWidth()) { Text(if (tab == 2) "Save Privately" else "Save Photo") }
        OutlinedButton(onClick = { brightness = 0f; saturation = 1f }) { Text("Reset") }
       } ?: Text("Pick a photo to edit its brightness and color saturation.")
      }
      if (videoMode) {
       Text("Create Video", style = MaterialTheme.typography.titleLarge)
       Button(enabled = !busy, onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Choose Photo for Video") }
       if (result == null && original == null) Text("Choose a photo to start video generation.")
      }
      (if (videoMode) result ?: original else result)?.let { image ->
       Text(if (videoMode) "Video source image" else "Your creation", style = MaterialTheme.typography.titleLarge)
       Image(image.asImageBitmap(), contentDescription = "Generated image, tap to enlarge", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clickable { expandedPhoto = image })
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
       if (!videoMode) OutlinedButton(onClick = { original = image; originalGeneratedHere = resultGeneratedHere; result = null; videoMode = false; tab = 1; maskMode = false; strokes.clear(); status = "Image loaded for editing. Choose an edit below."; }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Edit Image") }
       if (!videoMode) OutlinedButton(onClick = {
        status = "Refining faces and eyes with high-quality AI editing…"
        requestImage(image, FaceDetailEnhancement.REFINE_PROMPT)
       }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Improve Faces & Eyes") }
       if (!videoMode) Text("Face refinement uses a higher-quality edit and blends detected faces back into the original. It may take longer than Fast Edit.")
       if (!videoMode) OutlinedButton(onClick = {
        if (image.width >= 4096 || image.height >= 4096) {
         status = "Image is already 4K or larger."
        } else {
         val source = image
         busy = true
         status = "Upscaling image to 4K…"
         generationJob = scope.launch {
          try {
           val upscaled = withContext(Dispatchers.IO) {
            AiSuperResolution.upscale(this@MainActivity, source) { percent ->
             scope.launch(Dispatchers.Main) { status = "Upscaling image to 4K… $percent%" }
            }
           }
           kotlinx.coroutines.currentCoroutineContext().ensureActive()
           result = upscaled
           status = "AI-enhanced 4K image ready. Tap Save Image."
          } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (e: OutOfMemoryError) { status = "Not enough memory for 4K upscale. Original image is safe." }
          catch (e: Exception) { status = "4K upscale failed: ${e.message}" }
          finally { if (generationJob == kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) { busy = false; generationJob = null } }
         }
        }
       }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("4K Upscale") }
       if (!videoMode && original != null && result != null) {
        OutlinedButton(onClick = { showStripeFix = !showStripeFix }, enabled = !busy,
         modifier = Modifier.fillMaxWidth()) { Text(if (showStripeFix) "Hide Line Repair" else "Fix Horizontal Line") }
        if (showStripeFix) {
         Text("Fast repair from your original photo; no new AI render or painting required.")
         Text("Position: ${(stripePosition * 100f).roundToInt()}% down from the top")
         Slider(value = stripePosition, onValueChange = { stripePosition = it },
          valueRange = 0.02f..0.98f, enabled = !busy)
         OutlinedButton(onClick = {
          val source = original
          if (source != null) {
           try {
            val fixed = HorizontalStripeRepair.restoreFromOriginal(image, source, stripePosition)
            stripeRepairUndo = image
            result = fixed
            status = "Horizontal band replaced using the source photo. Check the result before saving; this preserves the original details within the band."
           } catch (error: Exception) { status = "Line repair failed: ${error.message}. Original image is safe." }
          }
         }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Repair Selected Line") }
        }
        if (stripeRepairUndo != null) {
         OutlinedButton(onClick = {
          result = stripeRepairUndo
          stripeRepairUndo = null
          status = "Line repair undone."
         }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Undo Line Repair") }
        }
       }
       if (!videoMode) Button(onClick = { if (tab == 2) this@MainActivity.savePrivate(image) else this@MainActivity.save(image) }) { Text(if (tab == 2) "Save Privately" else "Save Image") }
        if (!videoMode) OutlinedButton(onClick = {
         original = image
         result = null
         videoMode = true
         tab = 1
         status = "Photo selected for video. Describe movement, then tap Generate AI Video."
        }, modifier = Modifier.fillMaxWidth()) { Text("Create Video From This Photo") }
        if (videoMode) {
        Text("Create Video", style = MaterialTheme.typography.titleLarge)
        if (lastVideo == null) {
        Text("No MP4 saved yet. Describe movement and tap Generate AI Video. Corrupted video is rejected instead of saved.")
       } else {
        Button(enabled = !busy, onClick = { showVideoPlayer = true },
         modifier = Modifier.fillMaxWidth()) { Text("▶ Play Last Saved Video") }
        Text("Video saved in Movies/FreedomAI.")
       }
       OutlinedButton(enabled = !busy && lastVideo != null, onClick = {
        lastVideo?.let { saved ->
         exportVideoSource = saved
         exportVideoLauncher.launch("FreedomAI_Video_${System.currentTimeMillis()}.mp4")
        }
       }, modifier = Modifier.fillMaxWidth()) { Text("Save Video") }
       Text("Videos save automatically. Save Video lets you choose another folder.")
       Text(if (useMobileI2V) "Balanced local video • 5-second default • 16 fps MP4 encoding." else
        "Nightmare video • 5-second default. Resolution and frame rate depend on your Nightmare video workflow.")
        Text("Choose a photo and describe movement.")

       Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Use built-in MobileI2V (experimental)", modifier = Modifier.weight(1f))
        Switch(checked = useMobileI2V, enabled = !busy, onCheckedChange = {
         useMobileI2V = it
         prefs.edit().putBoolean("video_mobilei2v_experimental", it).apply()
        })
       }
       Text(if (useMobileI2V) "Offline video • Experimental" else "Video powered by Nightmare")
       if (!useMobileI2V && status.contains("corrupted video frames", ignoreCase = true)) {
        Text("Nightmare returned unusable video frames. In Nightmare turn ON Settings → Performance → Memory → Video: stream the model. FreedomAI cannot change Nightmare settings through its API.")
        OutlinedButton(onClick = {
         val app = packageManager.getLaunchIntentForPackage("com.abrah.nightmare")
         if (app != null) startActivity(app)
         else status = "Open Nightmare from your apps, then Settings → Performance → Memory → Video: stream the model → ON."
        }, modifier = Modifier.fillMaxWidth()) { Text("Open Nightmare to Fix Video") }
         OutlinedButton(enabled = !busy, onClick = {
          duration = 2
          customSeconds = ""
          prefs.edit().putInt("video_duration_seconds", 2).apply()
          status = "2-second test selected. Enable video model streaming in Nightmare first, then tap Generate AI Video."
         }, modifier = Modifier.fillMaxWidth()) { Text("Set 2-Second Test") }
       }
       if (useMobileI2V) {
        OutlinedButton(enabled = !busy, onClick = {
         useMobileI2V = false
         prefs.edit().putBoolean("video_mobilei2v_experimental", false).apply()
         status = "Nightmare selected for prompt-controlled motion. A compatible image-to-video workflow and valid token are required."
        }, modifier = Modifier.fillMaxWidth()) { Text("Use prompt-controlled video (Nightmare)") }
       }
       if (useMobileI2V) {
        var showMobileI2VInfo by remember { mutableStateOf(false) }
        TextButton(onClick = { showMobileI2VInfo = !showMobileI2VInfo }) {
         Text(if (showMobileI2VInfo) "Hide video details ↑" else "Video details & requirements ↓")
        }
        if (showMobileI2VInfo) {
         Text("MobileI2V uses on-device ONNX files. It is experimental, does not follow text-directed movement instructions and still needs actual phone-based video quality verification.")
        }
       }
       if (!useMobileI2V) OutlinedTextField(value = nightmareVideoFlow, onValueChange = {
        nightmareVideoFlow = it.trim()
        prefs.edit().putString("nightmare_video_flow", nightmareVideoFlow).apply()
       }, label = { Text("Video workflow (current or saved:Name)") }, modifier = Modifier.fillMaxWidth())
       OutlinedTextField(value = motionPrompt, onValueChange = { motionPrompt = it },
        label = { Text(if (useMobileI2V) "Motion prompt (unsupported offline)" else "Describe AI movement") },
        enabled = !useMobileI2V, modifier = Modifier.fillMaxWidth())
       if (useMobileI2V) Text("Offline MobileI2V is image-conditioned only: it cannot follow typed movement instructions. Switch to Nightmare with a compatible motion workflow for prompt-directed action. Offline video uses the source image without this prompt.")
       Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 2, 5, 10).forEach { secs ->
         OutlinedButton(onClick = { duration = secs; customSeconds = ""; prefs.edit().putInt("video_duration_seconds", secs).apply() }) {
          Text(if (duration == secs) "✓ ${secs}s" else "${secs}s")
         }
        }
       }
       Text("Selected video generator: ${if (useMobileI2V) "MobileI2V (offline)" else "Nightmare"}")
       if (lastVideoGenerator != "Not recorded") Text("Last successful video generator: $lastVideoGenerator")
       if (busy) {
        if (useMobileI2V) {
         Text("MobileI2V processing: ${generationPercent}% — ${status}")
         LinearProgressIndicator(progress = { generationPercent.coerceIn(0, 100) / 100f },
          modifier = Modifier.fillMaxWidth())
         Text("MobileI2V progress updates after completed model steps.")
        } else {
         Text("Nightmare: $status")
         LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
         Text("Elapsed: ${videoElapsedSeconds}s. Nightmare does not report a reliable live completion percentage.")
        }
        OutlinedButton(onClick = { cancelGeneration() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel video generation") }
       }
       Button(enabled = !busy, onClick = {
        if (!useMobileI2V && motionPrompt.isBlank()) status = "Describe the desired movement"
        else {
         busy = true
         generationPercent = 0
         videoElapsedSeconds = 0
         status = if (useMobileI2V) "Running offline MobileI2V models…" else "Connecting to Nightmare…"
         generationJob = scope.launch {
          try {
           val video = withContext(Dispatchers.IO) {
            // Clean only stale FreedomAI render artifacts, never user media or models.
            runOnUiThread { status = "Preparing video: clearing temporary memory…" }
            val staleCutoff = System.currentTimeMillis() - 60L * 60L * 1000L
            cacheDir.listFiles()?.forEach { file ->
             if (file.isFile && file.lastModified() < staleCutoff &&
                 (file.name.startsWith("mobilei2v_") || file.name.startsWith("freedomai_video_")) &&
                 file.extension.lowercase() in setOf("mp4", "tmp")) {
              runCatching { file.delete() }
             }
            }
            // Avoid expensive forced GC/finalization when Nightmare handles video inference.
            if (useMobileI2V) {
             System.gc()
             runOnUiThread { status = "Preparing MobileI2V ONNX models…" }
            } else {
             runOnUiThread { status = "Connecting to Nightmare…" }
            }
            if (useMobileI2V) {
             val models = java.io.File(filesDir, "models/video-v1")
             // Check verified release on every video attempt so previously installed
             // full-precision models can be upgraded to the smaller validated graph.
             runOnUiThread { status = "Checking compatible MobileI2V models…" }
             MobileI2VModelUpdater.install(models) { p ->
              runOnUiThread { generationPercent = p; status = "Checking MobileI2V models… $p%" }
             }
             runOnUiThread { generationPercent = 0; status = "Generating MobileI2V video…" }
             MobileI2VVideoEngine.generate(this@MainActivity, java.io.File(filesDir, "models/video-v1"), cacheDir, image, motionPrompt, duration) { p ->
              if (generationJob?.isActive == false) throw kotlinx.coroutines.CancellationException("MobileI2V cancelled")
              runOnUiThread { generationPercent = p; status = "MobileI2V processing… $p%" }
             }
            } else {
             NightmareVideoClient.generate(this@MainActivity, nightmareToken, nightmareVideoFlow, image, motionPrompt, duration) { stage ->
              runOnUiThread {
               if (busy && !useMobileI2V && generationJob?.isActive == true) status = stage
              }
             }
            }
           }
           kotlinx.coroutines.currentCoroutineContext().ensureActive()
           val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "FreedomAI_Motion_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FreedomAI")
            put(MediaStore.Video.Media.IS_PENDING, 1)
           }
           val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Cannot save video")
           try {
            withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)?.use { it.write(video.bytes) } ?: error("Cannot write MP4") }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            val quality = withContext(Dispatchers.IO) {
             runCatching { VideoMotionCheck.inspect(this@MainActivity, uri) }.getOrNull()
            }
            lastVideo = uri
            lastVideoGenerator = if (useMobileI2V) "MobileI2V (offline)" else "Nightmare"
            prefs.edit().putString("last_video_generator", lastVideoGenerator)
             .putString("last_video_uri", uri.toString()).apply()
            showVideoPlayer = true
            generationPercent = 100
            status = if (useMobileI2V) "Experimental MobileI2V video saved (${video.width}×${video.height}, ${video.durationLabel}s). Motion prompts are unsupported in the official checkpoint; check frame quality." else if (video.reducedForMemory) "AI video saved to Movies/FreedomAI (reduced to ${video.width}×${video.height}, ${video.durationLabel}s to fit NPU memory)." else "AI video saved to Movies/FreedomAI (${video.width}×${video.height}, ${video.durationLabel}s)."
            if (quality != null && quality.samples >= 4) {
             if (quality.nearlyStatic) status += " WARNING: Video contains almost no movement."
             if (quality.largeFrameChanges >= 3) status += " WARNING: Abrupt frame changes detected; possible video model/decoder corruption. Try another compatible Nightmare image-to-video workflow."
             android.util.Log.i("FreedomAI-VideoQuality", "Generator=${lastVideoGenerator}, change=${quality.meanChange}, abrupt=${quality.largeFrameChanges}")
            }
           } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
          } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (e: OutOfMemoryError) {
           status = "MobileI2V stopped: Android ran out of memory. Original photo is preserved. Use a smaller model or remote video rendering."
           android.util.Log.e("MobileI2V", "Video memory exhaustion", e)
          }
          catch (e: Exception) {
           android.util.Log.e("MobileI2V", "Video generation failed", e)
           status = "AI video failed: ${e.message}"
          }
          finally { if (generationJob == kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) { busy = false; generationJob = null } }
         }
        }
       }, modifier = Modifier.fillMaxWidth()) { Text("Generate AI Video") }
       
        }
        if (!videoMode) OutlinedButton(onClick = { videoMode = false; original = image; originalGeneratedHere = resultGeneratedHere; brightness = 0f; saturation = 1f; strokes.clear(); maskMode = false; precisionEdit = false; result = null; tab = 1 }) { Text("Edit this image") }
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

private fun generateImage(server: String, prompt: String, input: Bitmap?, editStrength: Float, mask: Bitmap? = null, highQuality: Boolean = true, cartoonMode: Boolean = false): Bitmap {
 val payload = JSONObject().put("prompt", prompt).put("negative_prompt", CartoonImagePrompt.negativePrompt(cartoonMode)).put("steps", if (highQuality) 35 else 20).put("cfg_scale", 6.0).put("width", if (input == null && highQuality) 768 else ((input?.width ?: 512).coerceIn(256, 1024) / 8 * 8)).put("height", if (input == null && highQuality) 1024 else ((input?.height ?: 512).coerceIn(256, 1024) / 8 * 8)).put("batch_size", 1)
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

/** A safe automated face-only edit: no arbitrary pixel masking or repainting. */
/**
 * Replaces only detected face regions, preserving full-resolution eyes/body/background outside
 * those regions. Face detection works on a <=1024px preview to limit memory on 4K images.
 */
private fun compositeRefinedFaces(original: Bitmap, edited: Bitmap): Bitmap? = runCatching {
 val width = original.width
 val height = original.height
 if (width < 64 || height < 64) return@runCatching null
 val previewScale = minOf(1f, 1024f / maxOf(width, height))
 val detectorW = ((width * previewScale).toInt().coerceAtLeast(2) / 2) * 2
 val detectorH = (height * previewScale).toInt().coerceAtLeast(2)
 val detectorInput = Bitmap.createBitmap(detectorW, detectorH, Bitmap.Config.RGB_565)
 try {
  val rect = android.graphics.Rect(0, 0, detectorW, detectorH)
  android.graphics.Canvas(detectorInput).drawBitmap(original, null, rect,
   android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
  val found = arrayOfNulls<android.media.FaceDetector.Face>(12)
  val count = android.media.FaceDetector(detectorW, detectorH, found.size).findFaces(detectorInput, found)
  if (count <= 0) return@runCatching null
  val out = original.copy(Bitmap.Config.ARGB_8888, true)
  val canvas = android.graphics.Canvas(out)
  val sx = width.toFloat() / detectorW
  val sy = height.toFloat() / detectorH
  var changed = 0
  for (face in found.take(count)) {
   if (face == null || face.confidence() < 0.3f) continue
   val eye = face.eyesDistance()
   if (eye < 2f) continue
   val mid = android.graphics.PointF()
   face.getMidPoint(mid)
   val cx = mid.x * sx
   val cy = (mid.y + eye * 0.55f) * sy
   val rx = eye * 1.65f * sx
   val ry = eye * 2.20f * sy
   val oval = android.graphics.RectF(cx-rx, cy-ry, cx+rx, cy+ry)
   val layer = canvas.saveLayer(oval.left, oval.top, oval.right, oval.bottom, null)
   try {
    canvas.drawBitmap(edited, null, android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()),
     android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    val radial = android.graphics.RadialGradient(0f, 0f, 1f,
     intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
     floatArrayOf(0f, 0.60f, 1f), android.graphics.Shader.TileMode.CLAMP)
    radial.setLocalMatrix(android.graphics.Matrix().apply { setScale(rx, ry); postTranslate(cx, cy) })
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
     shader = radial
     xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }
    canvas.drawOval(oval, paint)
    paint.xfermode = null
   } finally { canvas.restoreToCount(layer) }
   changed++
  }
  if (changed == 0) { out.recycle(); null } else out
 } finally { detectorInput.recycle() }
}.getOrNull()

/**
 * Preserve the original face when a regular whole-image edit is not intended
 * to change faces. Face-refinement edits deliberately skip this restoration.
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
