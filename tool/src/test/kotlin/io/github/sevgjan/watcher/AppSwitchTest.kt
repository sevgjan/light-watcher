package io.github.sevgjan.watcher

import io.github.sevgjan.watcher.engine.AppHistory
import io.github.sevgjan.watcher.engine.mergeServices
import io.github.sevgjan.watcher.engine.parseServices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppSwitchTest {
    @Test
    fun flipsBetweenTheLastTwoApps() {
        val h = AppHistory()
        h.appOpened("a"); h.appOpened("b")
        assertEquals("a", h.target())
        h.appOpened("a")
        assertEquals("b", h.target())
    }

    @Test
    fun fromHomeGoesToTheLastApp() {
        val h = AppHistory()
        h.appOpened("a"); h.appOpened("b"); h.homeShown()
        assertEquals("b", h.target())
    }

    @Test
    fun nowhereToGoWithOneOrNoApps() {
        val h = AppHistory()
        assertNull(h.target())
        h.appOpened("a")
        assertNull(h.target())
    }

    @Test
    fun servicesListKeepsOthers() {
        assertEquals(emptyList(), parseServices("null\n"))
        assertEquals("x/y:me/svc", mergeServices("x/y", "me/svc", true))
        assertEquals("x/y", mergeServices("x/y:me/svc", "me/svc", false))
        assertEquals("me/svc", mergeServices("null", "me/svc", true))
        assertEquals("", mergeServices("me/svc", "me/svc", false))
        assertEquals("x/y:me/svc", mergeServices("me/svc:x/y", "me/svc", true))
    }
}
