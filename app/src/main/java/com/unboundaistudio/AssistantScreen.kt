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
fun AssistantScreen() {
 val context = LocalContext.current
 val prefs = remember { context.getSharedPreferences("assistant", android.content.Context.MODE_PRIVATE) }
 var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "") ?: "") }
 var model by remember { mutableStateOf(prefs.getString("model", "") ?: "") }
 var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
 var error by remember { mutableStateOf("") }
 var localModelPath by remember { mutableStateOf(prefs.getString("local_model", "") ?: "") }
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
 var hfToken by remember { mutableStateOf(prefs.getString("hf_token", "") ?: "") }
 var downloadStatus by remember { mutableStateOf("") }
 var downloading by remember { mutableStateOf(false) }
 var message by remember { mutableStateOf("") }
 var busy by remember { mutableStateOf(false) }
 var speak by remember { mutableStateOf(true) }
 val history = remember { mutableStateListOf<Pair<String, String>>() }
 val scope = rememberCoroutineScope()
 val tts = remember { TextToSpeech(context) { } }
 DisposableEffect(tts) { onDispose { tts.stop(); tts.shutdown() } }
 val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
  val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
  if (!words.isNullOrEmpty()) message = words[0]
 }
 Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
  Text("AI Assistant", style = MaterialTheme.typography.headlineMedium)
  Text("Chat by typing or speaking. Import a compatible .task model for offline replies, or connect a chat service.")
  Text("Offline AI setup: Gemma 3 1B (one-time download, then works without internet). Accept the model license on Hugging Face before downloading.")
  OutlinedTextField(hfToken, { hfToken = it }, label = { Text("Hugging Face access token") }, modifier = Modifier.fillMaxWidth(), visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
  Button(enabled = !downloading, onClick = {
   downloading = true
   downloadStatus = "Downloading model..."
   scope.launch {
    try {
     val downloaded = withContext(Dispatchers.IO) { downloadGemmaModel(context, hfToken.trim()) }
     localModelPath = downloaded
     prefs.edit().putString("local_model", downloaded).putString("hf_token", hfToken.trim()).apply()
     downloadStatus = "Offline AI ready"
    } catch (e: Exception) { downloadStatus = e.message ?: "Download failed" }
    finally { downloading = false }
   }
  }) { Text(if (downloading) "Downloading..." else "Install offline AI") }
  if (downloadStatus.isNotBlank()) Text(downloadStatus)
  OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }) { Text("Import offline AI model (.task)") }
  Text(if (localModelPath.isNotBlank()) "Offline model installed" else "No offline AI model installed")
  OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Chat API URL (HTTPS)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
  OutlinedTextField(model, { model = it }, label = { Text("Model name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
  OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
  Button(onClick = {
   prefs.edit().putString("endpoint", endpoint.trim()).putString("model", model.trim()).putString("api_key", apiKey).apply()
   error = "Connection settings saved on this device."
  }) { Text("Save connection") }
  Row { Checkbox(checked = speak, onCheckedChange = { speak = it }); Text("Read replies aloud") }
  Column(Modifier.fillMaxWidth().heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
   history.forEach { (role, text) ->
    Text(if (role == "user") "You: $text" else "Assistant: $text", modifier = Modifier.padding(vertical = 6.dp))
   }
  }
  if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
  OutlinedTextField(message, { message = it }, label = { Text("Ask anything") }, modifier = Modifier.fillMaxWidth())
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
    busy = true
    error = ""
    scope.launch {
     try {
      val answer = withContext(Dispatchers.IO) { if (localModelPath.isNotBlank()) offlineAssistantReply(context, localModelPath, history.toList()) else assistantReply(endpoint, model, apiKey, history.toList()) }
      history.add("assistant" to answer)
      if (speak) tts.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "assistant-reply")
     } catch (e: Exception) {
      error = e.message ?: "Assistant connection failed."
     } finally { busy = false }
    }
   }) { Text(if (busy) "Thinking…" else "Send") }
  }
 }
}

private fun assistantReply(endpoint: String, model: String, apiKey: String, history: List<Pair<String, String>>): String {
 val url = URL(endpoint.trim())
 require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) { "Enter a valid HTTPS chat API URL." }
 require(model.isNotBlank()) { "Enter the chat model name." }
 val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "You are a helpful conversational assistant in an Android creative studio."))
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
  if (status !in 200..299) throw IllegalStateException("Chat service returned HTTP $status")
  val body = connection.inputStream.bufferedReader().use { it.readText().take(1000000) }
  return JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
 } finally { connection.disconnect() }
}

private fun offlineAssistantReply(context: android.content.Context, path: String, history: List<Pair<String, String>>): String {
 val options = LlmInference.LlmInferenceOptions.builder().setModelPath(path).setMaxTokens(1024).build()
 val engine = LlmInference.createFromOptions(context, options)
 try {
  val prompt = buildString {
   append("You are a helpful assistant.\\n")
   history.takeLast(8).forEach { (role, message) ->
    append(if (role == "user") "User: " else "Assistant: ")
    append(message).append("\\n")
   }
   append("Assistant: ")
  }
  return engine.generateResponse(prompt)
 } finally { engine.close() }
}

private fun downloadGemmaModel(context: android.content.Context, token: String): String {
 require(token.isNotBlank()) { "Enter a Hugging Face token after accepting the Gemma license." }
 val target = File(context.filesDir, "assistant-model.task")
 val partial = File(context.filesDir, "assistant-model.task.partial")
 val url = URL("https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true")
 val connection = url.openConnection() as HttpURLConnection
 try {
  connection.connectTimeout = 30000
  connection.readTimeout = 120000
  connection.setRequestProperty("Authorization", "Bearer $token")
  connection.instanceFollowRedirects = true
  val status = connection.responseCode
  require(status in 200..299) { "Model download HTTP $status. Accept the Gemma license and check your token." }
  require(connection.contentType?.contains("text/html") != true) { "Received a webpage, not a model." }
  connection.inputStream.use { input -> partial.outputStream().use { output -> input.copyTo(output) } }
  require(partial.length() > 100_000_000L) { "Downloaded model is incomplete." }
  if (target.exists()) target.delete()
  require(partial.renameTo(target)) { "Could not save model." }
  return target.absolutePath
 } finally { connection.disconnect() }
}
