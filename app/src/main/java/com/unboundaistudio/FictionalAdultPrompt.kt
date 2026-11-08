package com.unboundaistudio

/**
 * Creates a prompt for original fictional adult subjects.
 * Subject arrangement and anatomical description are separate controls.
 * No real-person inputs are used by this helper.
 */
internal object FictionalAdultPrompt {
 fun build(
  age: Int,
  subject: String,
  appearance: String,
  physique: String,
  pose: String,
  background: String,
  style: String,
  details: String,
  anatomy: String = "Default",
  sceneCount: String = "Single"
 ): String {
  require(age in 18..100) { "Fictional character must be an adult." }
  val count = if (subject == "Both People" && sceneCount == "Single") "Couple" else sceneCount
  val label = when (subject) {
   "Man" -> "man"
   "Woman" -> "woman"
   "Both People" -> "man and woman"
   else -> "person"
  }
  val people = when (count) {
   "Couple" -> when (subject) {
    "Both People" -> "Two original fictional adults, one man and one woman, both age $age, in the same image"
    "Man" -> "Two original fictional adult men, both age $age, in the same image"
    "Woman" -> "Two original fictional adult women, both age $age, in the same image"
    else -> "Two original fictional adult people, both age $age, in the same image"
   }
   "Group" -> when (subject) {
    "Both People" -> "A group of original fictional adults age $age, including at least one man and one woman"
    "Man" -> "A group of original fictional adult men age $age"
    "Woman" -> "A group of original fictional adult women age $age"
    else -> "A group of original fictional adult people age $age"
   }
   else -> "Original fictional adult $label, age $age"
  }
  val anatomyText = when (anatomy) {
   "Male anatomy" -> "Consistent adult male external anatomy and proportions; avoid mistakenly swapping male and female anatomy"
   "Female anatomy" -> "Consistent adult female external anatomy and proportions; avoid mistakenly swapping male and female anatomy"
   "Both anatomies" -> "Fictional adult character with both male and female anatomical characteristics on the same person, depicted coherently"
   else -> when (subject) {
    "Man" -> "Anatomically consistent adult man; do not substitute female genital anatomy"
    "Woman" -> "Anatomically consistent adult woman; do not substitute male genital anatomy"
    "Both People" -> "Keep male and female anatomy correctly associated with the two distinct adults"
    else -> "Natural, anatomically consistent adult proportions"
   }
  }
  return listOf(
   people, anatomyText, appearance, physique, pose, background, style, details
  ).map { it.trim() }.filter { it.isNotBlank() }.joinToString(", ")
 }
}
