package com.unboundaistudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NightmareEditModelPickerTest {
    private fun models(vararg entries: Pair<String, String>): JSONArray =
        JSONArray().apply {
            for ((family, id) in entries) {
                put(JSONObject().put("family", family).put("id", id))
            }
        }

    @Test fun fastPrefersInstalledFlux4BOverQwen() {
        val installed = models("QWEN21" to "qwen-image-2.1",
            "FLUX2" to "flux2-klein-9b", "FLUX2" to "flux2-klein-4b")
        assertEquals("flux2-klein-4b", NightmareEditModelPicker.select(installed, "FAST"))
    }

    @Test fun fastFallsBackToAlreadyInstalledQwen() {
        assertEquals("qwen-turbo", NightmareEditModelPicker.select(
            models("QWEN21" to "qwen-turbo"), "FAST"))
    }

    @Test fun balancedPreservesQwenPreference() {
        val installed = models("FLUX2" to "flux2-klein-4b", "QWEN21" to "qwen-turbo")
        assertEquals("qwen-turbo", NightmareEditModelPicker.select(installed, "BALANCED"))
    }

    @Test fun neverSelectImageOnlyModel() {
        assertNull(NightmareEditModelPicker.select(models("SDXL" to "realvis-xl"), "FAST"))
    }

    @Test fun doesNotConfuse14bWith4b() {
        val installed = models("FLUX2" to "flux2-klein-14b", "FLUX2" to "flux2-klein-4b")
        assertEquals("flux2-klein-4b", NightmareEditModelPicker.select(installed, "FAST"))
    }
}
