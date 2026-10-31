package com.unboundaistudio

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class LocalDreamClientTest {
 private fun read(text: String) = LocalDreamClient.readStream(ByteArrayInputStream(text.toByteArray())) { _, _ -> }
 @Test fun readsProgressAndRawRgbWithCrLf() {
  var steps = 0
  val stream = "event: progress\r\ndata: {\"type\":\"progress\",\"step\":3,\"total_steps\":20}\r\n\r\nevent: complete\r\ndata: {\"type\":\"complete\",\"width\":2,\"height\":1,\"channels\":3,\"image\":\"/wAAAP8A\"}\r\n\r\n"
  val frame = LocalDreamClient.readStream(ByteArrayInputStream(stream.toByteArray())) { step, total -> steps = step; assertEquals(20, total) }
  assertEquals(3, steps)
  assertEquals(2, frame.width)
  assertArrayEquals(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()), frame.pixels)
 }
 @Test fun readsRgbaAndFinalFrameWithoutBlankLine() {
  val frame = read("data: {\"type\":\"complete\",\"width\":1,\"height\":1,\"channels\":4,\"image\":\"AQIDBA==\"}")
  assertEquals(0x04010203, frame.pixels[0])
 }
 @Test fun reportsBackendError() {
  try { read("data: {\"type\":\"error\",\"message\":\"model unloaded\"}\n\n"); fail("Expected error") }
  catch (e: IllegalStateException) { assertEquals("model unloaded", e.message) }
 }
 @Test fun rejectsTruncatedPixels() {
  try { read("data: {\"type\":\"complete\",\"width\":2,\"height\":1,\"channels\":3,\"image\":\"AQID\"}\n\n"); fail("Expected error") }
  catch (e: IllegalArgumentException) { assertEquals("Incomplete image pixels", e.message) }
 }
 @Test fun rejectsOversizedDimensionsBeforeAllocation() {
  try { read("data: {\"type\":\"complete\",\"width\":999999,\"height\":999999,\"channels\":3,\"image\":\"\"}\n\n"); fail("Expected error") }
  catch (e: IllegalArgumentException) { assertEquals("Unsupported image dimensions", e.message) }
 }
 @Test fun rejectsDisconnectedStream() {
  try { read(": keepalive\n\ndata: {\"type\":\"progress\",\"step\":1,\"total_steps\":20}\n\n"); fail("Expected error") }
  catch (e: IllegalStateException) { assertTrue(e.message!!.contains("ended")) }
 }
}
