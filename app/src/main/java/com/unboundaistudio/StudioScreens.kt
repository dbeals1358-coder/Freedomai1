package com.unboundaistudio

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val studioOrange = Color(0xFFFF975F)
private val tile = Color(0xFF202431)
private val curve = RoundedCornerShape(16.dp)
internal enum class StudioTool(val label: String) {
    EDIT("Image Edit"), VIDEO("Image to Video"), TEXT("Text to Image")
}

@Composable
internal fun StudioHome(open: (StudioTool, String?) -> Unit, more: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = more) { Text("☰", color = Color.White, fontSize = 22.sp) }
        Text("Titties", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.headlineMedium)
        Text("V1.69", color = studioOrange)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioTool.entries.forEachIndexed { index, tool ->
            Column(Modifier.weight(1f).clip(curve).background(Brush.linearGradient(
                listOf(Color(0xFF51436A), Color(0xFF24113C)))).clickable { open(tool, null) }
                .height(98.dp).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(listOf("▧", "▷", "✧")[index], color = Color.White, fontSize = 28.sp)
                Text(tool.label, color = Color.White, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
    val photos = listOf(R.drawable.studio_scene_0, R.drawable.studio_scene_1, R.drawable.studio_scene_2,
        R.drawable.studio_scene_3, R.drawable.studio_scene_4, R.drawable.studio_scene_5)
    val names = listOf("Tender Kiss", "Smiling Painter", "Desert Mummy", "Ballet Dancer", "Golden Hour", "Android Awakens")
    val prompts = listOf("Two adults sharing a tender kiss in warm afternoon light.",
        "A smiling painter at an easel in a sunlit studio.", "A desert mummy waking among windswept ruins.",
        "An anime ballet dancer practicing in an airy studio.", "A golden hourglass suspended in a starlit sky.",
        "A silver android awakening inside a futuristic hangar.")
    for ((row, title) in listOf("Hot 🔥", "Cinematic").withIndex()) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (index in row * 3 until row * 3 + 3) {
                Box(Modifier.size(120.dp, 156.dp).clip(curve).clickable { open(StudioTool.TEXT, prompts[index]) }) {
                    Image(painterResource(photos[index]), names[index], Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Text(names[index], Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Color.Black.copy(alpha = .78f)).padding(6.dp), color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
internal fun StudioOptions(label: String, values: List<String>, value: String, enabled: Boolean, choose: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.widthIn(min = 60.dp))
        for (option in values) {
            val active = option == value
            Text(option, Modifier.clip(RoundedCornerShape(8.dp)).background(if (active) studioOrange else tile)
                .clickable(enabled = enabled) { choose(option) }.padding(horizontal = 12.dp, vertical = 8.dp),
                color = if (active) Color.Black else Color.White, fontSize = 14.sp)
        }
    }
}

@Composable
internal fun StudioReference(label: String, bitmap: Bitmap?, enabled: Boolean, select: () -> Unit) {
    Box(Modifier.size(124.dp).clip(curve).background(tile).clickable(enabled = enabled, onClick = select),
        contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("+", color = studioOrange, fontSize = 28.sp)
            Text(label, fontSize = 13.sp)
        }
    }
}

internal fun studioDimensions(edge: Int, ratio: String): Pair<Int, Int> {
    val (w, h) = when (ratio) {
        "3:4" -> 3 to 4
        "4:3" -> 4 to 3
        "9:16" -> 9 to 16
        else -> 1 to 1
    }
    return (edge * w / maxOf(w,h) / 8 * 8) to (edge * h / maxOf(w,h) / 8 * 8)
}

internal fun referencePair(first: Bitmap, second: Bitmap): Bitmap {
    val height = minOf(1024, maxOf(first.height, second.height))
    val sheet = Bitmap.createBitmap(2 * height, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(sheet)
    canvas.drawColor(android.graphics.Color.DKGRAY)
    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    listOf(first, second).forEachIndexed { i, bitmap ->
        val scale = height.toFloat() / maxOf(bitmap.width, bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = i * height + (height - w) / 2
        val top = (height - h) / 2
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + w, top + h), paint)
    }
    return sheet
}
