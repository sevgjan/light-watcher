package io.github.sevgjan.watcher

import io.github.sevgjan.watcher.engine.parseOpAllowed
import io.github.sevgjan.watcher.engine.parseOpPerPackage
import io.github.sevgjan.watcher.engine.parsePackages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClockOnlyTest {
    @Test
    fun readsTheUsageAccessMode() {
        assertTrue(parseOpAllowed("GET_USAGE_STATS: allow; time=+2m3s ago\n", "GET_USAGE_STATS"))
        assertTrue(parseOpAllowed("Uid mode: GET_USAGE_STATS: allow\n", "GET_USAGE_STATS"))
        assertFalse(parseOpAllowed("GET_USAGE_STATS: default\n", "GET_USAGE_STATS"))
        assertFalse(parseOpAllowed("GET_USAGE_STATS: ignore\n", "GET_USAGE_STATS"))
        assertFalse(parseOpAllowed("No operations.\n", "GET_USAGE_STATS"))
        // Another op being allowed doesn't count.
        assertFalse(parseOpAllowed("SYSTEM_ALERT_WINDOW: allow\n", "GET_USAGE_STATS"))
        // A longer op name that ends the same way isn't this op.
        assertFalse(parseOpAllowed("MY_GET_USAGE_STATS: allow\n", "GET_USAGE_STATS"))
    }

    @Test
    fun readsInstalledPackages() {
        val out = "package:com.lightos\npackage:com.example.chat\r\n\npackage:com.example.music\n"
        assertEquals(setOf("com.lightos", "com.example.chat", "com.example.music"), parsePackages(out))
        assertEquals(emptySet(), parsePackages(""))
    }

    @Test
    fun readsAccessPerPackage() {
        val out = "==com.example.chat\nGET_USAGE_STATS: allow; time=+1s ago\n==com.example.music\nNo operations.\n"
        assertEquals(mapOf("com.example.chat" to true, "com.example.music" to false), parseOpPerPackage(out, "GET_USAGE_STATS"))
    }
}

class DozeTest {
    @Test
    fun readsDozeState() {
        kotlin.test.assertTrue(io.github.sevgjan.watcher.engine.parseDozeEnabled("  mLightEnabled=true  mDeepEnabled=true\n"))
        kotlin.test.assertFalse(io.github.sevgjan.watcher.engine.parseDozeEnabled("  mLightEnabled=false  mDeepEnabled=false\n"))
        kotlin.test.assertFalse(io.github.sevgjan.watcher.engine.parseDozeEnabled(""))
    }
}
