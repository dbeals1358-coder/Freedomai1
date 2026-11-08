package com.unboundaistudio

import org.junit.Assert.*
import org.junit.Test

class FictionalAdultPromptTest {
 @Test fun manIsNotOverriddenWithWoman() {
  val prompt = FictionalAdultPrompt.build(30, "Man", "brown hair", "", "", "", "photorealistic", "athletic build")
  assertTrue(prompt.startsWith("Original fictional adult man, age 30"))
  assertFalse(prompt.contains("woman"))
  assertTrue(prompt.contains("athletic build"))
 }
 @Test fun womanAndPersonRemainSupported() {
  assertTrue(FictionalAdultPrompt.build(28, "Woman", "", "", "", "", "", "").contains("adult woman"))
  assertTrue(FictionalAdultPrompt.build(40, "Person", "", "", "", "", "", "").contains("adult person"))
 }
 @Test fun refusesNonAdultAges() {
  try {
   FictionalAdultPrompt.build(17, "Man", "", "", "", "", "", "")
   fail("Expected adult age requirement")
  } catch (_: IllegalArgumentException) { }
 }
}
