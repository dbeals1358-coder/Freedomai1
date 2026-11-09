package com.unboundaistudio

import org.json.JSONObject

/**
 * Retry an on-device image flow without ControlNet when Nightmare says the
 * phone's NPU cannot hold the checkpoint and ControlNet together.
 *
 * This changes the per-request override, not the user's saved Nightmare flow,
 * model, LoRAs, or IP-Adapter setting.
 */
internal object NightmareControlNetRecovery {
    fun isControlNetNpuFailure(message: String): Boolean =
        message.contains("controlnet", ignoreCase = true) &&
            (message.contains("no room", ignoreCase = true) ||
                message.contains("npu", ignoreCase = true) ||
                message.contains("failed init qnn", ignoreCase = true) ||
                message.contains("not enough memory", ignoreCase = true))

    fun disableControlNet(payload: JSONObject) {
        val params = payload.optJSONObject("params") ?: JSONObject()
        val generateParams = params.optJSONObject("generate") ?: JSONObject()
        generateParams.put("controlnet", "none")
        params.put("generate", generateParams)
        payload.put("params", params)
    }
}
