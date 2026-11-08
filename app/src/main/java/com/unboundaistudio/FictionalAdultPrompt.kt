package com.unboundaistudio

/** Creates a neutral, user-selectable fictional adult subject prompt. */
internal object FictionalAdultPrompt {
 fun build(
  age: Int,
  subject: String,
  appearance: String,
  physique: String,
  pose: String,
  background: String,
  style: String,
  details: String
 ): String {
  require(age in 18..100) { "Fictional character must be an adult." }
  val label = when (subject) {
   "Man" -> "man"
   "Woman" -> "woman"
   else -> "person"
  }
  return listOf(
   "Original fictional adult $label, age $age",
   appearance, physique, pose, background, style, details
  ).map { it.trim() }.filter { it.isNotBlank() }.joinToString(", ")
 }
}
