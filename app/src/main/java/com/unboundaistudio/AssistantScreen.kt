package com.unboundaistudio

import android.content.Intent
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

@Composable
fun AssistantScreen(onCreateImage: (String) -> Unit = {}, onEditImage: (String) -> Unit = {}, onCreateVideo: (String) -> Unit = {}) {
 val context = LocalContext.current
 val prefs = remember { context.getSharedPreferences("assistant", android.content.Context.MODE_PRIVATE) }
 val defaultOnlineEndpoint = "https://router.huggingface.co/v1/chat/completions"
 var endpoint by remember { mutableStateOf(prefs.getString("endpoint", defaultOnlineEndpoint).orEmpty().ifBlank { defaultOnlineEndpoint }) }
 var model by remember { mutableStateOf(prefs.getString("model", "openai/gpt-oss-120b:fastest").orEmpty().ifBlank { "openai/gpt-oss-120b:fastest" }) }
 var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
 var error by remember { mutableStateOf("") }
 var localModelPath by remember { mutableStateOf(prefs.getString("local_model", "") ?: "") }
 var offlineSettingsVisible by remember { mutableStateOf(false) }
 var showAssistantSettings by remember { mutableStateOf(false) }
 var useOfflineAssistant by remember { mutableStateOf(prefs.getBoolean("use_offline_assistant", false)) }
 var allowMatureTopics by remember { mutableStateOf(prefs.getBoolean("allow_mature_topics", true)) }
 var customInstructions by remember { mutableStateOf(prefs.getString("custom_instructions", "").orEmpty()) }
 val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
  if (uri != null) {
   val target = File(context.filesDir, "assistant-model.task")
   runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
     target.outputStream().use { output -> input.copyTo(output) }
    } ?: error("Could not open model")
    require(target.length() > 1024 * 1024) { "Model is incomplete" }
    localModelPath = target.absolutePath
    prefs.edit().putString("local_model", localModelPath).apply()
   }.onFailure { error = it.message ?: "Model import failed" }
  }
 }
 val embeddedHfToken = BuildConfig.HF_ACCESS_TOKEN
 var hfToken by remember { mutableStateOf(prefs.getString("hf_token", "") ?: "") }
 val effectiveHfToken = embeddedHfToken.ifBlank { hfToken.trim() }
 var downloadStatus by remember { mutableStateOf("") }
 var downloadPercent by remember { mutableIntStateOf(0) }
 var downloading by remember { mutableStateOf(false) }
 var message by remember { mutableStateOf("") }
 var busy by remember { mutableStateOf(false) }
 var speak by remember { mutableStateOf(true) }
 val history = remember {
  mutableStateListOf<Pair<String, String>>().apply {
   runCatching {
    val saved = JSONArray(prefs.getString("chat_history", "[]") ?: "[]")
    for (i in 0 until saved.length()) {
     val item = saved.optJSONObject(i) ?: continue
     val role = item.optString("role")
     if (role == "user" || role == "assistant") add(role to item.optString("text"))
    }
   }
  }
 }
 LaunchedEffect(history.size) { saveAssistantHistory(prefs, history.toList()) }
 DisposableEffect(history) { onDispose { saveAssistantHistory(prefs, history.toList()) } }
 val scope = rememberCoroutineScope()
 val tts = remember { TextToSpeech(context) { } }
 DisposableEffect(tts) { onDispose { tts.stop(); tts.shutdown() } }
 val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
  val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
  if (!words.isNullOrEmpty()) message = words[0]
 }
 Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
  Text("AI Assistant", style = MaterialTheme.typography.headlineMedium)
  Text("Speak or type your message, then tap Send.")
  TextButton(onClick = { showAssistantSettings = !showAssistantSettings }) { Text(if (showAssistantSettings) "Hide settings ↑" else "Assistant settings ↓") }
  if (showAssistantSettings) {
  Text("Online assistant: Hugging Face Inference Providers. HTTP 402 means provider credits or billing are required. When a compatible offline model is installed, online failures can fall back to offline automatically.")
  Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
   Text("Discuss mature adult topics", modifier = Modifier.weight(1f))
   Switch(checked = allowMatureTopics, onCheckedChange = {
    allowMatureTopics = it
    prefs.edit().putBoolean("allow_mature_topics", it).apply()
   })
  }
  Text("This changes chat guidance for lawful adult discussions. Online providers and image/video services may still have their own content rules.")
  OutlinedTextField(
   value = customInstructions,
   onValueChange = { next ->
    customInstructions = next.take(1500)
    prefs.edit().putString("custom_instructions", customInstructions).apply()
   },
   label = { Text("My AI instructions (optional)") },
   placeholder = { Text("Example: Be direct, creative, and detailed.") },
   modifier = Modifier.fillMaxWidth(),
   minLines = 2
  )
  if (BuildConfig.HF_ACCESS_TOKEN.isNotBlank()) {
   Text("Hugging Face access configured for this build.")
  }
  if (BuildConfig.HF_ACCESS_TOKEN.isBlank() && apiKey.isBlank()) {
   Text("A Hugging Face Inference Providers token is required for online chat.")
  }
  Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
   Text("Prefer offline assistant", modifier = Modifier.weight(1f))
   Switch(checked = useOfflineAssistant, onCheckedChange = {
    useOfflineAssistant = it
    prefs.edit().putBoolean("use_offline_assistant", it).apply()
   })
  }
  if (useOfflineAssistant) {
   OutlinedButton(onClick = { offlineSettingsVisible = !offlineSettingsVisible }) {
    Text(if (offlineSettingsVisible) "Hide offline model setup" else "Offline model setup (optional)")
   }
   if (offlineSettingsVisible) {
     Text("Offline AI setup: Gemma 3 1B (.task format). The Qwen Image model in Nightmare cannot be used for chat. Install a compatible language model once to enable offline replies and automatic fallback.")
     if (embeddedHfToken.isNotBlank()) {
      Text("Hugging Face access configured automatically.")
     } else {
      OutlinedTextField(hfToken, { hfToken = it; prefs.edit().putString("hf_token", it.trim()).apply() }, label = { Text("Hugging Face access token (one-time setup)") }, modifier = Modifier.fillMaxWidth(), visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
     }
     Button(enabled = !downloading, onClick = {
      downloading = true
      downloadStatus = "Downloading model..."
      scope.launch {
       try {
        val downloaded = withContext(Dispatchers.IO) { downloadGemmaModel(context, effectiveHfToken) { percent -> downloadPercent = percent } }
        localModelPath = downloaded
        prefs.edit().putString("local_model", downloaded).apply()
        downloadStatus = "Offline AI downloaded. Ready to chat."
       } catch (e: Exception) { downloadStatus = e.message ?: "Download failed" }
       finally { downloading = false }
      }
     }) { Text(if (downloading) "Downloading..." else "Install offline AI") }
     if (downloadStatus.isNotBlank()) Text(downloadStatus)
     if (downloading) { LinearProgressIndicator(progress = { downloadPercent / 100f }, modifier = Modifier.fillMaxWidth()); Text("$downloadPercent% downloaded") }
     OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }) { Text("Import offline AI model (.task)") }
     Text(if (localModelPath.isNotBlank() && File(localModelPath).length() > 100_000_000L) "Offline model installed" else "No offline AI model installed")
   
   }
  }
  OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Chat API URL (HTTPS)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
  OutlinedTextField(model, { model = it }, label = { Text("Model name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
  OutlinedTextField(apiKey, { apiKey = it }, label = { Text("Online chat API token (optional when configured in build)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
  Button(onClick = {
   prefs.edit().putString("endpoint", endpoint.trim()).putString("model", model.trim()).putString("api_key", apiKey).apply()
   error = "Connection settings saved on this device."
  }) { Text("Save connection") }
  } // End assistant settings; keep chat visible
  Row { Checkbox(checked = speak, onCheckedChange = { speak = it }); Text("Read replies aloud") }
  Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
   Text("Conversation", style = MaterialTheme.typography.titleMedium)
   TextButton(onClick = { history.clear(); prefs.edit().remove("chat_history").apply() }) { Text("Clear chat") }
  }
  Column(Modifier.fillMaxWidth().heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
   history.forEach { (role, text) ->
    Text(if (role == "user") "You: $text" else "Assistant: ${text.replace(Regex("^(Assistant:\\s*)+", RegexOption.IGNORE_CASE), "").trim()}", modifier = Modifier.padding(vertical = 6.dp))
   }
  }
  if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
  OutlinedTextField(message, { message = it }, label = { Text("Ask anything") }, modifier = Modifier.fillMaxWidth())
  // Web image discovery is separate from AI image generation and works without a chat API token.
  // Results open in the user's browser, where the provider controls adult filtering.
  OutlinedButton(
   enabled = !busy && message.isNotBlank(),
   onClick = {
    val query = message.trim()
    val url = Uri.parse("https://www.google.com/search").buildUpon()
     .appendQueryParameter("tbm", "isch")
     .appendQueryParameter("q", query)
     .build()
    runCatching {
     context.startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
     history.add("user" to "Search web images: $query")
     history.add("assistant" to "Opened live web image results in your browser. Search results and adult-content filtering are controlled by the search provider and browser settings.")
     message = ""
    }.onFailure { error = "No browser available to open image results: ${it.message}" }
   },
   modifier = Modifier.fillMaxWidth()
  ) { Text("Search Web Images") }
  Text("Search Web Images finds existing online photos. Send/Create Image generates a new image instead. Search-provider SafeSearch and content rules still apply.")
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
   OutlinedButton(onClick = {
    runCatching {
     speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
     })
    }.onFailure { error = "Speech recognition is unavailable on this phone." }
   }) { Text("🎤 Speak") }
   Button(enabled = !busy && message.isNotBlank(), onClick = {
    val input = message.trim()
    message = ""
    history.add("user" to input)
    when (AssistantCommandRouter.classify(input)) {
     AssistantCommand.SEARCH_IMAGES, AssistantCommand.SEARCH_WEB -> {
      val images = AssistantCommandRouter.classify(input) == AssistantCommand.SEARCH_IMAGES
      val builder = Uri.parse("https://www.google.com/search").buildUpon()
       .appendQueryParameter("q", input)
      if (images) builder.appendQueryParameter("tbm", "isch")
      runCatching {
       context.startActivity(Intent(Intent.ACTION_VIEW, builder.build()).addCategory(Intent.CATEGORY_BROWSABLE))
       history.add("assistant" to "Opened live search results in your browser. Search-provider filtering may apply.")
      }.onFailure { error = "Unable to open web search: ${it.message}" }
      return@Button
     }
     AssistantCommand.CREATE_VIDEO -> {
      history.add("assistant" to "Video instructions ready. Select the photo you want to animate.")
      onCreateVideo(input)
      return@Button
     }
     AssistantCommand.EDIT_IMAGE -> {
      history.add("assistant" to "Edit instructions ready. Select a photo and tap AI Edit.")
      onEditImage(input)
      return@Button
     }
     AssistantCommand.CREATE_IMAGE -> {
      history.add("assistant" to "Opening image generation with your prompt.")
      onCreateImage(input)
      return@Button
     }
     AssistantCommand.CHAT -> Unit
    }
    busy = true
    error = ""
    scope.launch {
     try {
      val answer = withContext(Dispatchers.IO) {
       if (useOfflineAssistant) {
         if (localModelPath.isBlank() || File(localModelPath).length() <= 100_000_000L) {
          downloading = true
          downloadStatus = "Preparing offline assistant automatically..."
          val downloaded = downloadGemmaModel(context, effectiveHfToken) { percent -> downloadPercent = percent }
          localModelPath = downloaded
          prefs.edit().putString("local_model", downloaded).apply()
          downloading = false
          downloadStatus = "Offline assistant ready."
         }
        offlineAssistantReply(context, localModelPath, history.toList(), allowMatureTopics, customInstructions)
       } else {
        val onlineToken = apiKey.ifBlank { BuildConfig.HF_ACCESS_TOKEN }
        if (onlineToken.isBlank() && endpoint == defaultOnlineEndpoint)
         throw IllegalStateException("Hugging Face online chat needs an Inference Providers token. Add one under API token.")
        try {
         assistantReply(endpoint, model, onlineToken, history.toList(), allowMatureTopics, customInstructions)
        } catch (onlineError: Exception) {
         val offlineFile = File(localModelPath)
         if (localModelPath.isNotBlank() && offlineFile.isFile && offlineFile.length() > 100_000_000L) {
          try {
           offlineAssistantReply(context, localModelPath, history.toList(), allowMatureTopics, customInstructions)
          } catch (offlineError: Exception) {
           throw IllegalStateException("Online assistant failed: ${onlineError.message}. Offline fallback failed: ${offlineError.message}")
          }
         } else {
          downloading = true
           downloadStatus = "Online chat unavailable. Setting up free offline assistant..."
           val downloaded = downloadGemmaModel(context, effectiveHfToken) { percent -> downloadPercent = percent }
           localModelPath = downloaded
           prefs.edit().putString("local_model", downloaded).apply()
           downloading = false
           downloadStatus = "Offline assistant ready."
           offlineAssistantReply(context, downloaded, history.toList(), allowMatureTopics, customInstructions)
         }
        }
       }
      }
      history.add("assistant" to answer.replace(Regex("^(Assistant:\\s*)+", RegexOption.IGNORE_CASE), "").trim())
      if (speak) tts.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "assistant-reply")
     } catch (e: Exception) {
      error = e.message ?: "Assistant connection failed."
     } finally { busy = false; downloading = false }
    }
   }) { Text(if (busy) "Thinking…" else "Send") }
  }
 }
}

