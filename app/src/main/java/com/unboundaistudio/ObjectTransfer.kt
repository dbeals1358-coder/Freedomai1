package com.unboundaistudio

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.asImageBitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor

private fun cutObject(source: Bitmap, points: List<Offset>): Bitmap? {
    if (points.size < 3) return null
    val left = floor(points.minOf { it.x } * source.width)
    val top = floor(points.minOf { it.y } * source.height)
    val width = (ceil(points.maxOf { it.x } * source.width) - left).toInt()
    val height = (ceil(points.maxOf { it.y } * source.height) - top).toInt()
    if (width < 2 || height < 2) return null
    val path = Path().apply {
        moveTo(points.first().x * source.width, points.first().y * source.height)
        for (point in points.drop(1)) lineTo(point.x * source.width, point.y * source.height)
        close()
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.translate(-left, -top)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        })
    }
}

private fun objectBounds(cut: Bitmap, width: Float, height: Float, position: Offset, scale: Float): RectF {
    val factor = minOf(width / cut.width, height / cut.height) * scale
    val halfWidth = cut.width * factor / 2
    val halfHeight = cut.height * factor / 2
    val x = position.x * width
    val y = position.y * height
    return RectF(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
}

private fun drawTransferredSubject(canvas: Canvas, piece: Bitmap, bounds: RectF, rotation: Float, brightness: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        if (brightness != 0f) colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, brightness, 0f, 1f, 0f, brightness,
            0f, 0f, 1f, 0f, brightness, 0f, 0f, 0f, 1f, 0f
        )))
    }
    canvas.save()
    canvas.rotate(rotation, bounds.centerX(), bounds.centerY())
    canvas.drawBitmap(piece, null, bounds, paint)
    canvas.restore()
}

// Build a soft local edit mask from the transformed subject's actual alpha.
private fun transferBlendMask(base: Bitmap, piece: Bitmap, position: Offset, scale: Float, rotation: Float): Bitmap {
    val ratio = minOf(1f, 1024f / maxOf(base.width, base.height))
    val w = (base.width * ratio).toInt().coerceAtLeast(1)
    val h = (base.height * ratio).toInt().coerceAtLeast(1)
    val bounds = objectBounds(piece, w.toFloat(), h.toFloat(), position, scale)
    val silhouette = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    drawTransferredSubject(Canvas(silhouette), piece, bounds, rotation, 0f)
    val alpha = silhouette.extractAlpha()
    silhouette.recycle()
    val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(mask)
    canvas.drawColor(android.graphics.Color.BLACK)
    val radius = (minOf(bounds.width(), bounds.height()) * .12f).coerceIn(6f, 48f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = android.graphics.Color.WHITE
    }
    // A small surrounding region includes edges and contact shadows.
    for (y in -2..2) for (x in -2..2) {
        if (x*x + y*y <= 5) canvas.drawBitmap(alpha, x * radius / 2, y * radius / 2, paint)
    }
    canvas.drawBitmap(alpha, 0f, radius, paint)
    alpha.recycle()
    val full = if (w == base.width && h == base.height) mask
        else Bitmap.createScaledBitmap(mask, base.width, base.height, true)
    if (full !== mask) mask.recycle()
    return full
}

