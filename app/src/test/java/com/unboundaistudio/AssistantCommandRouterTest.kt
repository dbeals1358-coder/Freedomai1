package com.unboundaistudio

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantCommandRouterTest {
 @Test fun routesCreativeActions() {
  assertEquals(AssistantCommand.CREATE_IMAGE, AssistantCommandRouter.classify("Make a realistic portrait of a tiger"))
  assertEquals(AssistantCommand.CREATE_IMAGE, AssistantCommandRouter.classify("Draw a cartoon astronaut"))
  assertEquals(AssistantCommand.EDIT_IMAGE, AssistantCommandRouter.classify("Change the shirt color in this photo"))
  assertEquals(AssistantCommand.EDIT_IMAGE, AssistantCommandRouter.classify("Remove background"))
  assertEquals(AssistantCommand.CREATE_VIDEO, AssistantCommandRouter.classify("Animate this photo into a video"))
  assertEquals(AssistantCommand.CREATE_VIDEO, AssistantCommandRouter.classify("Create a video from my photo"))
 }
 @Test fun searchesAreNotGeneration() {
  assertEquals(AssistantCommand.SEARCH_IMAGES, AssistantCommandRouter.classify("Search the web for waterfall images"))
  assertEquals(AssistantCommand.SEARCH_IMAGES, AssistantCommandRouter.classify("Find photos of the moon"))
  assertEquals(AssistantCommand.SEARCH_WEB, AssistantCommandRouter.classify("Search the internet for Android news"))
 }
 @Test fun questionsStayInChat() {
  assertEquals(AssistantCommand.CHAT, AssistantCommandRouter.classify("How can I make my videos better?"))
  assertEquals(AssistantCommand.CHAT, AssistantCommandRouter.classify("What image formats are available?"))
  assertEquals(AssistantCommand.CHAT, AssistantCommandRouter.classify("Can you explain photo editing?"))
  assertEquals(AssistantCommand.CHAT, AssistantCommandRouter.classify("Help me create a story"))
 }
}
