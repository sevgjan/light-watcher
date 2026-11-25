package io.github.sevgjan.watcher.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.thelightphone.sdk.ui.R as SdkR

/** Which side of LightOS's home circle. */
enum class Side { Left, Right }

/** A glyph to draw beside the circle: Watcher's own (chat, music, …) or one of the SDK's. */
data class Glyph(val id: String, val label: String, val res: Int)

/** One shortcut: the app it opens and the glyph that stands for it. */
data class Shortcut(val pkg: String, val glyph: String)

/**
 * Two shortcuts on LightOS's clock, one each side of the home circle: a tap opens the app. Kept in
 * Watcher's preferences; [ShortcutPanel] draws them and [ShortcutService] keeps Watcher listening for the
 * screen while they're on.
 */
object Shortcuts {
    val glyphs: List<Glyph> = listOf(
        Glyph("chat", "Chat", R.drawable.watcher_glyph_chat),
        Glyph("music", "Music", R.drawable.watcher_glyph_music),
        Glyph("radio", "Radio", R.drawable.watcher_glyph_radio),
        Glyph("book", "Book", R.drawable.watcher_glyph_book),
        Glyph("sparkle", "Sparkle", R.drawable.watcher_glyph_sparkle),
        Glyph("mail", "Mail", R.drawable.watcher_glyph_mail),
        Glyph("moon", "Moon", R.drawable.watcher_glyph_moon),
        Glyph("grid", "Grid", R.drawable.watcher_glyph_grid),
        Glyph("key", "Key", R.drawable.watcher_glyph_key),
        Glyph("call", "Phone", SdkR.drawable.ic_call_white),
        Glyph("compose", "Write", SdkR.drawable.ic_compose_white),
        Glyph("pencil", "Pencil", SdkR.drawable.ic_pencil_white),
        Glyph("camera", "Camera", SdkR.drawable.ic_camera),
        Glyph("microphone", "Microphone", SdkR.drawable.ic_microphone_white),
        Glyph("media", "Media", SdkR.drawable.ic_media_white),
        Glyph("map", "Map", SdkR.drawable.ic_map_white),
        Glyph("search", "Search", SdkR.drawable.ic_search_white),
        Glyph("alarm", "Alarm", SdkR.drawable.ic_alarm_white),
        Glyph("contacts", "Contacts", SdkR.drawable.ic_contacts_white),
        Glyph("list", "List", SdkR.drawable.ic_list_white),
        Glyph("star", "Star", SdkR.drawable.ic_star_white),
        Glyph("settings", "Settings", SdkR.drawable.ic_settings_white),
        Glyph("light", "Light", SdkR.drawable.ic_light_logo_white),
    )

    fun glyph(id: String?): Glyph? = glyphs.firstOrNull { it.id == id }

    /** Words in a package name, and the glyph they suggest; the first that matches wins. */
    private val hints = listOf(
        listOf("whatsapp", "signal", "telegram", "slack", "chat", "messag", "sms") to "chat",
        listOf("music", "spotify", "tidal", "deezer", "podcast", "audio") to "music",
        listOf("radio") to "radio",
        listOf("quran", "bible", "book", "reader", "read") to "book",
        listOf("gpt", "claude", "gemini", "ask") to "sparkle",
        listOf("mail") to "mail",
        listOf("prayer", "salat", "pray", "sleep") to "moon",
        listOf("sudoku", "puzzle", "game") to "grid",
        listOf("password", "authenticator", "otp", "vault") to "key",
        listOf("scribble", "sketch", "draw", "note") to "pencil",
        listOf("reminder", "todo", "task", "list") to "list",
        listOf("dictat", "voice", "record") to "microphone",
        listOf("camera") to "camera",
        listOf("map", "navi", "transit") to "map",
        listOf("contact") to "contacts",
        listOf("alarm", "clock", "timer") to "alarm",
        listOf("phone", "dial", "call") to "call",
    )

    /** The glyph an app gets when it's picked first, so picking the app is often all there is to do. */
    fun suggestedGlyph(pkg: String): String {
        val name = pkg.lowercase().substringAfter('.')
        return hints.firstOrNull { (words, _) -> words.any { it in name } }?.second ?: "star"
    }