@Composable
internal fun ObjectTransferDialog(initialBackground: Bitmap?, onClose: () -> Unit,
    onBlend: suspend (Bitmap, Bitmap) -> Bitmap, onApply: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var background by remember { mutableStateOf(initialBackground) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var cut by remember { mutableStateOf<Bitmap?>(null) }
    val outline = remember { mutableStateListOf<Offset>() }
    var position by remember { mutableStateOf(Offset(.5f, .5f)) }
    var scale by remember { mutableFloatStateOf(.45f) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var detecting by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }
    fun detectSubjects() {
        val photo = source ?: return
        if (detecting) return
        detecting = true
        error = ""
        val options = SubjectSegmenterOptions.Builder()
            .enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableSubjectBitmap().build())
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        try {
            segmenter.process(InputImage.fromBitmap(photo, 0))
                .addOnSuccessListener { result ->
                    if (active && source === photo) {
                        candidates = result.subjects.mapNotNull { it.bitmap }
                        if (candidates.isEmpty()) error = "No subject found, or the Google model is still downloading. Try again or trace manually."
                        else if (candidates.size == 1) cut = candidates.first()
                    }
                }
                .addOnFailureListener {
                    if (active && source === photo) error = "Automatic selection unavailable. Try again or trace manually."
                }
                .addOnCompleteListener {
                    segmenter.close()
                    if (active) detecting = false
                }
        } catch (e: Exception) {
            segmenter.close()
            detecting = false
            error = "Automatic selection unavailable. Trace manually."
        }
    }
    var pickingBackground by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var blending by remember { mutableStateOf(false) }
    var blendJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var blended by remember { mutableStateOf<Bitmap?>(null) }
    var blendSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(blending) {
        if (blending) {
            blendSeconds = 0
            while (true) {
                kotlinx.coroutines.delay(1000)
                blendSeconds++
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val longest = maxOf(info.size.width, info.size.height)
                        if (longest > 4096) decoder.setTargetSize(
                            (info.size.width.toLong() * 4096 / longest).toInt().coerceAtLeast(1),
                            (info.size.height.toLong() * 4096 / longest).toInt().coerceAtLeast(1))
                    }
                }
                if (pickingBackground) background = bitmap else {
                    source = bitmap
                    outline.clear()
                    cut = null
                    candidates = emptyList()
                    position = Offset(.5f, .5f)
                    scale = .45f
                    rotation = 0f
                    brightness = 0f
                }
                error = ""
            } catch (e: Exception) { error = "Couldn’t open this photo. Choose another image." }
            finally { loading = false }
        }
    }
    val placing = cut != null
    val shown = blended ?: if (placing) background else source
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    Dialog(onDismissRequest = { if (!blending) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Add Person or Object", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { if (blending) blendJob?.cancel() else onClose() }) { Text(if (blending) "Stop" else "Cancel") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !loading && !detecting && !blending && blended == null, onClick = { pickingBackground = true; picker.launch("image/*") }) {
                        Text(if (background == null) "Choose background" else "Change background")
                    }
                    OutlinedButton(enabled = !loading && !detecting && !blending && blended == null, onClick = { pickingBackground = false; picker.launch("image/*") }) {
                        Text(if (source == null) "Choose source" else "Change source")
                    }
                }
                Text(when {
                    blending -> "AI blending into the scene… ${blendSeconds}s elapsed"
                    blended != null -> "Review the AI blend before applying it."
                    loading -> "Opening photo…"
                    detecting -> "Finding subjects on your device…"
                    placing && background == null -> "Choose the background photo for your cutout."
                    placing -> "Position the subject. This is only a preview; use AI Blend for the finished photo."
                    source == null -> "Choose the source photo that contains the object."
                    else -> "Tap Auto select, or trace around a person or object with one finger."
                })
                if (!placing && source != null) {
                    Button(enabled = !loading && !detecting && !blending && blended == null, onClick = { detectSubjects() }) { Text("Auto select subject") }
                    if (candidates.isNotEmpty()) {
                        Text("Choose a detected subject")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            candidates.forEachIndexed { index, bitmap ->
                                OutlinedButton(onClick = { cut = bitmap; error = "" }) {
                                    Image(bitmap.asImageBitmap(), "Subject ${index + 1}", Modifier.size(56.dp))
                                }
                            }
                        }
                    }
                }
                if (blending) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (blended != null) {
                        TextButton(onClick = { blended = null }) { Text("Adjust placement") }
                        Button(onClick = { blended?.let(onApply) }) { Text("Use blended photo") }
                    } else {
                    if (placing) TextButton(enabled = !blending, onClick = { cut = null }) { Text("Change subject") }
                    Button(enabled = !loading && !detecting && !blending && (if (placing) background != null else outline.size >= 3), onClick = {
                        val piece = cut
                        val base = background
                        if (piece == null) {
                            cut = source?.let { cutObject(it, outline) }
                            if (cut == null) error = "Trace a larger area around the object."
                        } else if (base != null) {
                            val pos = position
                            val sz = scale
                            val angle = rotation
                            val light = brightness
                            blending = true
                            error = ""
                            blendJob = scope.launch {
                                var composite: Bitmap? = null
                                var mask: Bitmap? = null
                                try {
                                    val prepared = withContext(Dispatchers.Default) {
                                        val img = base.copy(Bitmap.Config.ARGB_8888, true)
                                        drawTransferredSubject(Canvas(img), piece,
                                            objectBounds(piece, base.width.toFloat(), base.height.toFloat(), pos, sz), angle, light)
                                        img to transferBlendMask(base, piece, pos, sz, angle)
                                    }
                                    composite = prepared.first
                                    mask = prepared.second
                                    blended = onBlend(prepared.first, prepared.second)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    error = "Blend cancelled. Placement preserved."
                                    throw e
                                } catch (e: OutOfMemoryError) {
                                    error = "Not enough memory to blend this photo. Try smaller images."
                                } catch (e: Exception) {
                                    error = "Blend failed: " + (e.message ?: "Unknown error")
                                } finally {
                                    composite?.recycle()
                                    mask?.recycle()
                                    blending = false
                                }
                            }
                        }
                    }) { Text(if (placing) "AI Blend into photo" else "Use cutout") }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    val photo = shown
                    if (photo != null) {
                        val ratio = photo.width.toFloat() / photo.height
                        val w = minOf(maxWidth, maxHeight * ratio)
                        androidx.compose.foundation.Canvas(Modifier.size(w, w / ratio).pointerInput(photo, placing, blending, blended) {
                            if (blending || blended != null) return@pointerInput
                            fun normalized(p: Offset) = Offset((p.x / size.width).coerceIn(0f, 1f), (p.y / size.height).coerceIn(0f, 1f))
                            detectDragGestures(onDragStart = { if (!placing) { outline.clear(); outline.add(normalized(it)); error = "" } },
                                onDrag = { change, delta ->
                                    change.consume()
                                    if (placing) position = Offset((position.x + delta.x / size.width).coerceIn(0f, 1f),
                                        (position.y + delta.y / size.height).coerceIn(0f, 1f))
                                    else outline.add(normalized(change.position))
                                })
                        }) {
                            val canvas = drawContext.canvas.nativeCanvas
                            canvas.drawBitmap(photo, null, RectF(0f, 0f, size.width, size.height), paint)
                            val piece = cut
                            if (piece != null && blended == null) {
                                canvas.save()
                                canvas.clipRect(0f, 0f, size.width, size.height)
                                drawTransferredSubject(canvas, piece, objectBounds(piece, size.width, size.height, position, scale), rotation, brightness)
                                canvas.restore()
                            } else if (blended == null && outline.size > 1) {
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(outline[0].x * size.width, outline[0].y * size.height)
                                    for (i in 1 until outline.size) lineTo(outline[i].x * size.width, outline[i].y * size.height)
                                    close()
                                }
                                drawPath(path, Color.Black, style = Stroke(5.dp.toPx()))
                                drawPath(path, Color.White, style = Stroke(2.dp.toPx()))
                            }
                        }
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (placing && blended == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Size")
                        Slider(scale, { scale = it }, modifier = Modifier.weight(1f), enabled = !blending, valueRange = .03f..2f)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Angle")
                        Slider(rotation, { rotation = it }, modifier = Modifier.weight(1f), enabled = !blending, valueRange = -180f..180f)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Lighting")
                        Slider(brightness, { brightness = it }, modifier = Modifier.weight(1f), enabled = !blending, valueRange = -80f..80f)
                    }
                }

            }
        }
    }
}
