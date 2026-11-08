package com.unboundaistudio

import org.junit.Assert.*
import org.junit.Test

class HorizontalStripeRepairTest {
    @Test fun bandIsFullyRestoredInCenter() {
        assertEquals(1f, HorizontalStripeRepair.blendWeight(50, 45, 55, 5), 0.00001f)
        assertEquals(1f, HorizontalStripeRepair.blendWeight(45, 45, 55, 5), 0.00001f)
        assertEquals(1f, HorizontalStripeRepair.blendWeight(55, 45, 55, 5), 0.00001f)
        assertEquals(0xffc8a080.toInt(), HorizontalStripeRepair.blend(0xff00ff00.toInt(), 0xffc8a080.toInt(), 1f))
    }

    @Test fun rowsOutsideBandStayUntouched() {
        assertEquals(0f, HorizontalStripeRepair.blendWeight(39, 45, 55, 5), 0.00001f)
        assertEquals(0f, HorizontalStripeRepair.blendWeight(61, 45, 55, 5), 0.00001f)
        val edited = 0xff10fe30.toInt()
        assertEquals(edited, HorizontalStripeRepair.blend(edited, 0xffbbaacc.toInt(), 0f))
    }

    @Test fun featherIsSmoothAndPreservesAlpha() {
        assertEquals(0.4f, HorizontalStripeRepair.blendWeight(42, 45, 55, 5), 0.00001f)
        val mixed = HorizontalStripeRepair.blend(0x40102030, 0xc0506070.toInt(), 0.5f)
        assertEquals(0x80304050.toInt(), mixed)
    }

    @Test fun invalidFeatherRejected() {
        try {
            HorizontalStripeRepair.blendWeight(5, 2, 8, 0)
            fail("Expected argument check")
        } catch (_: IllegalArgumentException) { }
    }
}
