package com.unboundaistudio

/**
 * Makes the requested *physical action* the highest priority for image-to-video.
 * A moving virtual camera is not a substitute for the people/objects moving.
 */
internal object VideoMotionPrompt {
 fun build(description: String): String {
  require(description.isNotBlank()) { "Describe the action to animate." }
  return listOf(
   description.trim(),
   "Image-to-video animation of the exact source subjects and scene.",
   "The people, animals or objects visibly perform the requested action through" +
    " changing poses and positions across consecutive frames; real articulated" +
    " subject motion, not merely moving the camera over a still photograph.",
   "Maintain a coherent face, identity, body shape and background from frame to frame.",
   "Keep edges defined, natural colors and consistent lighting. Clear temporally" +
    " consistent frames, with no random noise, colored static, flicker or horizontal bands.",
   "Do not replace the action with a slideshow, still frame, zoom, pan or parallax effect."
  ).joinToString(" ")
 }
}
