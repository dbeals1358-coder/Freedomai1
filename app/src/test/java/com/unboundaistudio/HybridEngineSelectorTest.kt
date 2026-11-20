package com.unboundaistudio

import org.junit.Assert.assertEquals
import org.junit.Test

class HybridEngineSelectorTest {
    private val local = HybridEngineSelector.Engine(
        "local", HybridEngineSelector.Location.DEVICE,
        setOf(HybridEngineSelector.Task.TEXT_IMAGE), true
    )
    private val remote = HybridEngineSelector.Engine(
        "remote", HybridEngineSelector.Location.NETWORK,
        setOf(HybridEngineSelector.Task.TEXT_IMAGE), true
    )
    @Test fun autoPrefersLocalThenRemote() {
        val request = HybridEngineSelector.Request(
            HybridEngineSelector.Mode.AUTO, HybridEngineSelector.Task.TEXT_IMAGE, true
        )
        assertEquals(listOf(local, remote), HybridEngineSelector.candidates(request, listOf(remote, local)))
    }
    @Test fun offlineNeverUsesNetwork() {
        val request = HybridEngineSelector.Request(
            HybridEngineSelector.Mode.OFFLINE, HybridEngineSelector.Task.TEXT_IMAGE, true
        )
        assertEquals(listOf(local), HybridEngineSelector.candidates(request, listOf(remote, local)))
    }
    @Test fun noInternetPreventsRemoteFallback() {
        val request = HybridEngineSelector.Request(
            HybridEngineSelector.Mode.AUTO, HybridEngineSelector.Task.TEXT_IMAGE, false
        )
        assertEquals(listOf(local), HybridEngineSelector.candidates(request, listOf(remote, local)))
    }
    @Test fun paidServiceRequiresApproval() {
        val paid = remote.copy(requiresPaidService = true)
        val request = HybridEngineSelector.Request(
            HybridEngineSelector.Mode.ONLINE, HybridEngineSelector.Task.TEXT_IMAGE, true
        )
        assertEquals(emptyList<HybridEngineSelector.Engine>(), HybridEngineSelector.candidates(request, listOf(paid)))
    }
    @Test fun motionPromptRequiresCapableEngine() {
        val request = HybridEngineSelector.Request(
            HybridEngineSelector.Mode.AUTO, HybridEngineSelector.Task.IMAGE_VIDEO, true,
            needsPromptedMotion = true
        )
        val unsupported = local.copy(tasks = setOf(HybridEngineSelector.Task.IMAGE_VIDEO))
        val supported = remote.copy(tasks = setOf(HybridEngineSelector.Task.IMAGE_VIDEO), supportsPromptedMotion = true)
        assertEquals(listOf(supported), HybridEngineSelector.candidates(request, listOf(unsupported, supported)))
    }
}
