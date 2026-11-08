package com.unboundaistudio

import android.content.Context
import android.graphics.Bitmap
import android.os.StatFs
import com.scsonic.qwenimage21.ModelDownloader
import com.scsonic.qwenimage21.QwenImage21
import com.scsonic.qwenimage21.QwenImage21Exception
import java.io.File
import java.util.UUID

/**
 * Local Qwen Image 2.1 Turbo editor. No Nightmare service, API token, or web server is involved
 * in inference. Large model files are installed separately in this app's external-files sandbox.
 *
 * Integration is tied to libQwenImage21 v0.4.0 AAR; never silently substitute img2img for edits.
 * All blocking calls MUST be invoked from Dispatchers.IO.
 */
internal object NativeQwenEditor {
    private const val MIN_FREE_BYTES_FOR_NEW_DOWNLOAD = 11_000_000_000L
    @Volatile private var activeDownloader: ModelDownloader? = null

    fun directory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "qwen_image21").apply { mkdirs() }

    fun ready(context: Context): Boolean {
        val dir = directory(context)
        return QwenImage21.missingTurboDitFiles(dir) == null &&
            QwenImage21.missingTinyVaeFiles(dir) == null &&
            QwenImage21.missingEditFiles(dir) == null
    }

    fun status(context: Context): String {
        val dir = directory(context)
        val missing = listOfNotNull(
            QwenImage21.missingTurboDitFiles(dir),
            QwenImage21.missingTinyVaeFiles(dir),
            QwenImage21.missingEditFiles(dir)
        )
        return if (missing.isEmpty()) {
            "Native Qwen Turbo models detected. Ready for an offline image edit."
        } else {
            "Native Qwen models not installed (${missing.joinToString(", ").take(160)}). Download models below (~11 GB)."
        }
    }

    /**
     * SHA/etag verified, partial downloads resumed by the upstream ModelDownloader.
     * IMPORTANT: the Qwen model weights have their own Qwen Research License.
     */
    fun download(context: Context, onProgress: (filename: String, percent: Int) -> Unit) {
        val dir = directory(context)
        if (!ready(context) && dir.walkTopDown().filter { it.isFile }.none()) {
            val free = StatFs(dir.absolutePath).availableBytes
            require(free >= MIN_FREE_BYTES_FOR_NEW_DOWNLOAD) {
                "Not enough free storage. Native Qwen Turbo needs approximately 11 GB available for model files."
            }
        }
        val downloader = ModelDownloader()
        check(activeDownloader == null) { "Qwen download already running." }
        activeDownloader = downloader
        try {
            // Turbo int4, Tiny VAE, and visual encoder: no unnecessary standard DiT download.
            downloader.download(dir, false, true, true, ModelDownloader.Listener { name, done, total ->
                val percent = if (total > 0) ((done * 100.0) / total).toInt().coerceIn(0, 100) else 0
                onProgress(name, percent)
            })
            check(ready(context)) { "Download ended but some required Qwen edit files are still missing." }
        } finally {
            activeDownloader = null
        }
    }

    fun cancelDownload() { activeDownloader?.cancel() }

    /**
     * Actual on-device text-to-image inference using the already bundled Qwen-Image-2.1
     * runtime. This is NOT a request to Nightmare, Local Dream, or a web service.
     * Users install the compatible converted model files once into FreedomAI storage.
     * Run on Dispatchers.IO, never the Android UI thread.
     */
    fun generate(
        context: Context,
        prompt: String,
        speed: String,
        aspect: String,
        onProgress: (Int) -> Unit
    ): Bitmap {
        require(prompt.isNotBlank()) { "Describe the image you want to make." }
        check(ready(context)) {
            "Built-in Qwen Turbo model files are missing. Tap Install AI Model in FreedomAI (one-time download)."
        }
        val size = QwenImage21.Size.of(
            QwenImage21.Size.Ratio.valueOf(StandaloneImageOptions.ratio(aspect)),
            QwenImage21.Size.Tier.valueOf(StandaloneImageOptions.tier(speed))
        )
        val output = File(context.cacheDir, "qwen-generate-${UUID.randomUUID()}.png")
        try {
            val settings = QwenImage21.Options().apply {
                turbo = true  // the installed six-step Turbo model
                tinyVae = true
                useGpu = true
                textEncoderOnCpu = true
                keepModelsLoaded = false // lower peak RAM, at some speed cost
                crashMarkerFile = File(context.filesDir, "qwen-generation-in-progress.txt")
            }
            return QwenImage21(directory(context), settings).use { engine ->
                engine.generate(prompt, size, 6, System.currentTimeMillis().toInt(), output,
                    QwenImage21.ProgressListener { value ->
                        onProgress(value.coerceIn(0, 100))
                    })
            } ?: error("Qwen generation finished without a readable image.")
        } catch (e: QwenImage21Exception) {
            if (e.isOutOfMemory()) {
                throw IllegalStateException(
                    "Not enough memory for built-in image generation. Select FAST for 320px, close other apps, and retry.",
                    e
                )
            }
            throw e
        } finally {
            output.delete()
        }
    }


    /**
     * Uses the supplied bitmap as the actual Qwen edit reference. Turbo is fixed at six DiT steps.
     * The returned image is the model's real result, not a new text-to-image generation.
     */
    fun edit(
        context: Context,
        source: Bitmap,
        instruction: String,
        tier: QwenImage21.Size.Tier,
        onProgress: (Int) -> Unit
    ): Bitmap {
        require(instruction.isNotBlank()) { "Enter an editing instruction." }
        check(ready(context)) { "Install the native Qwen Turbo editing models first." }
        val input = File(context.cacheDir, "qwen-source-${UUID.randomUUID()}.png")
        val output = File(context.cacheDir, "qwen-edit-${UUID.randomUUID()}.png")
        try {
            input.outputStream().use { out ->
                check(source.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "Could not encode the original photo for Qwen editing."
                }
            }
            val settings = QwenImage21.Options().apply {
                turbo = true
                tinyVae = true
                useGpu = true
                textEncoderOnCpu = true
                keepModelsLoaded = false
                crashMarkerFile = File(context.filesDir, "qwen-generation-in-progress.txt")
            }
            val result = QwenImage21(directory(context), settings).use { engine ->
                engine.edit(instruction, input, tier, 6, 42, output,
                    QwenImage21.ProgressListener { percent ->
                        onProgress(percent.coerceIn(0, 100))
                    })
            }
            return result ?: error("Qwen completed without a readable edited image.")
        } catch (e: QwenImage21Exception) {
            if (e.isOutOfMemory()) {
                throw IllegalStateException("Not enough free RAM for this edit. Close other apps or select Tiny (320 px).", e)
            }
            throw e
        } finally {
            input.delete()
            output.delete()
        }
    }
}