    private val prefs get() = Watcher.context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)

    /** The switch in Watcher's settings. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(on) { prefs.edit().putBoolean("enabled", on).apply() }

    fun get(side: Side): Shortcut? {
        val key = side.name.lowercase()
        val pkg = prefs.getString("${key}_pkg", null) ?: return null
        return Shortcut(pkg, prefs.getString("${key}_glyph", null) ?: suggestedGlyph(pkg))
    }

    fun set(side: Side, shortcut: Shortcut?) {
        val key = side.name.lowercase()
        prefs.edit().apply {
            if (shortcut == null) remove("${key}_pkg").remove("${key}_glyph")
            else putString("${key}_pkg", shortcut.pkg).putString("${key}_glyph", shortcut.glyph)
        }.apply()
        ShortcutPanel.changed()
    }

    /** Starts the listening service when the switch is on. Fine to call often; from the background only at boot or after an update. */
    fun start(c: Context = Watcher.context) {
        if (!enabled) return
        runCatching { c.startForegroundService(Intent(c, ShortcutService::class.java)) }
            .onFailure { Log.w(ShortcutPanel.TAG, "couldn't start the shortcut service: $it") }
    }

    fun stop(c: Context = Watcher.context) {
        c.stopService(Intent(c, ShortcutService::class.java))
    }
}

/**
 * The two glyphs on the clock. They show when the screen comes on with LightOS's clock in front (usage
 * access, from Watcher's switch; without it they never show), and go when another app comes over the
 * clock, at the first touch anywhere else, at the side button, or when the screen goes off. They never
 * turn the screen on or keep it on.
 */
internal object ShortcutPanel {
    const val TAG = "WatcherShortcuts"

    /** The home circle's centre on LightOS's clock, as a share of the full screen height (1160 of 1240 px, measured on the LP3). */
    private const val CIRCLE_Y = 1160f / 1240f
    /** Each glyph's centre, as a share of the screen width in from its own edge. */
    private const val SIDE_X = 0.2f
    private const val GLYPH_DP = 30
    private const val TOUCH_DP = 72
    /** Long enough for LightOS to come back in front after the side button. */
    private const val AFTER_HOME_MS = 600L

    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var context: Context? = null
    private var session: ClockSession? = null
    private val views = mutableMapOf<Side, View>()
    /** A finger is on one of the glyphs: the other glyph's "touch outside" mustn't end things then. */
    private var pressing = false
    private val endSoon = Runnable { if (!pressing) touchedElsewhere() }

