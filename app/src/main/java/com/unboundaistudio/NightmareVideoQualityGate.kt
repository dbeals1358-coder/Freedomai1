package com.unboundaistudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Validates a downloaded Nightmare MP4 before it enters the user's Gallery.
 * A valid MP4 container can still contain unusable colored noise from Wan's
 * model/decoder. This check detects that failure without altering video frames.
 */
internal object NightmareVideoQualityGate {
    fun requireUsable(context: Context, mp4: ByteArray) {
        check(mp4.isNotEmpty()) { "Nightmare returned an empty MP4" }
        val temp = File.createTempFile("freedomai_video_quality_", ".mp4", context.cacheDir)
        val retriever = MediaMetadataRetriever()
        try {
            temp.writeBytes(mp4)
            retriever.setDataSource(temp.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: error("Nightmare output has no readable video duration")
            require(durationMs >= 300L) { "Nightmare output contains no usable video frames" }
            // Earlier samples catch a first photograph followed by colored static;
            // later samples catch a collapse partway through the clip.
            val fractions = doubleArrayOf(0.0, 0.05, 0.10, 0.15, 0.20, 0.35, 0.60)
            val samples = fractions.map { fraction ->
                val timeUs = (durationMs * 1000.0 * fraction).toLong()
                val frame = retriever.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 64, 64
                ) ?: error("Nightmare MP4 could not decode its video frames")
                try {
                    val scaled = if (frame.width == 64 && frame.height == 64) frame
                        else Bitmap.createScaledBitmap(frame, 64, 64, true)
                    try {
                        val pixels = IntArray(64 * 64)
                        scaled.getPixels(pixels, 0, 64, 0, 0, 64, 64)
                        IntArray(pixels.size) { index ->
                            val pixel = pixels[index]
                            (77 * Color.red(pixel) + 150 * Color.green(pixel) +
                                29 * Color.blue(pixel)) shr 8
                        }
                    } finally {
                        if (scaled !== frame) scaled.recycle()
                    }
                } finally {
                    frame.recycle()
                }
            }
            val result = VideoFrameContinuity.assess(samples)
            check(!result.likelyCorrupted) {
                "Nightmare returned corrupted video frames (colored static instead of movement). " +
                "MP4 was not saved. In Nightmare open Settings > Performance > Memory, turn ON " +
                "'Video: stream the model', then try a 2-second clip. If this continues, " +
                "the installed Wan video model may not support your phone's Snapdragon chip."
            }
        } finally {
            retriever.release()
            temp.delete()
        }
    }
}
