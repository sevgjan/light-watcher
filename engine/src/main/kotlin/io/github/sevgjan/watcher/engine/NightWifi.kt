package io.github.sevgjan.watcher.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Wi-Fi off at night. On many networks the LP3's Wi-Fi wakes the phone for every announcement other
 * devices make (about 20 a minute at home), so a night on Wi-Fi costs more than a night on cellular.
 *
 * Inside the night window, [DELAY_MIN] minutes after the screen goes off, Watcher turns Wi-Fi off
 * through the debug bridge (apps may not switch Wi-Fi themselves). It turns it back on when the screen
 * comes on, and at the end of the window. It only turns on what it turned off: Wi-Fi you switched off
 * yourself stays off. Timing comes from inexact alarms, so a few minutes either way.
 */
object NightWifi {
    const val TAG = "WatcherNightWifi"
    const val DELAY_MIN = 10
    private const val ACTION_OFF = "io.github.sevgjan.watcher.NIGHT_WIFI_OFF"
    private const val ACTION_ON = "io.github.sevgjan.watcher.NIGHT_WIFI_ON"

    private val prefs get() = Watcher.context.getSharedPreferences("battery", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("night_wifi", false)
        set(on) { prefs.edit().putBoolean("night_wifi", on).apply() }

    /** The night window, in whole hours: from [fromHour] until [untilHour], across midnight if need be. */
    var fromHour: Int
        get() = prefs.getInt("night_from", 23)
        set(h) { prefs.edit().putInt("night_from", h).apply() }

    var untilHour: Int
        get() = prefs.getInt("night_until", 7)
        set(h) { prefs.edit().putInt("night_until", h).apply() }

    /** Watcher switched Wi-Fi off and owes switching it back on. */
    private var owed: Boolean
        get() = prefs.getBoolean("night_wifi_owed", false)
        set(on) { prefs.edit().putBoolean("night_wifi_owed", on).apply() }

    /** True when [hour] (0–23) is inside the window from [from] until [until]. */
    fun inWindow(hour: Int, from: Int, until: Int): Boolean =
        if (from == until) false else if (from < until) hour in from until until else hour >= from || hour < until

    private fun now() = Calendar.getInstance()
    private fun isNight() = inWindow(now().get(Calendar.HOUR_OF_DAY), fromHour, untilHour)

    private var receiver: BroadcastReceiver? = null

    /** Listens for the screen while Watcher's service runs. */
    fun start(c: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_OFF -> if (enabled && isNight()) {
                        schedule(c, ACTION_OFF, System.currentTimeMillis() + DELAY_MIN * 60_000L)
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        cancel(c, ACTION_OFF)
                        if (owed) Watcher.scope.launch { setWifi(true) }
                    }
                }
            }
        }
        receiver = r
        // Exported only so the flag is valid; only the system sends these.
        c.applicationContext.registerReceiver(r, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }, Context.RECEIVER_EXPORTED)
    }

    fun stop(c: Context) {
        receiver?.let { runCatching { c.applicationContext.unregisterReceiver(it) } }
        receiver = null
        cancel(c, ACTION_OFF)
    }

    /** The switch: off gives back Wi-Fi Watcher took and forgets pending alarms. */
    suspend fun turnOff(c: Context = Watcher.context) {
        enabled = false
        cancel(c, ACTION_OFF)
        cancel(c, ACTION_ON)
        if (owed) setWifi(true)
    }

    internal fun handle(c: Context, action: String?, pending: BroadcastReceiver.PendingResult) {
        Watcher.scope.launch {
            try {
                when (action) {
                    ACTION_OFF -> {
                        val awake = c.getSystemService(PowerManager::class.java)?.isInteractive == true
                        val wifiOn = c.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
                        if (!enabled || awake || !isNight() || !wifiOn) return@launch
                        if (setWifi(false)) {
                            owed = true
                            schedule(c, ACTION_ON, nextMorning())
                        }
                    }
                    ACTION_ON -> if (owed) setWifi(true)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Through the debug bridge; false when it isn't up (after a restart, before adb tcpip 5555). */
    private suspend fun setWifi(on: Boolean): Boolean {
        if (Bridge.state.value != BridgeState.Ready) Bridge.connect()
        if (Bridge.state.value != BridgeState.Ready) {
            Log.w(TAG, "couldn't turn Wi-Fi ${if (on) "on" else "off"}: the debug bridge isn't up")
            return false
        }
        val ok = Bridge.setWifi(on)
        if (ok && on) owed = false
        Log.i(TAG, "Wi-Fi ${if (on) "on" else "off"}: ${if (ok) "done" else "failed"}")
        return ok
    }

    private fun nextMorning(): Long = now().apply {
        set(Calendar.HOUR_OF_DAY, untilHour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    private fun intent(c: Context, action: String) = PendingIntent.getBroadcast(
        c, action.hashCode(), Intent(c, NightWifiReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** An inexact alarm that may fire in doze, so no exact-alarm permission is needed. */
    private fun schedule(c: Context, action: String, at: Long) {
        c.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c, action))
    }

    private fun cancel(c: Context, action: String) {
        c.getSystemService(AlarmManager::class.java)?.cancel(intent(c, action))
    }
}

/** Night Wi-Fi alarms: off after the screen has been off a while, back on in the morning. */
class NightWifiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = NightWifi.handle(context, intent.action, goAsync())
}
