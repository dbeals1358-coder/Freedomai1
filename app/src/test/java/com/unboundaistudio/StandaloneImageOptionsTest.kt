package com.unboundaistudio

import org.junit.Assert.assertEquals
import org.junit.Test

class StandaloneImageOptionsTest {
    @Test fun speedUsesNativeSizeTiersRatherThanUnsupported1024px() {
        assertEquals("TINY", StandaloneImageOptions.tier("FAST"))
        assertEquals("FAST", StandaloneImageOptions.tier("BALANCED"))
        assertEquals("STANDARD", StandaloneImageOptions.tier("MAX QUALITY"))
        assertEquals("FAST", StandaloneImageOptions.tier("unknown"))
    }
    @Test fun supportsSquareAndPortraitLandscapeOnDevice() {
        assertEquals("SQUARE", StandaloneImageOptions.ratio("Square"))
        assertEquals("PORTRAIT_3_4", StandaloneImageOptions.ratio("Portrait"))
        assertEquals("LANDSCAPE_4_3", StandaloneImageOptions.ratio("Landscape"))
    }
}
