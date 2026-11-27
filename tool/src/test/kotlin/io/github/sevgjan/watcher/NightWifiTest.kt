package io.github.sevgjan.watcher

import io.github.sevgjan.watcher.engine.NightWifi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NightWifiTest {
    @Test
    fun windowAcrossMidnight() {
        assertTrue(NightWifi.inWindow(23, 23, 7))
        assertTrue(NightWifi.inWindow(0, 23, 7))
        assertTrue(NightWifi.inWindow(6, 23, 7))
        assertFalse(NightWifi.inWindow(7, 23, 7))
        assertFalse(NightWifi.inWindow(12, 23, 7))
        assertFalse(NightWifi.inWindow(22, 23, 7))
    }

    @Test
    fun windowAfterMidnightOnly() {
        assertTrue(NightWifi.inWindow(0, 0, 7))
        assertTrue(NightWifi.inWindow(3, 0, 7))
        assertFalse(NightWifi.inWindow(23, 0, 7))
        assertFalse(NightWifi.inWindow(7, 0, 7))
        assertFalse(NightWifi.inWindow(5, 5, 5))
    }
}
