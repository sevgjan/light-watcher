package io.github.sevgjan.watcher.engine

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Whether LightOS's clock is what's on screen.
 *
 * The LP3 has no Android lock screen. Its clock is part of LightOS's one activity
 * (com.lightos/.MainActivity), and so are the toolbox and LightOS's own tools, so "on the clock" is read
 * as "LightOS is the app in front", from Android's usage events. That needs usage access, which Watcher's
 * clock switches grant over the debug bridge (from a computer:
 * `adb shell appops set <package> GET_USAGE_STATS allow`). Without it [showing] always says yes.
 * Watcher's clock shortcuts use [onClock], which never guesses, and [ClockState] first when its clock
 * service runs. Apps that draw on the clock can copy this file and ask Watcher the same way.
 */
internal object ClockScreen {
    const val LIGHTOS = "com.lightos"

    /** The first look goes this far back, for whatever was in front when the screen went off. */
    private const val LOOKBACK_MS = 2 * 24 * 60 * 60 * 1000L

    private var checkedUpTo = 0L
    private var front: String? = null

    /** Usage access, from Watcher's switch. */
    fun allowed(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java) ?: return false
        return when (ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT -> c.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    /** True when LightOS is in front, or when that can't be told (no usage access). */
    fun showing(c: Context): Boolean = !allowed(c) || (frontApp(c) ?: LIGHTOS) == LIGHTOS

    /** True only when usage access says LightOS is in front; no access means no. */
    fun onClock(c: Context): Boolean = allowed(c) && frontApp(c) == LIGHTOS

    /** The app whose screen came to the front last. Only reads what happened since the last look. */
    @Synchronized
    fun frontApp(c: Context): String? {
        val usage = c.getSystemService(UsageStatsManager::class.java) ?: return front
        val now = System.currentTimeMillis()
        val from = if (checkedUpTo == 0L) now - LOOKBACK_MS else minOf(checkedUpTo, now) - 5_000
        val events = runCatching { usage.queryEvents(from, now) }.getOrNull() ?: return front
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) front = e.packageName
        }
        checkedUpTo = now
        return front
    }
}

/**
 * One stretch of screen-on under the clock: from the screen coming on to the first touch.
 *
 * While it lasts, it asks the panel to [refresh] every second (with usage access only), so the panel goes
 * when another app comes over the clock and shows when LightOS comes back. While the panel itself isn't
 * up, a one-pixel window in the corner hears the first touch anywhere, so that touch still ends the
 * stretch: a tap on the clock's circle opens the toolbox, which is LightOS too, and a message arriving
 * there mustn't pop up over it.
 */
internal class ClockSession(private val c: Context, private val refresh: () -> Unit, private val touched: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val windows = c.getSystemService(WindowManager::class.java)
    private var ear: View? = null
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        running = true
        handler.removeCallbacks(tick)
        if (ClockScreen.allowed(c)) handler.postDelayed(tick, TICK_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        listen(false)
    }

    /** On while the session runs and the panel isn't up; off while the panel hears touches itself. */
    fun listen(on: Boolean) {
        if (on && running && ear == null) {
            val v = View(c)
            val params = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSPARENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            v.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) { touched(); true } else false }
            if (runCatching { windows.addView(v, params) }.isSuccess) ear = v
        } else if (!on) {
            val v = ear ?: return
            ear = null
            runCatching { windows.removeView(v) }
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
