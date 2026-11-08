package com.unboundaistudio

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/**
 * Distinguish an unreachable on-device Nightmare API from a bad model or flow.
 * Avoid presenting RealVis/model repair steps when no server accepted the request.
 */
internal object NightmareConnectionDiagnostics {
    fun helpFor(error: Throwable): String? {
        var cause: Throwable? = error
        repeat(6) {
            when (cause) {
                is ConnectException, is NoRouteToHostException ->
                    return "Nightmare is not reachable on this phone (127.0.0.1:8820). Open Nightmare, enable its API in Advanced settings and start the server. Then return to FreedomAI and tap Check Nightmare connection."
                is SocketTimeoutException ->
                    return "Nightmare on port 8820 did not respond in time. Open Nightmare and verify its API server is running and responsive before trying again."
            }
            cause = cause?.cause ?: return null
        }
        return null
    }
}
