package io.github.sevgjan.watcher

import io.github.sevgjan.watcher.engine.Bridge
import io.github.sevgjan.watcher.engine.parseDensity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DensityTest {
    @Test
    fun readsOverride() {
        assertEquals(Bridge.Density(420, 300), parseDensity("Physical density: 420\nOverride density: 300\n"))
        assertEquals(300, parseDensity("Physical density: 420\nOverride density: 300\n")!!.current)
    }

    @Test
    fun readsNoOverride() {
        assertEquals(Bridge.Density(420, null), parseDensity("Physical density: 420\n"))
        assertEquals(420, parseDensity("Physical density: 420\n")!!.current)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(parseDensity("error"))
    }
}
