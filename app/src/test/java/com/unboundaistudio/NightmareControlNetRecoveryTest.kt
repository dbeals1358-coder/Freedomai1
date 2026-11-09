package com.unboundaistudio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightmareControlNetRecoveryTest {
    @Test fun detectsReportedNpuMemoryFailure() {
        val error = """{"error":"generate: sample failed http 200. this phone's NPU had no room for the ControlNet beside the model [Failed init QNN model: controlnet]"}"""
        assertTrue(NightmareControlNetRecovery.isControlNetNpuFailure(error))
    }

    @Test fun doesNotRetryForBadTokenOrOtherModelFailure() {
        assertFalse(NightmareControlNetRecovery.isControlNetNpuFailure("401 unauthorized token"))
        assertFalse(NightmareControlNetRecovery.isControlNetNpuFailure("backend did not start"))
        assertFalse(NightmareControlNetRecovery.isControlNetNpuFailure("No model installed"))
    }

    @Test fun disablesControlNetButRetainsExistingOptions() {
        val payload = JSONObject().put("flow", "current")
            .put("prompt", "cartoon dragon")
            .put("width", 1024).put("height", 1024)
            .put("params", JSONObject().put("generate",
                JSONObject().put("ip_adapter", "plus").put("controlnet", "canny")))
        NightmareControlNetRecovery.disableControlNet(payload)
        assertEquals("current", payload.getString("flow"))
        assertEquals("cartoon dragon", payload.getString("prompt"))
        assertEquals(1024, payload.getInt("width"))
        assertEquals("none", payload.getJSONObject("params").getJSONObject("generate").getString("controlnet"))
        assertEquals("plus", payload.getJSONObject("params").getJSONObject("generate").getString("ip_adapter"))
    }
}
