package io.github.sevgjan.watcher

import io.github.sevgjan.watcher.engine.Shortcuts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GlyphTest {
    @Test
    fun suggestsAGlyphFromThePackageName() {
        assertEquals("chat", Shortcuts.suggestedGlyph("com.whatsapp"))
        assertEquals("music", Shortcuts.suggestedGlyph("com.spotify.music"))
        assertEquals("radio", Shortcuts.suggestedGlyph("com.thelightphone.radio"))
        assertEquals("book", Shortcuts.suggestedGlyph("com.example.quran"))
        assertEquals("sparkle", Shortcuts.suggestedGlyph("com.example.ask"))
        assertEquals("star", Shortcuts.suggestedGlyph("com.example.something"))
    }

    @Test
    fun everySuggestionIsAGlyph() {
        val pkgs = listOf(
            "a.chat", "a.music", "a.radio", "a.book", "a.ask", "a.mail", "a.prayer", "a.sudoku", "a.vault",
            "a.draw", "a.todo", "a.voice", "a.camera", "a.maps", "a.contacts", "a.timer", "a.dialer", "a.x",
        )
        for (pkg in pkgs) assertNotNull(Shortcuts.glyph(Shortcuts.suggestedGlyph(pkg)), pkg)
    }
}
