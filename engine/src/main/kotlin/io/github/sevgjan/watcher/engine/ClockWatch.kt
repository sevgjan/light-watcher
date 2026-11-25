package io.github.sevgjan.watcher.engine

import android.accessibilityservice.AccessibilityService
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Whether LightOS's clock is on screen, as [ClockWatchService] last saw it. LightOS's clock, toolbox
 * and its own tools are one Android screen, so only what's drawn tells them apart: the clock is the
 * screen with the home circle button.
 */
object ClockState {
    /** The service is running; without it nobody knows, and [showing] means nothing. */
    @Volatile var watching = false
        internal set

    @Volatile var showing = false
        private set

    internal fun set(now: Boolean) {
        if (now == showing) return
        showing = now
        ShortcutPanel.clockChanged(now)
    }

    /** The view ids LightOS gives its home circle (only on the clock). */
    private val markers = setOf("TouchableWithoutFeedback-actionBar-button-circle", "icon-circle")

    /** True when [id] is the clock's circle. */
    fun isClockMarker(id: String?): Boolean = id != null && id in markers
}

/**
 * An accessibility service that only looks for LightOS's clock circle: on each window change, and on
 * content changes in LightOS, it walks LightOS's screen for that view id. It reads no text, and looks
 * into no other app's screen. Turned on and off from Watcher's Clock screen, over the debug bridge.
 */
class ClockWatchService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { evaluate() }

    override fun onServiceConnected() {
        ClockState.watching = true
        evaluate()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val fromLightOs = event.packageName?.toString() == ClockScreen.LIGHTOS
        // Another app's content changing can't change whether the clock shows; its window coming up can.
        if (!fromLightOs && event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        handler.removeCallbacks(check)
        handler.postDelayed(check, SETTLE_MS)
    }

    private fun evaluate() {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        ClockState.set(root != null && root.packageName?.toString() == ClockScreen.LIGHTOS && hasMarker(root, 0))
    }

    private fun hasMarker(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (ClockState.isClockMarker(node.viewIdResourceName)) return true
        if (depth > MAX_DEPTH) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasMarker(child, depth + 1)) return true
        }
        return false
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(check)
        ClockState.watching = false
        return super.onUnbind(intent)
    }

    private companion object {
        /** Lets a screen change finish drawing before looking. */
        const val SETTLE_MS = 80L
        const val MAX_DEPTH = 30
    }
}

/**
 * `content://io.github.sevgjan.watcher.clock`, method "clock": a Bundle with "watching" and "showing".
 * Apps that draw on LightOS's clock ask this, once a second while their panel could show.
 */
class ClockProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "clock") return null
        return Bundle().apply {
            putBoolean("watching", ClockState.watching)
            putBoolean("showing", ClockState.showing)
        }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
