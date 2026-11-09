package com.unboundaistudio

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.scsonic.qwenimage21.QwenImage21
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Import already-downloaded *MNN-converted* Qwen Image 2.1 model files from a
 * user-selected folder. Android app sandboxes cannot read another app's private model data.
 *
 * Only exact model filenames from the upstream runtime are copied. User files are never moved
 * or deleted. A .partial file prevents incomplete imports being mistaken for valid models.
 */
internal object NativeQwenModelImport {
    private val allowed = (
        QwenImage21.SHARED_FILES.toList() +
        QwenImage21.STANDARD_DIT_FILES.toList() +
        QwenImage21.TURBO_DIT_FILES.toList() +
        QwenImage21.TINY_VAE_FILES.toList() +
        QwenImage21.EDIT_FILES.toList()
    ).toSet()

    suspend fun fromFolder(
        context: Context,
        uri: Uri,
        onProgress: (String, Int) -> Unit
    ): Int {
        val folder = DocumentFile.fromTreeUri(context, uri)
            ?: error("Android could not open that folder.")
        require(folder.isDirectory) { "Select a folder containing the Qwen .mnn model files." }
        val matches = linkedMapOf<String, DocumentFile>()
        fun scan(directory: DocumentFile, parent: String, depth: Int) {
            if (depth > 3 || matches.size == allowed.size) return
            for (entry in directory.listFiles()) {
                val name = entry.name ?: continue
                if (entry.isDirectory) {
                    val next = if (name == "text_encoder") "text_encoder" else parent
                    scan(entry, next, depth + 1)
                } else if (entry.isFile) {
                    val relative = if (parent == "text_encoder") "text_encoder/$name" else name
                    if (relative in allowed && !matches.containsKey(relative)) matches[relative] = entry
                }
            }
        }
        scan(folder, "", 0)
        require(matches.isNotEmpty()) {
            "No compatible Qwen Image 2.1 MNN files found. The Nightmare/PyTorch model is a different format."
        }
        val destination = NativeQwenEditor.directory(context)
        var imported = 0
        for ((name, doc) in matches) {
            currentCoroutineContext().ensureActive()
            val target = File(destination, name)
            target.parentFile?.mkdirs()
            val size = doc.length()
            if (target.isFile && size > 0 && target.length() == size) continue
            val part = File(target.parentFile, target.name + ".import-partial")
            try {
                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    part.outputStream().use { output ->
                        val bytes = ByteArray(1024 * 1024)
                        var copied = 0L
                        var previousPercent = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(bytes)
                            if (n < 0) break
                            output.write(bytes, 0, n)
                            copied += n
                            val pct = if (size > 0) (copied * 100 / size).toInt().coerceIn(0, 100) else 0
                            if (pct != previousPercent) {
                                onProgress(name, pct)
                                previousPercent = pct
                            }
                        }
                    }
                } ?: error("Cannot open $name from the chosen folder.")
                check(part.length() > 0 && (size <= 0 || size == part.length())) {
                    "Incomplete model file $name; original preserved."
                }
                if (target.exists() && !target.delete()) error("Cannot replace incomplete $name.")
                check(part.renameTo(target)) { "Could not finish importing $name." }
                imported++
            } finally {
                part.delete()
            }
        }
        return imported
    }
}
