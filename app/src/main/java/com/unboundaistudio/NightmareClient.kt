package com.unboundaistudio

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Nightmare v1 API on the same Android device.
 * The /info endpoint is public; flow execution requires an API token.
 * Do not log or embed the token in the APK.
 */
internal object NightmareClient {
    private const val BASE_URL = "http://127.0.0.1:8820"
    private var cachedModels: org.json.JSONArray? = null
    private var cachedModelsAt: Long = 0L
    @Volatile var lastCartoonModelLabel: String = "Not selected"
        private set

    @Synchronized private fun installedModels(token: String): org.json.JSONArray {
        val now = android.os.SystemClock.elapsedRealtime()
        cachedModels?.takeIf { now - cachedModelsAt < 300_000L }?.let { return it }
        val response = JSONObject(authenticatedGet("/models", token))
        val models = response.optJSONArray("models") ?: response.optJSONArray("items")
            ?: response.optJSONArray("checkpoints")
            ?: error("Nightmare did not report its installed picture models.")
        cachedModels = models
        cachedModelsAt = now
        return models
    }

    fun checkConnection(): String {
        val connection = URL("$BASE_URL/info").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            check(connection.responseCode == 200) {
                "Nightmare API returned HTTP ${connection.responseCode}"
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val info = JSONObject(body)
            check(info.optString("app") == "nightmare") {
                "Port 8820 is not running Nightmare"
            }
            check(info.optInt("api", -1) == 1) {
                "Unsupported Nightmare API version"
            }
            return info.optString("version", "unknown")
        } finally {
            connection.disconnect()
        }
    }

