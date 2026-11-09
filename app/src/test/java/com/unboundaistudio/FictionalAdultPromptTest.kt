package com.unboundaistudio

import org.junit.Assert.*
import org.junit.Test

class FictionalAdultPromptTest {
 @Test fun switchingSubjectClearsStaleAnatomySelection() {
  assertEquals("Default", FictionalAdultPrompt.anatomyAfterSubjectChange("Woman", "Man", "Female anatomy"))
  assertEquals("Default", FictionalAdultPrompt.anatomyAfterSubjectChange("Man", "Woman", "Male anatomy"))
  assertEquals("Default", FictionalAdultPrompt.anatomyAfterSubjectChange("Man", "Both People", "Both anatomies"))
  assertEquals("Male anatomy", FictionalAdultPrompt.anatomyAfterSubjectChange("Man", "Man", "Male anatomy"))
 }
 @Test fun manIsNotOverriddenWithWoman() {
  val prompt = FictionalAdultPrompt.build(30, "Man", "brown hair", "", "", "", "photorealistic", "athletic build")
  assertTrue(prompt.startsWith("Original fictional adult man, age 30"))
  assertFalse(prompt.contains("Original fictional adult woman"))
  assertTrue(prompt.contains("athletic build"))
  assertTrue(prompt.contains("do not substitute female genital anatomy"))
 }
 @Test fun womanAndPersonRemainSupported() {
  assertTrue(FictionalAdultPrompt.build(28, "Woman", "", "", "", "", "", "").contains("adult woman"))
  assertTrue(FictionalAdultPrompt.build(40, "Person", "", "", "", "", "", "").contains("adult person"))
 }
 @Test fun bothPeopleMeansTwoDistinctAdults() {
  val result = FictionalAdultPrompt.build(32, "Both People", "", "", "", "", "", "", "Default", "Couple")
  assertTrue(result.contains("one man and one woman"))
  assertTrue(result.contains("two distinct adults"))
  assertFalse(result.contains("character with both"))
 }
 @Test fun bothAnatomiesMeansOneCharacterNotAutomaticallyTwoPeople() {
  val result = FictionalAdultPrompt.build(35, "Person", "", "", "", "", "", "", "Both anatomies", "Single")
  assertTrue(result.contains("on the same person"))
  assertTrue(result.startsWith("Original fictional adult person"))
 }
 @Test fun bothPeopleNeverDowngradesToSinglePerson() {
  val result = FictionalAdultPrompt.build(30, "Both People", "", "", "", "", "", "", "Default", "Single")
  assertTrue(result.startsWith("Two original fictional adults"))
 }
 @Test fun compositionOptions() {
  val group = FictionalAdultPrompt.build(45, "Both People", "", "", "", "", "", "", "Default", "Group")
  assertTrue(group.contains("at least one man and one woman"))
  val pair = FictionalAdultPrompt.build(25, "Man", "", "", "", "", "", "", "Default", "Couple")
  assertTrue(pair.contains("Two original fictional adult men"))
 }
 @Test fun explicitAnatomyControls() {
  val male = FictionalAdultPrompt.build(38, "Man", "", "", "", "", "", "", "Male anatomy")
  assertTrue(male.contains("male external anatomy"))
  val female = FictionalAdultPrompt.build(38, "Woman", "", "", "", "", "", "", "Female anatomy")
  assertTrue(female.contains("female external anatomy"))
 }
 @Test fun refusesNonAdultAges() {
  try {
   FictionalAdultPrompt.build(17, "Man", "", "", "", "", "", "")
   fail("Expected adult age requirement")
  } catch (_: IllegalArgumentException) { }
 }
}
