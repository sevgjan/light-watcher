package io.github.sevgjan.watcher.engine

import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed interface BridgeState {
    data object Off : BridgeState
    data object Connecting : BridgeState
    data object Ready : BridgeState
    data class Failed(val why: String) : BridgeState
}

/**
 * The phone's own debug bridge (adbd), reached over loopback on port 5555 with a key Watcher makes for
 * itself. It runs the commands `adb shell` would, which is the only way for an app to really force-stop
 * another app or restrict its background activity.
 *
 * Needs `adb tcpip 5555` run once from a computer after each restart, and a one-time "Allow USB debugging"
 * tap on the phone for Watcher's key.
 */
object Bridge {
    const val PORT = 5555

    private val _state = MutableStateFlow<BridgeState>(BridgeState.Off)
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    private var dadb: Dadb? = null

    private fun keys(): AdbKeyPair {
        val dir = Watcher.dir
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /** Connects (waiting while the phone asks to allow this key). Safe to call when already connected. */
    suspend fun connect() = withContext(Dispatchers.IO) {
        if (_state.value == BridgeState.Ready && dadb != null) return@withContext
        _state.value = BridgeState.Connecting
        runCatching { dadb?.close() }
        dadb = null
        try {
            val d = Dadb.create("127.0.0.1", PORT, keys(), connectTimeout = 4_000, socketTimeout = 45_000)
            val reply = d.shell("echo watcher-ok")
            check(reply.exitCode == 0 && reply.allOutput.contains("watcher-ok")) { "The bridge didn't answer." }
            dadb = d
            _state.value = BridgeState.Ready
        } catch (e: Exception) {
            val m = e.message.orEmpty()
            _state.value = BridgeState.Failed(
                when {
                    "refused" in m || "failed to connect" in m.lowercase() || "ECONNREFUSED" in m ->
                        "The debug port is off. On your Mac, with the phone plugged in, run: adb tcpip $PORT"
                    "timeout" in m.lowercase() || "timed out" in m.lowercase() ->
                        "Waiting for you to tap Allow on the phone's USB debugging prompt, then try again."
                    else -> m.ifBlank { "Couldn't reach the debug bridge." }
                },
            )
        }
    }

    fun disconnect() {
        runCatching { dadb?.close() }
        dadb = null
        _state.value = BridgeState.Off
    }

    /** Output of a shell command, or null if the bridge isn't up or the command failed to run. */
    suspend fun shell(command: String): String? = withContext(Dispatchers.IO) {
        val d = dadb ?: return@withContext null
        try {
            d.shell(command).allOutput
        } catch (e: Exception) {
            disconnect()
            _state.value = BridgeState.Failed("Lost the debug bridge. Open an app again to reconnect.")
            null
        }
    }

    // ---------------------------------------------------------------- what Watcher uses it for

    /** Package names with a process right now (a process name is the package, or `package:service`). */
    suspend fun running(): Set<String>? =
        shell("ps -A -o NAME")?.lineSequence()?.map { it.trim().substringBefore(':') }?.filter { '.' in it }?.toSet()

    suspend fun forceStop(pkg: String): Boolean = shell("am force-stop $pkg") != null

    /** True when Android's "Restricted" background setting is on for the app. */
    suspend fun backgroundRestricted(pkg: String): Boolean? {
        val out = shell("cmd appops get $pkg RUN_ANY_IN_BACKGROUND") ?: return null
        return out.lineSequence().any { l -> l.trimStart().startsWith("RUN_ANY_IN_BACKGROUND") && (": ignore" in l || ": deny" in l) }
    }

    /** "Restricted" is what Android's battery setting sets: the app may not run in the background. */
    suspend fun setBackgroundRestricted(pkg: String, restricted: Boolean): Boolean {
        val mode = if (restricted) "ignore" else "allow"
        shell("cmd appops set $pkg RUN_ANY_IN_BACKGROUND $mode") ?: return false
        if (restricted) shell("am force-stop $pkg")
        return true
    }

    /** The SMS code overlay is on when Watcher may receive texts and draw over other apps. */
    suspend fun codeOverlayOn(): Boolean? {
        val pkg = Watcher.packageName
        val sms = shell("dumpsys package $pkg | grep 'android.permission.RECEIVE_SMS'") ?: return null
        val draw = shell("cmd appops get $pkg SYSTEM_ALERT_WINDOW") ?: return null
        return parseSmsGranted(sms) && "SYSTEM_ALERT_WINDOW: allow" in draw
    }

    /**
     * On grants the two permissions; off takes both back. Android restarts an app that loses a runtime
     * permission, so turning it off closes Watcher, which is fine: it comes back with the switch off.
     */
    suspend fun setCodeOverlay(on: Boolean): Boolean {
        val pkg = Watcher.packageName
        if (on) {
            shell("pm grant $pkg android.permission.RECEIVE_SMS") ?: return false
            shell("cmd appops set $pkg RECEIVE_SMS allow")
            shell("cmd appops set $pkg SYSTEM_ALERT_WINDOW allow") ?: return false
        } else {
            // The app switch and the pairing helper also need to draw over other apps.
            if (appSwitchOn() != true && pairingHelperOn() != true && !Shortcuts.enabled) shell("cmd appops set $pkg SYSTEM_ALERT_WINDOW deny")
            shell("pm revoke $pkg android.permission.RECEIVE_SMS") ?: return false
        }
        return true
    }

    /** The pairing helper is on when Watcher may see Bluetooth devices and open its key screen over other apps. */
    suspend fun pairingHelperOn(): Boolean? {
        val pkg = Watcher.packageName
        val bt = shell("dumpsys package $pkg | grep 'android.permission.BLUETOOTH_CONNECT'") ?: return null
        val draw = shell("cmd appops get $pkg SYSTEM_ALERT_WINDOW") ?: return null
        return parseGranted(bt, "BLUETOOTH_CONNECT") && "SYSTEM_ALERT_WINDOW: allow" in draw
    }

    /**
     * On grants nearby-devices (so Watcher gets pairing requests), notifications (the tap-to-open fallback)
     * and draw-over-apps (so the key screen can open from the background). Off takes them back unless
     * another switch still needs them. Android restarts an app that loses a runtime permission.
     */
    suspend fun setPairingHelper(on: Boolean): Boolean {
        val pkg = Watcher.packageName
        if (on) {
            shell("pm grant $pkg android.permission.BLUETOOTH_CONNECT") ?: return false
            shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            shell("cmd appops set $pkg SYSTEM_ALERT_WINDOW allow") ?: return false
        } else {
            if (appSwitchOn() != true && codeOverlayOn() != true && !Shortcuts.enabled) shell("cmd appops set $pkg SYSTEM_ALERT_WINDOW deny")
            shell("pm revoke $pkg android.permission.BLUETOOTH_CONNECT") ?: return false
        }
        return true
    }

    /** The side-button app switch is on when Android has its accessibility service enabled. */
    suspend fun appSwitchOn(): Boolean? {
        val list = shell("settings get secure enabled_accessibility_services") ?: return null
        val enabled = shell("settings get secure accessibility_enabled") ?: return null
        return switchService() in parseServices(list) && enabled.trim() == "1"
    }

    /** Enables or disables the accessibility service that watches the side button. */
    suspend fun setAppSwitch(on: Boolean): Boolean {
        val pkg = Watcher.packageName
        val current = shell("settings get secure enabled_accessibility_services") ?: return false
        val merged = mergeServices(current, switchService(), on)
        if (on) {
            shell("cmd appops set $pkg SYSTEM_ALERT_WINDOW allow") ?: return false
            shell("settings put secure enabled_accessibility_services $merged") ?: return false
            shell("settings put secure accessibility_enabled 1")
        } else if (merged.isEmpty()) {
            shell("settings delete secure enabled_accessibility_services") ?: return false
            shell("settings put secure accessibility_enabled 0")
        } else {
            shell("settings put secure enabled_accessibility_services $merged") ?: return false
        }
        return true
    }

    private fun switchService() = "${Watcher.packageName}/io.github.sevgjan.watcher.engine.AppSwitchService"

    private fun clockService() = "${Watcher.packageName}/io.github.sevgjan.watcher.engine.ClockWatchService"

    /** Watcher's clock service (it only looks for LightOS's clock circle) is on. */
    suspend fun clockWatchOn(): Boolean? {
        val list = shell("settings get secure enabled_accessibility_services") ?: return null
        return clockService() in parseServices(list)
    }

    /** Turns the clock service on or off, leaving every other accessibility service as it was. */
    suspend fun setClockWatch(on: Boolean): Boolean {
        val current = shell("settings get secure enabled_accessibility_services") ?: return false
        val merged = mergeServices(current, clockService(), on)
        if (merged.isEmpty()) {
            shell("settings delete secure enabled_accessibility_services") ?: return false
            shell("settings put secure accessibility_enabled 0")
        } else {
            shell("settings put secure enabled_accessibility_services $merged") ?: return false
            shell("settings put secure accessibility_enabled 1")
        }
        return true
    }

    /**
     * Which of [packages] have usage access, in one go. Apps that draw on LightOS's clock use it to tell
     * when the clock (LightOS) is the app in front, so they stay off over every other app.
     */
    suspend fun clockAccess(packages: List<String>): Map<String, Boolean>? {
        if (packages.isEmpty()) return emptyMap()
        val script = packages.joinToString("; ") { "echo '==$it'; cmd appops get $it GET_USAGE_STATS" }
        val out = shell(script) ?: return null
        return parseOpPerPackage(out, "GET_USAGE_STATS").let { found -> packages.associateWith { found[it] == true } }
    }

    /**
     * On gives the app usage access, and draw-over-apps for its panel in case it was never granted. Off
     * puts usage access back to Android's default (none). Neither restarts the app.
     */
    suspend fun setClockAccess(pkg: String, on: Boolean): Boolean {
        if (!on) return shell("cmd appops set $pkg GET_USAGE_STATS default") != null
        shell("cmd appops set $pkg GET_USAGE_STATS allow") ?: return false
        return shell("cmd appops set $pkg SYSTEM_ALERT_WINDOW allow") != null
    }

    /**
     * Watcher's own clock shortcuts need usage access (to know the clock is in front) and draw-over-apps.
     * Off takes usage access back; draw-over-apps stays for Watcher's other switches.
     */
    suspend fun setShortcutAccess(on: Boolean): Boolean {
        val pkg = Watcher.packageName
        if (!on) return shell("cmd appops set $pkg GET_USAGE_STATS default") != null
        shell("cmd appops set $pkg GET_USAGE_STATS allow") ?: return false
        return shell("cmd appops set $pkg SYSTEM_ALERT_WINDOW allow") != null
    }

    /** Starts a Settings action from the shell, for when Watcher itself isn't allowed to start it. */
    suspend fun openSystemSettings(action: String): Boolean =
        shell("am start -a $action")?.let { "Error" !in it } ?: false

    /** The screen scale `wm density` reports: the phone's own value and the override, if one is set. */
    data class Density(val physical: Int, val override: Int?) {
        val current: Int get() = override ?: physical
    }

    suspend fun density(): Density? = shell("wm density")?.let(::parseDensity)

    /** Sets the screen scale for the whole phone, or puts it back with null. Bank apps built for tall phones fit at about 300. */
    suspend fun setDensity(dpi: Int?): Boolean = shell(if (dpi == null) "wm density reset" else "wm density $dpi") != null
}

/** True when `dumpsys package` lists the permission (a name without the `android.permission.` prefix) as granted. */
fun parseGranted(out: String, permission: String): Boolean =
    Regex("android\\.permission\\.${Regex.escape(permission)}: granted=(true|false)").find(out)?.groupValues?.get(1) == "true"

/** True when `cmd appops get <package> <op>` says the op is allowed (for the package or its uid). */
fun parseOpAllowed(out: String, op: String): Boolean = Regex("\\b${Regex.escape(op)}: allow\\b").containsMatchIn(out)

/** For a reply of "==<package>" lines each followed by `cmd appops get` output: whether [op] is allowed per package. */
fun parseOpPerPackage(out: String, op: String): Map<String, Boolean> {
    val result = mutableMapOf<String, Boolean>()
    var pkg: String? = null
    val block = StringBuilder()
    fun flush() { pkg?.let { result[it] = parseOpAllowed(block.toString(), op) }; block.clear() }
    for (line in out.lineSequence()) {
        if (line.startsWith("==")) { flush(); pkg = line.removePrefix("==").trim() } else block.appendLine(line)
    }
    flush()
    return result
}

/** The package names in a `pm list packages` reply ("package:com.example" per line). */
fun parsePackages(out: String): Set<String> =
    out.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }.map { it.removePrefix("package:") }.toSet()

/** True when `dumpsys package` lists RECEIVE_SMS as granted. */
fun parseSmsGranted(out: String): Boolean = parseGranted(out, "RECEIVE_SMS")

/** Reads the output of `wm density`: "Physical density: 420" and, when set, "Override density: 300". */
fun parseDensity(out: String): Bridge.Density? {
    fun value(label: String) = Regex("$label density:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
    val physical = value("Physical") ?: return null
    return Bridge.Density(physical, value("Override"))
}

/** The services in a `settings get secure enabled_accessibility_services` reply ("null" or blank means none). */
fun parseServices(out: String): List<String> =
    out.trim().takeUnless { it == "null" }.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }

/** The colon-joined service list with [service] added or removed, leaving every other service as it was. */
fun mergeServices(current: String, service: String, on: Boolean): String {
    val others = parseServices(current).filter { it != service }
    return (if (on) others + service else others).joinToString(":")
}
