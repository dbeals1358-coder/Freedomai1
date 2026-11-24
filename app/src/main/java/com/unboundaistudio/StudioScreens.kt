package com.unboundaistudio

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
private val accentPink = Color(0xFFF02D91)

internal enum class StudioTool(val label: String) {
    EDIT("Image Edit"), VIDEO("Image to Video"), TEXT("Text to Image")
}

// The images are bundled with the app, so the gallery scrolls without a network
// connection. Each card opens Text to Image with an editable starter prompt.
private data class StudioPreset(
    val title: String,
    val category: String,
    val image: Int,
    val prompt: String,
    val tall: Boolean = false
)

private val studioPresets = listOf(
    StudioPreset("Tender Kiss", "Romance", R.drawable.studio_scene_0,
        "Two adults sharing a tender kiss in warm afternoon light, natural skin detail, realistic photography.", true),
    StudioPreset("Sunlit Portrait", "Portraits", R.drawable.studio_scene_1,
        "A smiling adult painter in a sunlit studio, detailed photorealistic portrait."),
    StudioPreset("Desert Mystery", "Fantasy", R.drawable.studio_scene_2,
        "An ancient desert mummy awakening among windswept ruins, cinematic fantasy lighting."),
    StudioPreset("Ballet Dancer", "Animation", R.drawable.studio_scene_3,
        "An anime ballet dancer practicing in an airy studio, beautiful hand-drawn animation style.", true),
    StudioPreset("Golden Hour", "Cinematic", R.drawable.studio_scene_4,
        "A golden hourglass suspended in a starlit sky, dramatic cinematic lighting.", true),
    StudioPreset("Android Awakens", "Fantasy", R.drawable.studio_scene_5,
        "A silver android awakening inside a futuristic hangar, science fiction concept art."),
    StudioPreset("Soft Romance", "Romance", R.drawable.studio_scene_0,
        "An adult couple enjoying a quiet romantic moment at sunset, natural candid photography."),
    StudioPreset("Editorial Portrait", "Portraits", R.drawable.studio_scene_1,
        "An adult artist posing for an editorial portrait in a bright studio, realistic skin and eyes.", true),
    StudioPreset("Ancient Legends", "Fantasy", R.drawable.studio_scene_2,
        "A mysterious desert explorer discovering ancient ruins at sunrise, richly detailed fantasy scene.", true),
    StudioPreset("Anime Elegance", "Animation", R.drawable.studio_scene_3,
        "An elegant anime character practicing ballet in a sunlit studio, refined linework."),
    StudioPreset("Film Noir", "Cinematic", R.drawable.studio_scene_4,
        "An antique hourglass in a shadowy film noir scene, high-contrast cinematic photography."),
    StudioPreset("Sci-Fi Hero", "Fantasy", R.drawable.studio_scene_5,
        "A highly detailed humanoid robot in a futuristic hangar, dramatic science fiction movie still.", true),
    StudioPreset("Romantic Close-Up", "Romance", R.drawable.studio_scene_0,
        "A close-up photograph of two adults smiling together in soft natural light.", true),
    StudioPreset("Creative Studio", "Portraits", R.drawable.studio_scene_1,
        "An adult painter working beside an easel in a bright art studio, cinematic depth of field."),
    StudioPreset("Fantasy Ruins", "Fantasy", R.drawable.studio_scene_2,
        "An ancient fantasy temple surrounded by golden sand and drifting mist, epic environment art."),
    StudioPreset("Animated Dance", "Animation", R.drawable.studio_scene_3,
        "A graceful animated ballet dancer moving across a sunlit stage, expressive character design.", true),
    StudioPreset("Dreamlike Light", "Cinematic", R.drawable.studio_scene_4,
        "A glowing hourglass against a dreamlike night sky, cinematic color grading.", true),
    StudioPreset("Future World", "Cinematic", R.drawable.studio_scene_5,
        "A futuristic robot in a large illuminated space station, photoreal science fiction production design.")
)

@Composable
private fun StudioPresetCard(item: StudioPreset, open: (StudioTool, String?) -> Unit) {
    val launch = { open(StudioTool.TEXT, item.prompt) }
    Box(
        Modifier.fillMaxWidth()
            .height(if (item.tall) 248.dp else 200.dp)
            .clip(curve)
            .background(tile)
            .clickable(onClick = launch)
    ) {
        Image(painterResource(item.image), contentDescription = item.title,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            colors = listOf(Color.Black.copy(alpha = 0.08f), Color.Transparent,
                Color.Black.copy(alpha = 0.89f)))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.title, color = Color.White, fontSize = 17.sp,
                style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = launch,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = accentPink),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text("Generate", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

@Composable
internal fun StudioHome(open: (StudioTool, String?) -> Unit, more: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = more) { Text("☰", color = Color.White, fontSize = 22.sp) }
        Text("Titties", Modifier.weight(1f), color = Color.White,
            style = MaterialTheme.typography.headlineMedium)
        Text("V1.69", color = studioOrange)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioTool.entries.forEachIndexed { index, tool ->
            Column(Modifier.weight(1f).clip(curve)
                .background(Brush.linearGradient(
                    listOf(Color(0xFF51436A), Color(0xFF24113C))))
                .clickable { open(tool, null) }
                .height(98.dp).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(listOf("▧", "▷", "✧")[index], color = Color.White, fontSize = 28.sp)
                Text(tool.label, color = Color.White, fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }

    var selectedCategory by rememberSaveable { mutableStateOf("All") }
    val categories = listOf("All", "Portraits", "Romance", "Fantasy", "Animation", "Cinematic")
    Text("Explore & Generate", color = Color.White,
        style = MaterialTheme.typography.titleLarge)
    Text("Scroll to browse more ideas. Tap any card to edit its prompt and generate.",
        color = Color.LightGray, fontSize = 13.sp)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.forEach { category ->
            FilterChip(
                selected = selectedCategory == category,
                onClick = { selectedCategory = category },
                label = { Text(category) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accentPink,
                    selectedLabelColor = Color.White,
                    labelColor = Color.White)
            )
        }
    }
    val filtered = remember(selectedCategory) {
        if (selectedCategory == "All") studioPresets
        else studioPresets.filter { it.category == selectedCategory }
    }
    // The host screen owns vertical scrolling. A pair of independent columns gives
    // the alternating-height, two-column gallery shown in the reference without
    // nesting another vertical scroll container.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            filtered.forEachIndexed { index, preset ->
                if (index % 2 == 0) StudioPresetCard(preset, open)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            filtered.forEachIndexed { index, preset ->
                if (index % 2 == 1) StudioPresetCard(preset, open)
            }
        }
    }
    Text("More styles", color = Color.White, style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            "Realistic photo" to "Photorealistic natural lighting, high detail, lifelike textures.",
            "Anime" to "Hand-drawn anime illustration, expressive characters, crisp clean lines.",
            "Comic book" to "Detailed comic-book illustration with bold ink lines and colors.",
            "Cinematic" to "Cinematic movie still, dramatic light and shallow depth of field.",
            "Fantasy" to "High-detail fantasy concept art with imaginative environment."
        ).forEach { (name, description) ->
            OutlinedButton(
                onClick = { open(StudioTool.TEXT, description) },
                shape = RoundedCornerShape(14.dp)
            ) { Text(name) }
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
