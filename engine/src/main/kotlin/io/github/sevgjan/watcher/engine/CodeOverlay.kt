package io.github.sevgjan.watcher.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The rules for which texts count and where the code is. Kept free of Android so they can be tested.
 * Nothing here stores, logs or sends a message: a text from anyone else is dropped untouched.
 */
object SmsCode {
    private fun normalise(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The entry in [senders] that [sender] is (ignoring case, spaces and punctuation), or null. */
    fun match(sender: String?, senders: List<String>): String? =
        sender?.let { s -> senders.firstOrNull { normalise(it).isNotEmpty() && normalise(it) == normalise(s) } }

    private val token = Regex("(?<![A-Za-z0-9])[A-Za-z0-9]{4,8}(?![A-Za-z0-9])")

    /**
     * The one-time code: a 4 to 8 character word of letters and digits that has at least one digit, so it
     * can be all digits (482913) or mixed (4aB9cD). The one right after a colon wins ("code: 4aB9cD"),
     * otherwise the first. Null when the text has none.
     */
    fun codeIn(body: String): String? {
        val candidates = token.findAll(body).filter { m -> m.value.any { it.isDigit() } }.toList()
        val afterColon = candidates.firstOrNull { m -> body.substring(0, m.range.first).trimEnd(' ').endsWith(":") }
        return (afterColon ?: candidates.firstOrNull())?.value
    }
}

/** The senders whose codes are shown: the names (or numbers) their texts arrive from, as the user typed them. */
object CodeSenders {
    private val prefs get() = Watcher.context.getSharedPreferences("codes", Context.MODE_PRIVATE)

    fun all(): List<String> = prefs.getString("senders", "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun add(sender: String) {
        val s = sender.trim()
        if (s.isEmpty() || SmsCode.match(s, all()) != null) return
        prefs.edit().putString("senders", (all() + s).joinToString("\n")).apply()
    }

    fun remove(sender: String) {
        prefs.edit().putString("senders", all().filter { it != sender }.joinToString("\n")).apply()
    }
}

/**
 * Gets a copy of every incoming text, but only while Android has granted Watcher the receive-SMS
 * permission, which the Text codes switch grants and revokes. Anything not from one of [CodeSenders]
 * is ignored without being read.
 */
class SmsCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val bank = SmsCode.match(parts.firstOrNull()?.originatingAddress, CodeSenders.all()) ?: return
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        // No recognisable code: show the sender's text itself, so it can still be read without leaving the app.
        val code = SmsCode.codeIn(body) ?: body.trim().take(200).ifEmpty { return }
        CodeOverlay.show(context.applicationContext, bank, code)
    }
}

/** A small box over whatever app is open, holding the code. Tap it to close; it also closes by itself. */
internal object CodeOverlay {
    private const val SHOW_MS = 90_000L
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null

    fun show(context: Context, bank: String, code: String) {
        val wm = context.getSystemService(WindowManager::class.java)
        hide(wm)
        val dp = context.resources.displayMetrics.density
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((24 * dp).toInt(), (14 * dp).toInt(), (24 * dp).toInt(), (14 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke((2 * dp).toInt(), Color.WHITE)
                cornerRadius = 14 * dp
            }
            addView(TextView(context).apply {
                text = code
                setTextColor(Color.WHITE)
                val short = code.length <= 8
                textSize = if (short) 48f else 18f
                typeface = if (short) Typeface.MONOSPACE else Typeface.DEFAULT
                gravity = Gravity.CENTER
                letterSpacing = if (short) 0.12f else 0f
                if (!short) maxWidth = (300 * dp).toInt()
            })
            addView(TextView(context).apply {
                text = "$bank code. Tap to close."
                setTextColor(Color.LTGRAY)
                textSize = 12f
                gravity = Gravity.CENTER
            })
            setOnClickListener { hide(wm) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (120 * dp).toInt()
        }
        // Without the "draw over other apps" permission this throws; then the code is still on the clipboard.
        if (runCatching { wm.addView(box, params) }.isSuccess) {
            view = box
            handler.postDelayed({ hide(wm) }, SHOW_MS)
        }
    }

    private fun hide(wm: WindowManager) {
        handler.removeCallbacksAndMessages(null)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
