package com.unboundaistudio

import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

class NightmareConnectionDiagnosticsTest {
    @Test fun unreachableServerGivesActionableHelp() {
        val text = NightmareConnectionDiagnostics.helpFor(ConnectException("Failed to connect to /127.0.0.1:8820"))
        assertNotNull(text)
        assertTrue(text!!.contains("8820"))
        assertTrue(text.contains("Open Nightmare"))
        assertFalse(text.contains("RealVis"))
    }

    @Test fun wrappedConnectionFailureIsDetected() {
        assertNotNull(NightmareConnectionDiagnostics.helpFor(
            IllegalStateException("request failed", ConnectException("Connection refused"))))
    }

    @Test fun timeoutExplainsServerHealth() {
        assertTrue(NightmareConnectionDiagnostics.helpFor(SocketTimeoutException("Read timed out"))
            ?.contains("did not respond") == true)
    }

    @Test fun ModelErrorIsNotMisdiagnosedAsServerOutage() {
        assertNull(NightmareConnectionDiagnostics.helpFor(
            IllegalStateException("Nightmare HTTP 404: unknown flow")))
    }
}
