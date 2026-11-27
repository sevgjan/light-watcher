package io.github.sevgjan.watcher

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.sevgjan.watcher.engine.Bridge
import io.github.sevgjan.watcher.engine.BridgeState
import io.github.sevgjan.watcher.engine.CodeSenders
import io.github.sevgjan.watcher.engine.Shortcut
import io.github.sevgjan.watcher.engine.Shortcuts
import io.github.sevgjan.watcher.engine.Side
import io.github.sevgjan.watcher.engine.WatchedApp
import io.github.sevgjan.watcher.engine.Watcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A feature screen: back button, a message line, and its state read over the bridge when shown. */
abstract class FeatureScreen(sealedActivity: SealedLightActivity, private val title: String) : SimpleLightScreen<Unit>(sealedActivity) {
    protected var message by mutableStateOf<String?>(null)

    override fun willShow() {
        Watcher.scope.launch(Dispatchers.Main) {
            if (Bridge.state.value != BridgeState.Ready) Bridge.connect()
            if (Bridge.state.value == BridgeState.Ready) load()
        }
    }

    /** Reads the screen's state; the bridge is up. */
    protected open suspend fun load() = Unit

    /** Runs a change and shows what came of it. */
    protected fun change(block: suspend () -> String?) {
        Watcher.scope.launch(Dispatchers.Main) { message = block() }
    }

    @Composable
    override fun Content() {
        Page(title = title, left = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() })) {
            message?.let { Note(it) }
            Body()
        }
    }

    @Composable
    protected abstract fun Body()
}

/** The clock: two shortcuts beside the home circle, and which apps may know when the clock is showing. */
class ClockSettingsScreen(sealedActivity: SealedLightActivity) : FeatureScreen(sealedActivity, "Clock") {
    private var on by mutableStateOf(Shortcuts.enabled)
    private var left by mutableStateOf<Shortcut?>(null)
    private var right by mutableStateOf<Shortcut?>(null)
    private var apps by mutableStateOf<List<WatchedApp>>(emptyList())
    private var watch by mutableStateOf<Boolean?>(null)

    override suspend fun load() { watch = Bridge.clockWatchOn() }

    override fun willShow() {
        on = Shortcuts.enabled
        left = Shortcuts.get(Side.Left)
        right = Shortcuts.get(Side.Right)
        apps = Watcher.apps()
        super.willShow()
    }

    @Composable
    override fun Body() {
        Section("Shortcuts")
        ToggleRow("Shortcuts on the clock", on) {
            val turnOn = !on
            change {
                if (turnOn) {
                    if (Bridge.state.value != BridgeState.Ready) Bridge.connect()
                    if (!Bridge.setShortcutAccess(true)) return@change "Turning them on needs the debug bridge."
                    // Without the clock service they'd also show over the toolbox, which is LightOS too.
                    if (watch != true && Bridge.setClockWatch(true)) watch = Bridge.clockWatchOn()
                    Shortcuts.enabled = true
                    Shortcuts.start()
                } else {
                    Shortcuts.enabled = false
                    Shortcuts.stop()
                    Bridge.setShortcutAccess(false)
                }
                on = turnOn
                if (turnOn) "Clock shortcuts are on." else "Clock shortcuts are off."
            }
        }
        ActionRow("Left of the circle", shortcutLine(left, apps)) { navigateTo({ ShortcutScreen(it, Side.Left) }) }
        ActionRow("Right of the circle", shortcutLine(right, apps)) { navigateTo({ ShortcutScreen(it, Side.Right) }) }
        Note("A glyph each side of LightOS's home circle: tap it to open its app. They show whenever the clock is on screen, and never over the toolbox or another app.")
        Section("Clock detection")
        WithBridge(onReady = { load() }) {
            val w = watch
            ToggleRow("Know when the clock shows", w) {
                if (w == null) return@ToggleRow
                change {
                    val ok = Bridge.setClockWatch(!w)
                    watch = Bridge.clockWatchOn() ?: w
                    if (ok) null else "Couldn't change it."
                }
            }
        }
        Note("LightOS's clock, toolbox and own tools are one screen to Android, so Watcher tells them apart with an accessibility service that looks only for the clock's home circle on LightOS's screen. It reads no text and no other app's screen. Apps that draw on the clock ask Watcher too. Off, they guess from which app is in front, and a touch clears them.")
        Section("Other apps")
        ActionRow("Only on the clock", "Apps that may tell when the clock is showing") { navigateTo({ ClockAppsScreen(it) }) }
    }
}

