package com.unboundaistudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightmareCatalogTest {
    @Test fun modelsUseInstalledIdentifiersNotDisplayNames() {
        val result = NightmareCatalog.models("""{"models":[{"id":"realvisxl","name":"RealVis XL"},{"model_id":"retro_2d","name":"Retro 2D"}]}""")
        assertEquals(listOf("realvisxl", "retro_2d"), result.map { it.id })
        assertEquals("RealVis XL", result[0].label)
    }

    @Test fun modelCatalogCanBePlainArray() {
        assertEquals(listOf("pony", "realvis"), NightmareCatalog.models("""["pony","realvis"]""").map { it.id })
    }

    @Test fun flowCatalogSupportsNestedLists() {
        val items = NightmareCatalog.flows("""{"flows":[{"id":"My Image Flow","name":"Image"},{"id":"Video Render"}]}""")
        assertTrue(items.contains("My Image Flow"))
        assertTrue(items.contains("Video Render"))
    }

    @Test fun flowCatalogSupportsNamedMaps() {
        val items = NightmareCatalog.flows("""{"saved":{"Portrait":{"nodes":[]},"Motion":{"widgets":[]}}}""")
        assertEquals(listOf("Portrait", "Motion").toSet(), items.toSet())
    }
}
