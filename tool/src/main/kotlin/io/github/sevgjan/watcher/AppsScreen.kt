package io.github.sevgjan.watcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
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
import io.github.sevgjan.watcher.engine.Bridge
import io.github.sevgjan.watcher.engine.BridgeState
import io.github.sevgjan.watcher.engine.WatchedApp
import io.github.sevgjan.watcher.engine.Watcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** What the bridge says is running, or null while it isn't connected. Shared by both screens. */
internal object Running {
    var set by mutableStateOf<Set<String>?>(null)

    suspend fun refresh() {
        set = if (Bridge.state.value == BridgeState.Ready) Bridge.running() else null
    }

    /** Connects the bridge in the background (once per visit) and reads what's running. */
    fun start() {
        Watcher.scope.launch(Dispatchers.Main) {
            if (Bridge.state.value != BridgeState.Ready) Bridge.connect()
            refresh()
        }
    }
}

/** Closes one app: a real force stop through the bridge when it's up, else its background processes. */
internal suspend fun closeApp(pkg: String) {
    if (Bridge.state.value == BridgeState.Ready && Bridge.forceStop(pkg)) Running.refresh() else Watcher.close(pkg)
}

/** How an app was closed in this visit. */
private enum class Closed(val note: String) { Background("background closed"), Stopped("force stopped") }

/**
 * The apps on the phone. Under each open app are two small buttons: "close" ends its background
 * processes (Android may still bring it back where it was), "force stop" ends it completely and needs the
 * debug bridge. QUIT ALL is at the bottom.
 */
class AppsScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    private var apps by mutableStateOf<List<WatchedApp>>(emptyList())
    /** Closed in this visit, and how. */
    private val closed = mutableStateMapOf<String, Closed>()
    private var message by mutableStateOf<String?>(null)

    override fun willShow() {
        apps = Watcher.apps()
        message = null
        Running.start()
    }

    private fun closeBackground(a: WatchedApp) {
        closed[a.packageName] = Closed.Background
        Watcher.close(a.packageName)
        message = null
    }

    private fun forceStop(a: WatchedApp) {
        Watcher.scope.launch(Dispatchers.Main) {
            if (Bridge.state.value != BridgeState.Ready) Bridge.connect()
            if (Bridge.state.value == BridgeState.Ready && Bridge.forceStop(a.packageName)) {
                closed[a.packageName] = Closed.Stopped
                message = null
                Running.refresh()
            } else {
                message = "Couldn't force stop ${a.label}. " +
                    ((Bridge.state.value as? BridgeState.Failed)?.why ?: "The debug bridge isn't up.")
            }
        }
    }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val bridge by Bridge.state.collectAsState()
        val running = Running.set
        // With the bridge up, only apps that really have a process count as open.
        val open = apps.filter { closed[it.packageName] == null && (running == null || it.packageName in running) }
        val rest = apps.filter { it !in open }
        LightTheme(colors = colors) {
            Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.TwoLineDetail("Apps", if (open.isEmpty()) "Nothing open" else "${open.size} open"),
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )
                LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                    message?.let { Note(it) }
                    if (apps.isEmpty()) {
                        LightText(text = "No apps found.", variant = LightTextVariant.Copy, lighten = true, modifier = Modifier.padding(top = 2f.gridUnitsAsDp()))
                    }
                    for (a in open) AppRow(a, null, bridge == BridgeState.Ready)
                    for (a in rest) AppRow(a, closed[a.packageName] ?: Closed.Stopped, bridge == BridgeState.Ready)
                    LightText(
                        text = when (bridge) {
                            BridgeState.Ready -> "close ends an app's background processes; it may still come back where it was. force stop ends it completely. Tap a name for its settings."
                            BridgeState.Connecting -> "Connecting to the phone's debug bridge…"
                            else -> "close ends an app's background processes; it may still come back where it was. force stop needs the debug bridge (Watcher's home screen → Debug bridge)."
                        },
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                    )
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(text = "QUIT ALL", enabled = open.isNotEmpty(), onClick = {
                            val all = open.map { it.packageName }
                            val how = if (Bridge.state.value == BridgeState.Ready) Closed.Stopped else Closed.Background
                            all.forEach { closed[it] = how }
                            Watcher.scope.launch(Dispatchers.Main) {
                                all.forEach { closeApp(it) }
                                Running.refresh()
                            }
                        }),
                    ),
                )
            }
        }
    }

    /**
     * The name, and under it either the two buttons (open) or what happened to it. A background-closed
     * app keeps its force stop button, since it can still come back where it was.
     */
    @Composable
    private fun AppRow(a: WatchedApp, state: Closed?, bridgeUp: Boolean) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 0.35f.gridUnitsAsDp())) {
            LightText(
                text = a.label,
                variant = LightTextVariant.Copy,
                lighten = state != null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().lightClickable { navigateTo({ AppScreen(it, a) }) }.padding(vertical = 0.15f.gridUnitsAsDp()),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (state) {
                    null -> {
                        SmallButton("close") { closeBackground(a) }
                        SmallButton("force stop", dim = !bridgeUp) { forceStop(a) }
                    }
                    Closed.Background -> {
                        LightText(text = state.note, variant = LightTextVariant.Fine, lighten = true, modifier = Modifier.padding(end = 1f.gridUnitsAsDp()))
                        SmallButton("force stop", dim = !bridgeUp) { forceStop(a) }
                    }
                    Closed.Stopped -> LightText(text = state.note, variant = LightTextVariant.Fine, lighten = true, modifier = Modifier.padding(vertical = 0.3f.gridUnitsAsDp()))
                }
            }
        }
    }

    /** A small underlined word with a finger-sized tap area around it. [dim] when it needs the bridge and it's off. */
    @Composable
    private fun SmallButton(text: String, dim: Boolean = false, onClick: () -> Unit) {
        LightText(
            text = text,
            variant = LightTextVariant.Fine,
            underline = true,
            lighten = dim,
            modifier = Modifier
                .lightClickable(onClick = onClick)
                .padding(top = 0.3f.gridUnitsAsDp(), bottom = 0.3f.gridUnitsAsDp(), end = 1.2f.gridUnitsAsDp()),
        )
    }
}