    fun start(c: Context) {
        if (receiver != null) return
        context = c.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_ON -> begin(c)
                    Intent.ACTION_SCREEN_OFF -> end()
                    // The side button (Home) takes you to the clock: start over there once LightOS is back in front.
                    // With the clock service watching, the glyphs already follow the clock; its "home" notice
                    // arrives after LightOS is back on the clock and would only clear them.
                    Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> if (i.getStringExtra("reason") == "homekey" && !ClockState.watching) {
                        end()
                        handler.postDelayed({ begin(c) }, AFTER_HOME_MS)
                    }
                }
            }
        }
        receiver = r
        // Exported only so the flag is valid for all three; only the system sends them.
        c.applicationContext.registerReceiver(r, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        }, Context.RECEIVER_EXPORTED)
    }

    fun stop(c: Context) {
        receiver?.let { runCatching { c.applicationContext.unregisterReceiver(it) } }
        receiver = null
        end()
    }

    /** Whether the clock is on screen: Watcher's clock service when it runs, else usage access (no touch can be told apart then). */
    private fun onClock(c: Context): Boolean = if (ClockState.watching) ClockState.showing else ClockScreen.onClock(c)

    /** The clock service saw the clock come or go: follow it at once. */
    fun clockChanged(showing: Boolean) = handler.post {
        val c = context ?: return@post
        val awake = c.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
        when {
            session != null -> refresh(c)
            showing && awake -> begin(c)
        }
    }

    /** A touch elsewhere ends things only when there's no clock service to say when the clock goes. */
    private fun touchedElsewhere() { if (!ClockState.watching) end() }

    /** A shortcut was changed: redraw if they're up. */
    fun changed() = handler.post {
        val c = context ?: return@post
        if (session == null) return@post
        removeAll()
        refresh(c)
    }

    private fun begin(c: Context) {
        end()
        if (!Shortcuts.enabled || !Settings.canDrawOverlays(c)) return
        session = ClockSession(c, refresh = { refresh(c) }, touched = { touchedElsewhere() }).also { it.start() }
        refresh(c)
    }

    private fun refresh(c: Context) {
        val s = session ?: return
        val wanted = Side.entries.mapNotNull { side -> Shortcuts.get(side)?.let { side to it } }
            .filter { (_, sc) -> c.packageManager.getLaunchIntentForPackage(sc.pkg) != null }
        if (wanted.isNotEmpty() && onClock(c)) {
            for ((side, sc) in wanted) if (views[side] == null) add(c, side, sc)
            s.listen(views.isEmpty() && !ClockState.watching)
        } else {
            removeAll()
            s.listen(!ClockState.watching)
        }
    }

    private fun end() {
        handler.removeCallbacks(endSoon)
        pressing = false
        session?.stop()
        session = null
        removeAll()
    }

    private fun removeAll() {
        val wm = context?.getSystemService(WindowManager::class.java) ?: return
        for (v in views.values) runCatching { wm.removeView(v) }
        views.clear()
    }

    private fun add(c: Context, side: Side, sc: Shortcut) {
        val glyph = Shortcuts.glyph(sc.glyph) ?: Shortcuts.glyph(Shortcuts.suggestedGlyph(sc.pkg)) ?: return
        // The whole screen: displayMetrics leaves out a strip LightOS draws over, which put the glyphs 29 px high.
        val screen = c.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val size = dp(c, TOUCH_DP)
        val pad = (size - dp(c, GLYPH_DP)) / 2
        val v = ImageView(c).apply {
            setImageResource(glyph.res)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            contentDescription = glyph.label
            setOnTouchListener { view, e ->
                when (e.action) {
                    // A touch somewhere else: end, unless it turns out to be on the other glyph.
                    MotionEvent.ACTION_OUTSIDE -> { handler.removeCallbacks(endSoon); handler.postDelayed(endSoon, 250); true }
                    MotionEvent.ACTION_DOWN -> { pressing = true; view.alpha = 0.4f; false }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pressing = false; view.alpha = 1f; false }
                    else -> false
                }
            }
            setOnClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                open(c, sc.pkg)
                end()
            }
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    // The touch box centred on the circle reaches past the bottom edge; without this Android
                    // pushes the window up to fit, and the glyph sits above the circle.
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or (if (side == Side.Left) Gravity.START else Gravity.END)
            x = (screen.width() * SIDE_X - size / 2f).toInt()
            y = (screen.height() * CIRCLE_Y - size / 2f).toInt()
        }
        runCatching { c.getSystemService(WindowManager::class.java).addView(v, params) }
            .onSuccess { views[side] = v }
            .onFailure { Log.w(TAG, "overlay refused: $it") }
    }

    private fun open(c: Context, pkg: String) {
        val launch = c.packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { c.startActivity(launch) }.onFailure { Log.w(TAG, "couldn't open $pkg: $it") }
    }

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
}

/** Keeps Watcher's process, and so its ear for the screen, alive while the clock shortcuts are on. */
class ShortcutService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ShortcutPanel.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Clock shortcuts", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.watcher_glyph_grid)
            .setContentTitle("Watcher")
            .setContentText("Clock shortcuts are on")
            .setOngoing(true)
            .build()
        runCatching { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            .onFailure { Log.w(ShortcutPanel.TAG, "not in the foreground: $it") }
        if (!Shortcuts.enabled) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        ShortcutPanel.stop(this)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "shortcuts"
        const val ID = 7
    }
}

/** At boot and after an update, brings the shortcut service back when the switch is on. */
class ShortcutBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Shortcuts.start(context)
}
