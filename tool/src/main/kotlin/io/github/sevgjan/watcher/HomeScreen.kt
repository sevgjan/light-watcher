package io.github.sevgjan.watcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import io.github.sevgjan.watcher.engine.Bridge
import io.github.sevgjan.watcher.engine.BridgeState
import io.github.sevgjan.watcher.engine.CodeSenders
import io.github.sevgjan.watcher.engine.Shortcuts
import io.github.sevgjan.watcher.engine.Watcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** What the home screen shows beside each entry, read over the bridge when it's up. */
private class Status {
    var codes by mutableStateOf<Boolean?>(null)
    var appSwitch by mutableStateOf<Boolean?>(null)
    var pairing by mutableStateOf<Boolean?>(null)
    var density by mutableStateOf<Bridge.Density?>(null)
    var deepSleep by mutableStateOf<Boolean?>(null)

    suspend fun read() {
        if (Bridge.state.value != BridgeState.Ready) return
        codes = Bridge.codeOverlayOn()
        appSwitch = Bridge.appSwitchOn()
        pairing = Bridge.pairingHelperOn()
        density = Bridge.density()
        deepSleep = Bridge.deepSleepOn()
    }
}

private fun onOff(v: Boolean?) = when (v) { null -> NEEDS_BRIDGE; true -> "On"; false -> "Off" }

private const val NEEDS_BRIDGE = "Needs the debug bridge"

/** Watcher's home: the things it can do for the phone, each with where it stands. */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    private val status = Status()
    private var openApps by mutableStateOf<Int?>(null)

    override fun willShow() {
        // Brings the clock shortcuts' service back if something stopped it (a force stop, say).
        Shortcuts.start()
        Watcher.scope.launch(Dispatchers.Main) {
            if (Bridge.state.value != BridgeState.Ready) Bridge.connect()
            Running.refresh()
            openApps = Running.set?.let { running -> Watcher.apps().count { it.packageName in running } }
            status.read()
        }
    }

    @Composable
    override fun Content() {
        val bridge by Bridge.state.collectAsState()
        val d = status.density
        Page(title = "Watcher") {
            ActionRow("Apps", openApps?.let { if (it == 0) "Nothing open" else "$it open" } ?: "See and close apps", tight = true) {
                navigateTo({ AppsScreen(it) })
            }
            ActionRow("Clock", if (Shortcuts.enabled) "Shortcuts on" else "Shortcuts and clock apps") {
                navigateTo({ ClockSettingsScreen(it) })
            }
            ActionRow("Text codes", when (status.codes) {
                null -> NEEDS_BRIDGE
                true -> CodeSenders.all().size.let { "On · $it sender${if (it == 1) "" else "s"}" }
                false -> "Off"
            }) { navigateTo({ CodesScreen(it) }) }
            ActionRow("Side button", "Double-press for last app: ${onOff(status.appSwitch).lowercase()}") {
                navigateTo({ SideButtonScreen(it) })
            }
            ActionRow("Bluetooth pairing", "Pairing helper: ${onOff(status.pairing).lowercase()}") {
                navigateTo({ PairingScreen(it) })
            }
            ActionRow("Battery", "Deep sleep: ${onOff(status.deepSleep).lowercase()}") { navigateTo({ BatteryScreen(it) }) }
            ActionRow("Screen size", when {
                d == null -> NEEDS_BRIDGE
                d.override != null && d.override != d.physical -> "Small (${d.current} dpi)"
                else -> "Normal"
            }) { navigateTo({ ScreenSizeScreen(it) }) }
            ActionRow("Android settings", "The ones LightOS hides") { navigateTo({ AndroidSettingsScreen(it) }) }
            ActionRow("Debug bridge", when (bridge) {
                BridgeState.Ready -> "Connected"
                BridgeState.Connecting -> "Connecting…"
                else -> "Not connected"
            }) { navigateTo({ BridgeScreen(it) }) }
        }
    }
}