/** One app: force stop, background sync on or off, and Android's own settings for it. */
class AppScreen(sealedActivity: SealedLightActivity, private val app: WatchedApp) : SimpleLightScreen<Unit>(sealedActivity) {
    private var restricted by mutableStateOf<Boolean?>(null)
    private var message by mutableStateOf<String?>(null)

    override fun willShow() {
        Watcher.scope.launch(Dispatchers.Main) {
            if (Bridge.state.value != BridgeState.Ready) Bridge.connect()
            Running.refresh()
            restricted = if (Bridge.state.value == BridgeState.Ready) Bridge.backgroundRestricted(app.packageName) else null
        }
    }

    @Composable
    override fun Content() {
        val bridge by Bridge.state.collectAsState()
        val running = Running.set
        val ready = bridge == BridgeState.Ready
        Page(
            title = app.label,
            subtitle = when {
                !ready -> null
                running == null -> null
                app.packageName in running -> "Running"
                else -> "Not running"
            },
            left = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
            bottom = listOf(LightBarButton.Text(text = "SYSTEM SETTINGS", onClick = { Watcher.openInfo(app.packageName) })),
        ) {
            message?.let { Note(it) }
            when (bridge) {
                BridgeState.Ready -> {
                    ActionRow("Force stop", "Ends it now") {
                        Watcher.scope.launch(Dispatchers.Main) {
                            message = if (Bridge.forceStop(app.packageName)) "${app.label} stopped." else "Couldn't stop it."
                            Running.refresh()
                        }
                    }
                    val r = restricted
                    ToggleRow("Background sync", r?.let { !it }) {
                        if (r == null) return@ToggleRow
                        Watcher.scope.launch(Dispatchers.Main) {
                            if (Bridge.setBackgroundRestricted(app.packageName, !r)) {
                                restricted = Bridge.backgroundRestricted(app.packageName)
                                message = if (restricted == true) "Background sync is off." else "Background sync is on."
                                Running.refresh()
                            } else {
                                message = "Couldn't change it."
                            }
                        }
                    }
                    Note("Off saves battery: the app only works while you have it open, and gets no notifications. It stays off until you turn it back on.")
                }
                BridgeState.Connecting -> Note("Connecting to the phone's debug bridge. If the phone asks to allow USB debugging, tap Allow.")
                else -> {
                    ActionRow("Close", "Ends its background processes") {
                        Watcher.scope.launch(Dispatchers.Main) {
                            Watcher.close(app.packageName)
                            message = "Closed ${app.label}'s background processes."
                        }
                    }
                    Section("Full control is off")
                    Note((bridge as? BridgeState.Failed)?.why ?: "Force stop and background sync need the phone's debug bridge.")
                    ActionRow("Connect", "Try the debug bridge again") {
                        Watcher.scope.launch(Dispatchers.Main) {
                            Bridge.connect()
                            Running.refresh()
                            restricted = if (Bridge.state.value == BridgeState.Ready) Bridge.backgroundRestricted(app.packageName) else null
                        }
                    }
                }
            }
        }
    }
}