    fun authenticatedGet(path: String, token: String): String {
        require(path.startsWith("/") && !path.startsWith("//")) { "Invalid API path" }
        require(token.isNotBlank() && !token.contains('\n') && !token.contains('\r')) {
            "Enter a valid Nightmare API token"
        }
        val connection = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            val status = connection.responseCode
            check(status in 200..299) { "Nightmare API returned HTTP $status" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
    internal fun editDimensions(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Invalid input image dimensions" }
        val scale = minOf(1.0, 2048.0 / width, 2048.0 / height)
        val w = (width * scale).toInt().coerceIn(1, 2048)
        val h = (height * scale).toInt().coerceIn(1, 2048)
        return Pair(w.coerceAtLeast(512), h.coerceAtLeast(512))
    }

    fun generate(prompt: String, token: String, input: android.graphics.Bitmap?, strength: Float, highQuality: Boolean, resolution: Int = 1024, editFlow: String = "auto", speed: String = "BALANCED", generationFlow: String = "saved:FreedomAI RealVis", disableControlNet: Boolean = false, cartoonMode: Boolean = false, routingPrompt: String? = null): android.graphics.Bitmap {
        require(token.isNotBlank()) { "Enter your Nightmare API token in Connection settings" }
        require(!token.contains('\n') && !token.contains('\r')) { "Invalid Nightmare API token" }
        // Skip the redundant authenticated /info round trip on every generation.
        // The actual flow request validates the token and reports backend errors.
        require(editFlow == "auto" || editFlow == "current" || editFlow == "img2img" ||
                editFlow == "inpaint" || editFlow == "flux_edit" || editFlow.startsWith("saved:")) {
            "Invalid Nightmare edit workflow: $editFlow"
        }
        val requestStarted = android.os.SystemClock.elapsedRealtime()
        if (cartoonMode) require(input == null && generationFlow == "txt2img") {
            "Cartoon creation needs Nightmare Text to image, without an input photo."
        }
        val actualFlow = if (input == null) {
            require(generationFlow == "txt2img" || generationFlow == "current" ||
                (generationFlow.startsWith("saved:") && generationFlow.length > 6)) {
                "Choose a valid Nightmare image workflow: txt2img, current or saved:Your Workflow Name."
            }
            generationFlow
        } else EditRequestRouting.chooseFlow(editFlow, routingPrompt ?: prompt)
        val payload = JSONObject().put("flow", actualFlow)
            .put("prompt", if (input == null) prompt else EditRequestRouting.promptFor(actualFlow, prompt))
        if (cartoonMode) {
            payload.put("negative", CartoonImagePrompt.negativePrompt(true, prompt, routingPrompt ?: prompt))
            // A missing/incomplete model list must never block text-to-image generation.
            // Prefer an installed drawing model; otherwise use Nightmare's current model.
            val installed = runCatching { installedModels(token) }
                .onFailure {
                    android.util.Log.w("FreedomAI-Cartoon",
                        "Cannot list models; using the selected Nightmare model.")
                }.getOrNull()
            val selection = CartoonModelSelector.chooseOrCurrent(installed, prompt)
            selection.modelId?.let { payload.put("model", it) }
            lastCartoonModelLabel = selection.label
            if (selection.modelId == null) {
                android.util.Log.w("FreedomAI-Cartoon",
                    "No recognized drawing model. Rendering with current Nightmare model; style may vary.")
            } else {
                android.util.Log.i("FreedomAI-Cartoon", "Using installed drawing model: " + selection.modelId)
            }
        }

        var editFrame: NightmareEditGeometry.Frame? = null
        if (input == null) {
            val (width, height) = if (cartoonMode) CartoonImagePrompt.canvasFor(prompt, resolution)
                else resolution to resolution
            val steps = if (cartoonMode) when (speed) {
                "FAST" -> 16; "MAX QUALITY" -> 38; else -> 28
            } else when (speed) {
                "FAST" -> 12; "MAX QUALITY" -> 35; else -> if (highQuality) 35 else 22
            }
            payload.put("steps", steps)
                .put("cfg", if (cartoonMode) 7.0 else 6.0)
                .put("width", width).put("height", height)
        } else {
            // Edit at model-friendly resolution; retain full source for framing/compositing in the UI.
            // Fast photo edits run at 512px in Nightmare, avoiding the much slower 1024px
            // diffusion pass. Preserve aspect with letterboxing and keep source untouched.
            val maxEditEdge = when (speed) { "FAST" -> 512; "MAX QUALITY" -> 1536; else -> 1024 }
            val frame = NightmareEditGeometry.plan(input.width, input.height, maxEditEdge)
            editFrame = frame
            val editWidth = frame.width
            val editHeight = frame.height
            // Record exact source rectangle. A diffusion result may include padding;
            // restoreOutput crops it instead of leaving a horizontal or vertical band.
            val editBitmap = frame.encodeSource(input)
            val stream = java.io.ByteArrayOutputStream()
            // JPEG dramatically reduces local API upload size for photographic inputs.
            // Use PNG for images with transparency to avoid destroying alpha masks.
            val hasAlpha = input.hasAlpha()
            val format = if (hasAlpha) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG
            check(editBitmap.compress(format, if (hasAlpha) 100 else 92, stream)) {
                "Failed to encode original image for editing"
            }
            payload.put("images", JSONObject().put("image",
                android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)))

            when (actualFlow) {
                "inpaint" -> {
                    // Auto-mask clothing using Nightmare's 29 MB Clothes parser. No hand-painting.
                    // Its inpaint node composites the masked change onto the original photo.
                    // If the parser isn't installed, Nightmare refuses instead of silently
                    // running a full-frame img2img variation.
                    payload.put("denoise", strength.coerceAtLeast(0.60f).toDouble())
                        // Faster 10-step FAST preview; higher-quality modes retain their steps.
                        .put("steps", when (speed) { "FAST" -> 10; "MAX QUALITY" -> 35; else -> 24 }).put("cfg", 6.0)
                        .put("width", editWidth).put("height", editHeight)
                        .put("params", JSONObject().put("inpaint", JSONObject()
                            .put("pick_select", "true")
                            // Nightmare validates dimensions on the inpaint node itself.
                            .put("width", editWidth).put("height", editHeight)
                            .put("auto_crop", "true")
                            .put("only_masked", "true")
                            .put("stitch", "true")))
                }
                "flux_edit" -> {
                    // Instruction-following edit is a DIFFERENT Nightmare recipe from img2img.
                    // Do not run an SDXL restyle and claim a requested object edit succeeded.
                    val installed = installedModels(token)
                    // FAST selects an installed Klein model when available;
                    // BALANCED/MAX QUALITY may use the more detailed Qwen model.
                    // No additional download and no SDXL img2img substitution.
                    val model = NightmareEditModelPicker.select(installed, speed).orEmpty()
                    check(model.isNotBlank()) {
                        "Nightmare has no instruction-edit model installed. Open Nightmare → Models → Edit and install a compatible FLUX Klein or Qwen Image model. Your RealVis/SDXL image-to-image flow only restyles the photo."
                    }
                    // Respect the requested edit resolution instead of forcing 512 px.
                    // Higher sizes require more memory in Nightmare; report backend errors rather than silently downgrading.
                    // Match the aspect-preserving source frame; a forced square
                    // stretches faces and introduces apparent bands on wide photos.
                    payload.put("model", model).put("cfg", 1.0).put("denoise", 1.0)
                        .put("width", editWidth).put("height", editHeight)
                }
                else -> {
                    // Explicit saved/current/img2img flows remain available for custom workflows.
                    payload.put("steps", when (speed) { "FAST" -> 14; "MAX QUALITY" -> 35; else -> 24 }).put("cfg", 6.0)
                        .put("width", editWidth).put("height", editHeight)
                        .put("denoise", strength.toDouble())
                }
            }
        }
        // Keep this specific to the memory-recovery retry. Ordinary custom flows
        // are left untouched; Nightmare rejects params for nodes that do not have them.
        if (input == null && disableControlNet) {
            NightmareControlNetRecovery.disableControlNet(payload)
        }
        val preparationMs = android.os.SystemClock.elapsedRealtime() - requestStarted
        val connection = URL("$BASE_URL/run").openConnection() as HttpURLConnection
        val reply = try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 600000
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 500 && body.contains("backend did not start", ignoreCase = true)) {
                error("Nightmare could not start the processing backend for flow '${payload.optString("flow")}'. This is a Nightmare model/runtime startup failure, not a button or image-upload failure. Open that exact flow in Nightmare and run a simple image-to-image edit; check model loading, available RAM and Nightmare logs. Run ID: ${runCatching { JSONObject(body).optString("run") }.getOrDefault("unknown")}")
            }
            if (EditRequestRouting.shouldRetryMissingClothingMask(editFlow, actualFlow, code, body)) {
                // Auto edit can recover without downloading or painting a mask.
                // Only a genuine auto-mask failure permits this one-time retry.
                val fallback = runCatching {
                    generate(prompt, token, input, strength, highQuality, resolution,
                        "flux_edit", speed, routingPrompt = routingPrompt)
                }
                fallback.getOrNull()?.let { return it }
                error("Nightmare clothing auto-mask unavailable. Installed Qwen/FLUX instruction-edit fallback also failed: ${fallback.exceptionOrNull()?.message?.take(230)}. Original photo preserved.")
            }
            if (code !in 200..299 && actualFlow == "inpaint" &&
                (body.contains("parser", true) || body.contains("picked by name", true) ||
                 body.contains("auto mask", true) || body.contains("auto-mask", true) ||
                 body.contains("cannot automatically mask", true))) {
                // A missing clothes parser should not strand users who already have a
                // compatible instruction-following editor installed in Nightmare.
                // Never silently fall back to SDXL img2img: it can replace the person.
                error("Nightmare clothing auto-mask is unavailable for the explicitly selected inpaint workflow. Original photo preserved. Switch Nightmare edit workflow to Auto to use an installed Qwen/FLUX editor, or enable Clothes Auto Mask.")
            }
            if (code !in 200..299 && input == null && !disableControlNet &&
                NightmareControlNetRecovery.isControlNetNpuFailure(body)) {
                // Retry only once, keeping the same image checkpoint, prompt, size,
                // steps and workflow. This is a per-request override, not a saved-flow edit.
                // The 500 came from insufficient NPU memory for the second QNN model.
                android.util.Log.w("FreedomAI-Nightmare", "ControlNet NPU memory failure; retrying without ControlNet")
                connection.disconnect()
                return try {
                    generate(prompt, token, input, strength, highQuality, resolution,
                        editFlow, speed, generationFlow, disableControlNet = true, cartoonMode = cartoonMode, routingPrompt = routingPrompt)
                } catch (retryError: Exception) {
                    error("Nightmare could not create the image without ControlNet. Open Nightmare, select the current flow, tap the image Generate node, and set ControlNet to None. Your models were not removed. Retry: ${retryError.message?.take(180)}")
                }
            }
            if (code !in 200..299 && input == null && disableControlNet &&
                NightmareControlNetRecovery.isControlNetNpuFailure(body)) {
                error("Nightmare still reports insufficient NPU memory for ControlNet. In Nightmare, set the current flow's Generate → ControlNet to None and retry.")
            }
            check(code in 200..299) { "Nightmare HTTP $code (flow: $actualFlow): ${body.take(350)}" }
            check(body.isNotBlank()) { "Nightmare returned an empty response" }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
        val processingMs = android.os.SystemClock.elapsedRealtime() - requestStarted - preparationMs
        android.util.Log.i("FreedomAI-Timing", "Nightmare prepare=${preparationMs}ms backend=${processingMs}ms flow=$actualFlow")
        val results = reply.optJSONArray("results") ?: error("Nightmare response has no results: ${reply.toString().take(350)}")
        val result = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .firstOrNull { it.optString("url").endsWith(".png", ignoreCase = true) }
            ?: error("Nightmare did not return a PNG image: ${reply.toString().take(350)}")
        val resultPath = result.getString("url")
        require(resultPath.startsWith("/results/")) { "Unexpected Nightmare result path" }
        if (cartoonMode) {
            val runId = reply.optString("run")
            check(runId.isNotBlank() && resultPath.startsWith("/results/$runId/")) {
                "Nightmare did not return an image from this cartoon run; no old photo will be shown."
            }
        }
        val downloadStarted = android.os.SystemClock.elapsedRealtime()
        val download = URL("$BASE_URL$resultPath").openConnection() as HttpURLConnection
        try {
            download.connectTimeout = 10000
            download.readTimeout = 60000
            download.setRequestProperty("Authorization", "Bearer $token")
            check(download.responseCode == 200) { "Image download failed: HTTP ${download.responseCode}" }
            val bitmap = download.inputStream.buffered(64 * 1024).use { android.graphics.BitmapFactory.decodeStream(it) }
            android.util.Log.i("FreedomAI-Timing", "Nightmare download=${android.os.SystemClock.elapsedRealtime() - downloadStarted}ms")
            android.util.Log.i("FreedomAI-Timing", "Nightmare total=${android.os.SystemClock.elapsedRealtime() - requestStarted}ms")
            val decoded = bitmap ?: error("Nightmare returned an unreadable image")
            return editFrame?.restoreOutput(decoded) ?: decoded
        } finally {
            download.disconnect()
        }
    }

}
