package io.github.sevgjan.watcher

import kotlin.test.Test
import kotlin.test.assertTrue

class NameTest {
    /** LightOS lists sideloaded tools by package name, so Watcher must sort after every other tool here. */
    @Test
    fun listsLast() {
        val others = listOf("com.Slack", "com.tailscale.ipn", "io.github.sevgjan.quran", "io.github.sevgjan.sudoku")
        assertTrue(others.all { it < "io.github.sevgjan.watcher" })
    }
}