/** Usage access, app by app, for apps that draw on the clock and keep to it when they can tell it's in front. */
class ClockAppsScreen(sealedActivity: SealedLightActivity) : FeatureScreen(sealedActivity, "Only on the clock") {
    private var apps by mutableStateOf<List<WatchedApp>>(emptyList())
    private var access by mutableStateOf<Map<String, Boolean>?>(null)

    override suspend fun load() {
        apps = Watcher.apps()
        access = Bridge.clockAccess(apps.map { it.packageName })
    }

    @Composable
    override fun Body() {
        Note("Some apps draw on LightOS's clock when the screen comes on (a list of messages, playback controls). Given usage access here, an app that checks for it shows only over the clock, never over other apps. Usage access tells an app which app is in front; apps that don't use it are unaffected.")
        WithBridge(onReady = { load() }) {
            val a = access
            for (app in apps) {
                ToggleRow(app.label, a?.get(app.packageName)) {
                    val now = a?.get(app.packageName) ?: return@ToggleRow
                    change {
                        val ok = Bridge.setClockAccess(app.packageName, !now)
                        load()
                        if (ok) null else "Couldn't change it."
                    }
                }
            }
        }
    }
}

/** One-time codes from texts, shown in a box over the app you're in, for senders you choose. */
class CodesScreen(sealedActivity: SealedLightActivity) : FeatureScreen(sealedActivity, "Text codes") {
    private var on by mutableStateOf<Boolean?>(null)
    private var senders by mutableStateOf(CodeSenders.all())

    override suspend fun load() { on = Bridge.codeOverlayOn() }

    override fun willShow() {
        senders = CodeSenders.all()
        super.willShow()
    }

    @Composable
    override fun Body() {
        WithBridge(onReady = { load() }) {
            val o = on
            ToggleRow("Show codes", o) {
                if (o == null) return@ToggleRow
                change {
                    val ok = Bridge.setCodeOverlay(!o)
                    on = Bridge.codeOverlayOn() ?: o
                    if (!ok) "Couldn't change it." else if (o) "Text codes are off." else "Text codes are on."
                }
            }
            if (o == true && senders.isNotEmpty()) {
                ActionRow("Show a test code", "Appears in 10 seconds, over whatever is open") {
                    Watcher.showTestCode()
                    message = "Open the app now. The box shows in 10 seconds."
                }
            }
        }
        Section("Senders")
        if (senders.isEmpty()) Note("No senders yet: add the name or number your bank's texts come from.")
        for (s in senders) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 0.3f.gridUnitsAsDp())) {
                LightText(text = s, variant = LightTextVariant.Copy, maxLines = 1, modifier = Modifier.weight(1f))
                LightText(
                    text = "remove",
                    variant = LightTextVariant.Fine,
                    underline = true,
                    modifier = Modifier.lightClickable { CodeSenders.remove(s); senders = CodeSenders.all() }.padding(0.3f.gridUnitsAsDp()),
                )
            }
        }
        ActionRow("Add a sender", "As the text shows it, e.g. \"MyBank\"") {
            navigateTo(screenFactory = { TextEditorScreen(it, title = "Sender") }) { name ->
                CodeSenders.add(name)
                senders = CodeSenders.all()
            }
        }
        Note("When a text from one of these senders arrives, its code appears in a box over the app you're in, so you don't have to leave a banking app to read it. Turning it on lets Watcher receive texts; it ignores every one that isn't from these senders without reading it. Nothing is saved or sent. Turning it off takes the permission back, and Android closes Watcher when that happens.")
    }
}

/** Double-press the side button for the last app. */
class SideButtonScreen(sealedActivity: SealedLightActivity) : FeatureScreen(sealedActivity, "Side button") {
    private var on by mutableStateOf<Boolean?>(null)

    override suspend fun load() { on = Bridge.appSwitchOn() }