private fun assistantReply(endpoint: String, model: String, apiKey: String, history: List<Pair<String, String>>, allowMatureTopics: Boolean, customInstructions: String): String {
 require(endpoint.trim().startsWith("https://", ignoreCase = true)) { "Enter an HTTPS chat API URL, or install the offline AI model." }
 val url = URL(endpoint.trim())
 require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) { "Enter a valid HTTPS chat API URL." }
 require(model.isNotBlank()) { "Enter the chat model name." }
 val messages = JSONArray().put(JSONObject().put("role", "system").put("content", assistantInstructionText(allowMatureTopics, customInstructions)))
 history.takeLast(20).forEach { (role, text) -> messages.put(JSONObject().put("role", role).put("content", text)) }
 val payload = JSONObject().put("model", model).put("messages", messages).put("stream", false)
 val connection = url.openConnection() as HttpURLConnection
 try {
  connection.requestMethod = "POST"
  connection.connectTimeout = 15000
  connection.readTimeout = 90000
  connection.doOutput = true
  connection.instanceFollowRedirects = false
  connection.setRequestProperty("Content-Type", "application/json")
  if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
  connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
  val status = connection.responseCode
  if (status !in 200..299) throw IllegalStateException(if (status == 402) "Online chat HTTP 402: provider credits exhausted or billing required" else "Chat service returned HTTP $status")
  val body = connection.inputStream.bufferedReader().use { it.readText().take(1000000) }
  return JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
 } finally { connection.disconnect() }
}

