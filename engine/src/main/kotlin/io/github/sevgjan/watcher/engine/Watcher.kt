package io.github.sevgjan.watcher.engine

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri

data class WatchedApp(val packageName: String, val label: String)

/**
 * Lists the apps on the phone that have a launcher icon and closes them.
 *
 * Android only lets an ordinary app close another app's *background* processes
 * (`killBackgroundProcesses`). A real force stop needs a system-only permission, so for that this opens
 * the app's system info screen, which has the Force stop button.
 */
@SuppressLint("StaticFieldLeak") // the application context
object Watcher {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private lateinit var app: Context

    /** Watcher's private folder, for the key it uses with the debug bridge. */
    internal val dir: java.io.File get() = app.filesDir

    internal val packageName: String get() = app.packageName

    internal val context: Context get() = app

    /** Shows a sample code box after [delayMs], so there's time to open a bank app first and see it over that. */
    fun showTestCode(delayMs: Long = 10_000) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ CodeOverlay.show(app, "Test", "482913") }, delayMs)
    }

    internal fun attach(context: Context) {
        app = context.applicationContext
    }

    /** User-installed apps with a launcher icon, A to Z, without Watcher itself. */
    fun apps(): List<WatchedApp> {
        val pm = app.packageManager
        return pm.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .filter { it.packageName != app.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { WatchedApp(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    /** Closes the app's background processes. An app showing a foreground service (a VPN, music) stays. */
    fun close(packageName: String) {
        app.getSystemService(ActivityManager::class.java).killBackgroundProcesses(packageName)
    }

    fun closeAll(packages: List<String>) = packages.forEach(::close)

    /** Android's own app info screen, where Force stop is. */
    fun openInfo(packageName: String) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
    }

    /**
     * Opens one of Android's own settings screens by its Settings action (the full list, the keyboard
     * list, languages). False when nothing on the phone answers it; LightOS keeps the stock Settings app
     * installed but has no icon for it.
     */
    fun openSystemSettings(action: String = android.provider.Settings.ACTION_SETTINGS): Boolean {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { app.startActivity(intent) }.isSuccess
    }
}

/** Starts the engine with the application context when the process starts. */
class EngineInit : ContentProvider() {
    override fun onCreate(): Boolean {
        Watcher.attach(context!!)
        return true
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
