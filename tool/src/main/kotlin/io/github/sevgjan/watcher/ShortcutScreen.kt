package io.github.sevgjan.watcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.sevgjan.watcher.engine.Shortcut
import io.github.sevgjan.watcher.engine.Shortcuts
import io.github.sevgjan.watcher.engine.Side
import io.github.sevgjan.watcher.engine.WatchedApp
import io.github.sevgjan.watcher.engine.Watcher

/** "Messages · Chat", or "None". */
internal fun shortcutLine(s: Shortcut?, apps: List<WatchedApp>): String {
    if (s == null) return "None"
    val app = apps.firstOrNull { it.packageName == s.pkg }?.label ?: s.pkg
    return "$app · ${Shortcuts.glyph(s.glyph)?.label ?: s.glyph}"
}

/**
 * One side's shortcut: the glyph to draw (a grid) and the app it opens (a list). Each tap saves. Picking
 * an app first gives it a fitting glyph; a glyph picked in this visit stays.
 */
class ShortcutScreen(sealedActivity: SealedLightActivity, private val side: Side) : SimpleLightScreen<Unit>(sealedActivity) {
    private var current by mutableStateOf<Shortcut?>(null)
    private var apps by mutableStateOf<List<WatchedApp>>(emptyList())
    /** The glyph tapped in this visit, kept even before an app is picked. */
    private var picked by mutableStateOf<String?>(null)

    override fun willShow() {
        current = Shortcuts.get(side)
        apps = Watcher.apps()
    }

    private fun save(s: Shortcut?) {
        Shortcuts.set(side, s)
        current = s
    }

    @Composable
    override fun Content() {
        val cur = current
        val shown = picked ?: cur?.glyph
        Page(
            title = if (side == Side.Left) "Left shortcut" else "Right shortcut",
            subtitle = shortcutLine(cur, apps),
            left = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
        ) {
            Section("Glyph")
            for (row in Shortcuts.glyphs.chunked(5)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (g in row) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.weight(1f).lightClickable {
                                picked = g.id
                                cur?.let { save(it.copy(glyph = g.id)) }
                            }.padding(vertical = 0.5f.gridUnitsAsDp()),
                        ) {
                            Image(
                                painter = painterResource(g.res),
                                contentDescription = g.label,
                                colorFilter = ColorFilter.tint(LightThemeTokens.colors.content),
                                modifier = Modifier.size(1.6f.gridUnitsAsDp()).alpha(if (g.id == shown) 1f else 0.3f),
                            )
                        }
                    }
                    // Keeps the last row's glyphs in the same columns as the rows above.
                    repeat(5 - row.size) { Box(modifier = Modifier.weight(1f)) }
                }
            }
            Section("Opens")
            Choice("None", cur == null) { save(null) }
            for (a in apps) {
                Choice(a.label, cur?.pkg == a.packageName) {
                    val glyph = picked ?: cur?.takeIf { it.pkg == a.packageName }?.glyph ?: Shortcuts.suggestedGlyph(a.packageName)
                    save(Shortcut(a.packageName, glyph))
                }
            }
        }
    }

    @Composable
    private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(vertical = 0.4f.gridUnitsAsDp()),
        ) {
            LightText(text = label, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            LightIcon(
                icon = if (selected) LightIcons.SELECT_ON else LightIcons.SELECT_OFF,
                size = 1.4f,
                contentDescription = if (selected) "chosen" else null,
            )
        }
    }
}