private fun offlineAssistantReply(context: android.content.Context, path: String, history: List<Pair<String, String>>, allowMatureTopics: Boolean, customInstructions: String): String {
 val options = LlmInference.LlmInferenceOptions.builder().setModelPath(path).setMaxTokens(1024).build()
 val engine = LlmInference.createFromOptions(context, options)
 try {
  val prompt = buildString {
   append(assistantInstructionText(allowMatureTopics, customInstructions)).append("\\n")
   append("Image and video generation use the app controls, not this text-only chat.\\n")
   history.takeLast(8).forEach { (role, message) ->
    append(if (role == "user") "User: " else "Assistant: ")
    append(message.replace(Regex("^(Assistant:\\s*)+", RegexOption.IGNORE_CASE), "")).append("\n")
   }
   append("Assistant: ")
  }
  return engine.generateResponse(prompt).replace(Regex("^(Assistant:\\s*)+", RegexOption.IGNORE_CASE), "").trim()
 } finally { engine.close() }
}

private suspend fun downloadGemmaModel(context: android.content.Context, token: String, onProgress: (Int) -> Unit): String {
 require(token.startsWith("hf_") && token.length > 10) { "Enter a valid Hugging Face read token." }
 val target = File(context.filesDir, "assistant-model.task")
 val partial = File(context.filesDir, "assistant-model.task.partial")
 // Access to google/gemma-3-1b-it does not grant access to this separate LiteRT repository.
 val modelUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"
 var current = URL(modelUrl)
 var connection: HttpURLConnection? = null
 try {
  var redirects = 0
  while (true) {
   require(current.protocol == "https") { "Model download requires HTTPS." }
   connection = current.openConnection() as HttpURLConnection
   connection.connectTimeout = 30000
   connection.readTimeout = 120000
   connection.instanceFollowRedirects = false
   // Only send the secret to Hugging Face, never to redirected file storage hosts.
   if (current.host == "huggingface.co") connection.setRequestProperty("Authorization", "Bearer $token")
   val status = connection.responseCode
   if (status in listOf(301, 302, 303, 307, 308)) {
    val location = connection.getHeaderField("Location") ?: error("Model server redirected without a destination.")
    val next = URL(current, location)
    require(next.protocol == "https") { "Unsafe model download redirect." }
    require(++redirects <= 8) { "Too many model download redirects." }
    connection.disconnect()
    connection = null
    current = next
    continue
   }
   if (status == 401 || status == 403) {
    error("Hugging Face HTTP $status: grant access to litert-community/Gemma3-1B-IT (not just google/gemma-3-1b-it), and use a token with read permission for that repository.")
   }
   if (status == 404) error("Gemma model file not found (404). The model download path must be updated.")
   require(status in 200..299) { "Model download failed: HTTP $status." }
   val type = connection.contentType.orEmpty()
   require(!type.contains("text/html", ignoreCase = true) && !type.contains("application/json", ignoreCase = true)) { "Model server returned a webpage or error instead of a model." }
   val expectedBytes = connection.contentLengthLong
   partial.delete()
   connection.inputStream.use { input ->
    partial.outputStream().use { output ->
     val buffer = ByteArray(262144)
     var received = 0L
     while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      output.write(buffer, 0, count)
      received += count
      if (expectedBytes > 0) {
       val percent = (received * 100 / expectedBytes).toInt().coerceIn(0, 99)
       withContext(Dispatchers.Main) { onProgress(percent) }
      }
     }
    }
   }
   require(expectedBytes <= 0 || partial.length() == expectedBytes) { "Model download interrupted; retry." }
   require(partial.length() > 100_000_000L) { "Downloaded file is too small to be a Gemma model." }
   // Keep a previously installed model until the new download is complete.
   val backup = File(context.filesDir, "assistant-model.task.backup")
   backup.delete()
   if (target.exists()) require(target.renameTo(backup)) { "Could not back up existing model." }
   if (!partial.renameTo(target)) {
    if (backup.exists()) backup.renameTo(target)
    error("Could not save the downloaded model.")
   }
   backup.delete()
   withContext(Dispatchers.Main) { onProgress(100) }
   return target.absolutePath
  }
 } finally {
  connection?.disconnect()
  partial.delete()
 }
}

private fun assistantInstructionText(allowMatureTopics: Boolean, customInstructions: String): String =
 buildString {
  append("You are FreedomAI's practical conversational assistant. Give accurate, direct responses and use conversation context. Be candid about limitations: do not falsely claim to browse the web, create files or operate app features. Help with creative work, technical tasks and everyday questions. ")
  if (allowMatureTopics)
   append("Discuss lawful, consensual adult subjects directly and without needless moralizing. Respect consent, privacy and applicable law. ")
  else
   append("Keep the discussion suitable for general audiences. ")
  if (customInstructions.isNotBlank()) append("User preferences: ").append(customInstructions.take(1500))
 }

private fun saveAssistantHistory(prefs: android.content.SharedPreferences, history: List<Pair<String, String>>) {
 val saved = JSONArray()
 history.takeLast(40).forEach { (role, text) ->
  if (role == "user" || role == "assistant")
   saved.put(JSONObject().put("role", role).put("text", text.take(6000)))
 }
 prefs.edit().putString("chat_history", saved.toString()).apply()
}