    @Composable
    override fun Body() {
        WithBridge(onReady = { load() }) {
            val o = on
            ToggleRow("Double-press for last app", o) {
                if (o == null) return@ToggleRow
                change {
                    val ok = Bridge.setAppSwitch(!o)
                    on = Bridge.appSwitchOn() ?: o
                    if (!ok) "Couldn't change it." else if (o) "Double-press is off." else "Double-press is on."
                }
            }
        }
        Note("Double-press the side button to jump to the app you had open before, and again to jump back. A single press still goes Home, a moment after you press it. It turns on an Android accessibility service that only sees which app is open and the side button; it reads nothing on screen. With the screen off the button works as usual.")
    }
}

/** Typing the key a car or keyboard shows, since LightOS's own pairing prompt crashes. */
class PairingScreen(sealedActivity: SealedLightActivity) : FeatureScreen(sealedActivity, "Bluetooth pairing") {
    private var on by mutableStateOf<Boolean?>(null)

    override suspend fun load() { on = Bridge.pairingHelperOn() }

    @Composable
    override fun Body() {
        WithBridge(onReady = { load() }) {
            val o = on
            ToggleRow("Pairing helper", o) {
                if (o == null) return@ToggleRow
                change {
                    val ok = Bridge.setPairingHelper(!o)
                    on = Bridge.pairingHelperOn() ?: o
                    if (!ok) "Couldn't change it." else if (o) "Pairing helper is off." else "Pairing helper is on."
                }
            }
        }
        Note("LightOS's own pairing prompt crashes, so a car or keyboard that shows a key can't be paired. With this on, start pairing from the other device and a key screen opens over whatever you're in: type the key it shows and press PAIR. It only works for keys you type; \"compare these numbers\" and \"tap Allow\" requests can't be answered by an app. Turning it off takes the permission back, and Android closes Watcher when that happens.")
    }
}

/** Doze: Android's deep sleep for an idle phone, which LightOS leaves off. */
class BatteryScreen(sealedActivity: SealedLightActivity) : FeatureScreen(sealedActivity, "Battery") {
    private var on by mutableStateOf<Boolean?>(null)

    override suspend fun load() {
        on = Bridge.deepSleepOn()
        // Turned on some other way (adb): keep it, so a restart doesn't lose it.
        if (on == true) io.github.sevgjan.watcher.engine.DeepSleep.wanted = true
    }

    @Composable
    override fun Body() {
        WithBridge(onReady = { load() }) {
            val o = on
            ToggleRow("Deep sleep", o) {
                if (o == null) return@ToggleRow
                change {
                    val ok = Bridge.setDeepSleep(!o)
                    io.github.sevgjan.watcher.engine.DeepSleep.wanted = !o
                    on = Bridge.deepSleepOn() ?: o
                    if (!ok) "Couldn't change it." else if (o) "Deep sleep is off." else "Deep sleep is on."
                }
            }
        }
        Note("LightOS ships with Android's doze turned off, so while the phone lies unused, apps and the system keep waking it all night. Deep sleep turns doze on: after a while still with the screen off, background work and network access are bunched into short windows. Calls, texts, alarms and apps with an ongoing connection (shown as a notification) still come through.")
        Note("A restart turns it off again. Watcher turns it back on the next time its debug bridge connects (after adb tcpip 5555).")
        Section("Wi-Fi at night")
        val nw = io.github.sevgjan.watcher.engine.NightWifi
        ToggleRow("Wi-Fi off at night", nightWifi) {
            val turnOn = !nightWifi
            change {
                if (turnOn) {
                    if (Bridge.state.value != BridgeState.Ready) Bridge.connect()
                    if (Bridge.state.value != BridgeState.Ready) return@change "This needs the debug bridge."
                    nw.enabled = true
                    Shortcuts.start()
                } else {
                    nw.turnOff()
                    if (!Shortcuts.enabled) Shortcuts.stop()
                }
                nightWifi = turnOn
                if (turnOn) "Wi-Fi goes off at night." else "Wi-Fi stays on at night."
            }
        }
        if (nightWifi) {
            ActionRow("From", "%02d:00".format(from)) { from = next(FROM_HOURS, from); nw.fromHour = from }
            ActionRow("Until", "%02d:00".format(until)) { until = next(UNTIL_HOURS, until); nw.untilHour = until }
        }
        Note("On many networks the LP3's Wi-Fi wakes the phone for every announcement other devices make, so a night on Wi-Fi uses more battery than a night on cellular. Inside these hours, ${nw.DELAY_MIN} minutes after the screen goes off, Watcher turns Wi-Fi off; it comes back on when you use the phone, and in the morning. Calls and texts keep working over the mobile network, and apps use mobile data meanwhile. Wi-Fi you turned off yourself stays off.")
    }

