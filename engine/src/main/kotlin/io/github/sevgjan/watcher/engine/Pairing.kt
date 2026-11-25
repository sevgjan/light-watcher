package io.github.sevgjan.watcher.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * What the phone's own pairing prompt can't do: LightOS's Settings crashes when it opens that prompt, so a
 * device that shows a key (a car, a keyboard) can never be answered. Kept free of Android so it can be tested.
 */
object PairingRules {
    // BluetoothDevice.PAIRING_VARIANT_*; PASSKEY and PIN_16_DIGITS are hidden in the SDK.
    const val PIN = 0
    const val PASSKEY = 1
    const val PASSKEY_CONFIRMATION = 2
    const val CONSENT = 3
    const val DISPLAY_PASSKEY = 4
    const val DISPLAY_PIN = 5
    const val PIN_16_DIGITS = 7

    /** A PIN is at most 16 bytes. */
    const val MAX_PIN = 16

    /** Only these ask you to type a key that the other device shows. They are the ones an ordinary app may answer. */
    fun canAnswer(variant: Int) = variant == PIN || variant == PIN_16_DIGITS

    /** The key as typed, or null when it's empty or too long to be a PIN. */
    fun cleanPin(text: String): String? = text.trim().takeIf { it.isNotEmpty() && it.toByteArray().size <= MAX_PIN }

    /** Why a pairing can't be finished here, in words for the screen. */
    fun whyNot(variant: Int): String = when (variant) {
        PASSKEY_CONFIRMATION -> "This pairing asks you to confirm that both devices show the same number. Android only lets the system answer that, so Watcher can't."
        CONSENT -> "This pairing asks you to tap Allow. Android only lets the system answer that, so Watcher can't."
        DISPLAY_PASSKEY, DISPLAY_PIN -> "This pairing shows a key on the phone and you type it on the other device. Watcher can't show it."
        PASSKEY -> "This pairing asks for a passkey, which Android only lets the system answer. Watcher can't."
        else -> "Watcher can't answer this kind of pairing request."
    }
}

/**
 * Gets every Bluetooth pairing request, but only while Android has granted Watcher the nearby-devices
 * permission, which the settings switch grants and revokes. It never reads anything but the device and the
 * kind of request, and only acts while that device is really being paired.
 */
class PairingReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
        val device = deviceOf(intent) ?: return
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
        // The sender is the Bluetooth app, so make sure this device is the one being paired and not a made-up request.
        val pairing = try { device.bondState == BluetoothDevice.BOND_BONDING } catch (e: SecurityException) { false }
        if (!pairing) return
        val name = try { device.name } catch (e: SecurityException) { null }
        PairingPrompt.show(context.applicationContext, device.address, name, variant, goAsync())
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
}

/** Opens [PairingActivity] over whatever is on screen, and leaves a notification to tap if Android blocks that. */
internal object PairingPrompt {
    private const val CHANNEL = "pairing"
    private const val NOTIFICATION_ID = 7301

    internal const val EXTRA_ADDRESS = "address"
    internal const val EXTRA_NAME = "name"
    internal const val EXTRA_VARIANT = "variant"

    fun show(context: Context, address: String, name: String?, variant: Int, pending: BroadcastReceiver.PendingResult?) {
        val open = Intent(context, PairingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ADDRESS, address).putExtra(EXTRA_NAME, name).putExtra(EXTRA_VARIANT, variant)
        // LightOS's own prompt crashes and takes a moment to go away; opening ours after it avoids sitting under it.
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { context.startActivity(open) }
            notify(context, open, name)
            pending?.finish()
        }, 700)
    }

    private fun notify(context: Context, open: Intent, name: String?) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bluetooth pairing", NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(context, name, tap)) }
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(context: Context, name: String?, tap: PendingIntent) =
        android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Pairing with ${name ?: "a device"}")
            .setContentText("Tap to type its key.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .build()
}

/**
 * The key entry for a pairing the phone's own prompt can't show. Type what the other device displays and
 * press PAIR. A request that isn't a PIN (compare numbers, tap Allow) can't be answered by an app, so it just says so.
 */
class PairingActivity : Activity() {
    private var address = ""
    private var variant = -1

    @SuppressLint("MissingPermission")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        address = intent.getStringExtra(PairingPrompt.EXTRA_ADDRESS).orEmpty()
        variant = intent.getIntExtra(PairingPrompt.EXTRA_VARIANT, -1)
        val name = intent.getStringExtra(PairingPrompt.EXTRA_NAME)?.takeIf { it.isNotBlank() } ?: "the device"
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        PairingPrompt.clear(this)

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun label(text: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(px(24), px(24), px(24), px(24))
            fitsSystemWindows = true
        }
        root.addView(label("Pair with $name", 22f))

        if (PairingRules.canAnswer(variant)) {
            root.addView(label("Type the key it shows.", 15f, Color.LTGRAY).apply { setPadding(0, px(8), 0, px(16)) })
            val pin = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(PairingRules.MAX_PIN))
                textSize = 40f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                letterSpacing = 0.12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
                imeOptions = EditorInfo.IME_ACTION_DONE
                requestFocus()
            }
            root.addView(pin, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, px(20), 0, 0) }
            row.addView(button("CANCEL") { finish() })
            row.addView(button("PAIR") { send(pin.text.toString()) })
            root.addView(row)
            pin.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { send(pin.text.toString()); true } else false }
        } else {
            root.addView(label(PairingRules.whyNot(variant), 15f, Color.LTGRAY).apply { setPadding(0, px(12), 0, px(20)) })
            root.addView(button("CLOSE") { finish() })
        }
        setContentView(root)
    }

    private fun button(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        val dp = resources.displayMetrics.density
        setPadding((28 * dp).toInt(), (12 * dp).toInt(), (28 * dp).toInt(), (12 * dp).toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.BLACK); setStroke((2 * dp).toInt(), Color.WHITE); cornerRadius = 14 * dp
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins((8 * dp).toInt(), 0, (8 * dp).toInt(), 0) }
    }

    @SuppressLint("MissingPermission")
    private fun send(typed: String) {
        val pin = PairingRules.cleanPin(typed) ?: return toast("Type the key first.")
        val device = runCatching { getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.getRemoteDevice(address) }.getOrNull()
        val ok = try { device?.setPin(pin.toByteArray()) == true } catch (e: SecurityException) { false }
        if (ok) { toast("Key sent."); finish() } else toast("Couldn't send it. Is the pairing still open?")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
