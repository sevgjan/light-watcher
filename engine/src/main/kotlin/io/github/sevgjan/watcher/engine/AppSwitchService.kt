package io.github.sevgjan.watcher.engine

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Which apps were in front, newest first, and where a double press of the side button goes. Kept free of
 * Android so it can be tested.
 */
class AppHistory(private val max: Int = 8) {
    private val apps = ArrayDeque<String>()

    /** What's in front right now: an app from [apps], or the home screen (null). */
    var front: String? = null
        private set

    fun appOpened(pkg: String) {
        apps.remove(pkg)
        apps.addFirst(pkg)
        while (apps.size > max) apps.removeLast()
        front = pkg
    }

    fun homeShown() {
        front = null
    }

    /** From an app, the one before it. From the home screen, the last app. Null when there's nowhere to go. */
    fun target(): String? = if (front != null && front == apps.firstOrNull()) apps.getOrNull(1) else apps.firstOrNull()
}

/**
 * Double-press the side button to flip between the last two apps. The side button is a plain Home key on
 * the LP3. A single press still goes Home, after a short wait to be sure no second press follows; a
 * double press goes to the previous app instead. With the screen off the key is left alone, so it wakes the
 * phone as always.
 */
class AppSwitchService : AccessibilityService() {
    private val history = AppHistory()
    private val handler = Handler(Looper.getMainLooper())
    private var homePackages = emptySet<String>()
    private var waitingForSecond = false
    private val goHome = Runnable {
        waitingForSecond = false
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onServiceConnected() {
        homePackages = packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Activities have their own class names; dialogs, toasts and overlays report android.* ones.
        val cls = event.className?.toString().orEmpty()
        if (cls.startsWith("android.") || cls.startsWith("com.android.systemui")) return
        when {
            pkg in homePackages -> history.homeShown()
            pkg == "com.android.systemui" || packageManager.getLaunchIntentForPackage(pkg) == null -> Unit
            else -> history.appOpened(pkg)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (!getSystemService(PowerManager::class.java).isInteractive) return false
        if (event.action == KeyEvent.ACTION_UP) {
            if (waitingForSecond) {
                handler.removeCallbacks(goHome)
                waitingForSecond = false
                switchApp()
            } else {
                waitingForSecond = true
                handler.postDelayed(goHome, DOUBLE_PRESS_MS)
            }
        }
        return true
    }

    private fun switchApp() {
        val pkg = history.target()
        val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch == null) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { performGlobalAction(GLOBAL_ACTION_HOME) }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(goHome)
        waitingForSecond = false
        return super.onUnbind(intent)
    }

    private companion object {

        /** After the first press lifts. Two presses on the LP3 land about 100 ms apart. */
        const val DOUBLE_PRESS_MS = 300L
    }
}