    private var nightWifi by mutableStateOf(io.github.sevgjan.watcher.engine.NightWifi.enabled)
    private var from by mutableStateOf(io.github.sevgjan.watcher.engine.NightWifi.fromHour)
    private var until by mutableStateOf(io.github.sevgjan.watcher.engine.NightWifi.untilHour)

    private fun next(options: List<Int>, current: Int) = options[(options.indexOf(current) + 1) % options.size]

    private companion object {
        val FROM_HOURS = listOf(21, 22, 23, 0, 1)
        val UNTIL_HOURS = listOf(5, 6, 7, 8, 9)
    }
}

/** The screen size used for tall-phone apps, as a toggle. */
private const val SMALL_DPI = 300

/** Makes everything smaller, for apps built for taller phones. */
class ScreenSizeScreen(sealedActivity: SealedLightActivity) : FeatureScreen(sealedActivity, "Screen size") {
    private var density by mutableStateOf<Bridge.Density?>(null)

    override suspend fun load() { density = Bridge.density() }

    @Composable
    override fun Body() {
        WithBridge(onReady = { load() }) {
            val d = density
            val small = d != null && d.override != null && d.override != d.physical
            ToggleRow("Small screen", d?.let { small }, d?.let { if (small) "${it.current} dpi (normal is ${it.physical})" else "${it.physical} dpi" }) {
                if (d == null) return@ToggleRow
                change {
                    val ok = Bridge.setDensity(if (small) null else SMALL_DPI)
                    density = Bridge.density() ?: d
                    if (!ok) "Couldn't change it." else if (small) "Normal size." else "Small screen on."
                }
            }
        }
        Note("Makes everything on the phone smaller so more fits. Turn it on before opening an app whose buttons are hidden off the bottom, and off again afterwards. It applies to the whole phone and stays until you turn it off.")
    }
}

/** Android's own settings screens, which LightOS keeps but hides. */
class AndroidSettingsScreen(sealedActivity: SealedLightActivity) : FeatureScreen(sealedActivity, "Android settings") {
    @Composable
    override fun Body() {
        ActionRow("All settings", "Android's full settings app") { open(android.provider.Settings.ACTION_SETTINGS) }
        ActionRow("Keyboard", "Keyboards, autocorrect and input") { open(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS) }
        ActionRow("Languages", "Phone and keyboard languages") { open(android.provider.Settings.ACTION_LOCALE_SETTINGS) }
        Note("LightOS hides these. Changing things here can affect how LightOS behaves; use the back button to return.")
    }

    /** Opens a stock Settings screen directly, or through the debug bridge if Android refuses Watcher. */
    private fun open(action: String) {
        if (Watcher.openSystemSettings(action)) return
        change {
            if (Bridge.state.value != BridgeState.Ready) Bridge.connect()
            if (Bridge.state.value == BridgeState.Ready && Bridge.openSystemSettings(action)) null else "Couldn't open Android's settings."
        }
    }
}

/** The phone's own debug bridge, which most of Watcher needs, and how to turn it on. */
class BridgeScreen(sealedActivity: SealedLightActivity) : FeatureScreen(sealedActivity, "Debug bridge") {
    @Composable
    override fun Body() {
        WithBridge {
            LightText(text = "Connected", variant = LightTextVariant.Copy)
            ActionRow("Disconnect", "Until you open something that needs it") { Bridge.disconnect() }
        }
        Section("Setting it up")
        Note("Watcher talks to the phone's own debug bridge, the way a computer does over USB. That's how it can force stop apps, grant itself permissions and change the screen size, without root.")
        Note("1. Turn on USB debugging (Android settings → System → Developer options).")
        Note("2. With the phone plugged into a computer that has adb, run: adb tcpip 5555. Do it again after every restart.")
        Note("3. Tap Connect here and tap Allow when the phone asks about USB debugging. Watcher's key stays on the phone.")
    }
}
