package com.unboundaistudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NightmareImageFlowPlannerTest {
    @Test fun customFlowUsesConnectedPhotoNodeInsteadOfHardCodedImage() {
        val flow = JSONObject("""
            {"nodes":[
                {"id":"user_photo","type":"core.image"},
                {"id":"reference","type":"core.image"},
                {"id":"instruction","type":"core.prompt"},
                {"id":"render","type":"core.generate","inputs":{"image":"user_photo","reference":"reference"}}
            ]}
        """.trimIndent())
        assertEquals(setOf("user_photo"), NightmareImageFlowPlanner.connectedImageNodeIds(flow))
    }

    @Test fun builtInImageNodeStillWorks() {
        val flow = JSONObject("""{"nodes":[{"id":"image","type":"core.image"},
            {"id":"render","type":"image.edit","inputs":{"image":"image"}}]}""")
        assertEquals(setOf("image"), NightmareImageFlowPlanner.connectedImageNodeIds(flow))
    }

    @Test fun textOnlyFlowIsNeverSentAnUploadedPhoto() {
        val flow = JSONObject("""{"nodes":[{"id":"prompt","type":"core.prompt"},
            {"id":"render","type":"image.generate","inputs":{"prompt":"prompt"}}]}""")
        val failure = runCatching { NightmareImageFlowPlanner.connectedImageNodeIds(flow) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("no Image node"))
    }

    @Test fun unconnectedImageNodeIsNotSilentlyUsed() {
        val flow = JSONObject("""{"nodes":[{"id":"photo","type":"core.image"},
            {"id":"render","type":"image.generate","inputs":{"prompt":"prompt"}}]}""")
        val failure = runCatching { NightmareImageFlowPlanner.connectedImageNodeIds(flow) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("not connected"))
    }

    @Test fun detectsMissingNodeResponseOnlyForMatchingHttpError() {
        val body = """{"error":"images: no node \"image\" in this flow"}"""
        assertTrue(NightmareImageFlowPlanner.missingImageNode(400, body))
        assertFalse(NightmareImageFlowPlanner.missingImageNode(500, body))
        assertFalse(NightmareImageFlowPlanner.missingImageNode(400, "model not installed"))
    }

    @Test fun readsGraphWrappedNodeSchema() {
        val flow = JSONObject("""{"graph":{"nodes":[{"id":"inputA","type":"core.image"},
            {"id":"edit","type":"core.generate","inputs":{"image":"inputA"}}]}}""")
        assertEquals(setOf("inputA"), NightmareImageFlowPlanner.connectedImageNodeIds(flow))
    }
}
