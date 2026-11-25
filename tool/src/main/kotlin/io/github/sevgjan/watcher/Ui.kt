package io.github.sevgjan.watcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** A LightOS screen: top bar, content, optional bottom bar. */
@Composable
fun Page(
    title: String,
    subtitle: String? = null,
    left: LightBarButton? = null,
    right: LightBarButton? = null,
    onTitleClick: (() -> Unit)? = null,
    bottom: List<LightBarButton?>? = null,
    scroll: Boolean = true,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Themed {
        Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
            LightTopBar(
                leftButton = left,
                center = if (subtitle != null) LightTopBarCenter.TwoLineDetail(title, subtitle, onTitleClick) else LightTopBarCenter.Text(title, onTitleClick),
                rightButton = right,
                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
            )
            val body = Modifier.weight(1f).fillMaxWidth().let { if (padded) it.padding(horizontal = 1f.gridUnitsAsDp()) else it }
            if (scroll) LightScrollView(modifier = body, content = content) else Column(modifier = body, content = content)
            if (bottom != null) LightBottomBar(items = bottom)
        }
    }
}

@Composable
fun Themed(content: @Composable () -> Unit) {
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors) { content() }
}

@Composable
fun CenteredNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LightText(text = text, variant = LightTextVariant.Copy, lighten = true, align = TextAlign.Center, modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()))
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    LightText(text = text, variant = LightTextVariant.Fine, lighten = true, modifier = modifier.padding(vertical = 0.3f.gridUnitsAsDp()))
}

@Composable
fun Section(title: String) {
    LightText(text = title, variant = LightTextVariant.Fine, lighten = true, modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()))
}

/** A tappable line. [tight] drops the space above it, for the first line under an [AppHeader]. */
@Composable
fun ActionRow(text: String, detail: String? = null, tight: Boolean = false, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(top = if (tight) 0.05f.gridUnitsAsDp() else 0.45f.gridUnitsAsDp(), bottom = 0.45f.gridUnitsAsDp())) {
        LightText(text = text, variant = LightTextVariant.Copy, maxLines = 2, overflow = TextOverflow.Ellipsis)
        detail?.let { LightText(text = it, variant = LightTextVariant.Fine, lighten = true, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** A setting: label above, value below. */
@Composable
fun SettingRow(label: String, value: String, onClick: (() -> Unit)?) {
    Column(modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.lightClickable(onClick = onClick) else it }.padding(vertical = 0.4f.gridUnitsAsDp())) {
        LightText(text = label, variant = LightTextVariant.Fine, lighten = true)
        LightText(text = value, variant = LightTextVariant.Copy)
    }
}


/** A switch: label with LightOS's toggle beside it ("…" while it's being read), and an optional line under it. */
@Composable
fun ToggleRow(label: String, on: Boolean?, detail: String? = null, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(vertical = 0.45f.gridUnitsAsDp())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LightText(text = label, variant = LightTextVariant.Copy, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (on == null) {
                LightText(text = "…", variant = LightTextVariant.Copy, lighten = true)
            } else {
                LightIcon(
                    icon = if (on) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                    size = 1.6f,
                    contentDescription = if (on) "on" else "off",
                    modifier = Modifier.padding(start = 0.6f.gridUnitsAsDp()),
                )
            }
        }
        detail?.let { LightText(text = it, variant = LightTextVariant.Fine, lighten = true) }
    }
}

/**
 * [content] once the phone's debug bridge is up. Until then: what's wrong, and a way to connect, which
 * calls [onReady] when it works.
 */
@Composable
fun WithBridge(onReady: suspend () -> Unit = {}, content: @Composable () -> Unit) {
    val bridge by io.github.sevgjan.watcher.engine.Bridge.state.collectAsState()
    when (val b = bridge) {
        io.github.sevgjan.watcher.engine.BridgeState.Ready -> content()
        io.github.sevgjan.watcher.engine.BridgeState.Connecting ->
            Note("Connecting to the phone's debug bridge. If the phone asks to allow USB debugging, tap Allow.")
        else -> {
            Note((b as? io.github.sevgjan.watcher.engine.BridgeState.Failed)?.why ?: "This needs the phone's debug bridge: see Debug bridge on Watcher's home screen.")
            ActionRow("Connect", "Try the debug bridge again") {
                io.github.sevgjan.watcher.engine.Watcher.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    io.github.sevgjan.watcher.engine.Bridge.connect()
                    if (io.github.sevgjan.watcher.engine.Bridge.state.value == io.github.sevgjan.watcher.engine.BridgeState.Ready) onReady()
                }
            }
        }
    }
}
