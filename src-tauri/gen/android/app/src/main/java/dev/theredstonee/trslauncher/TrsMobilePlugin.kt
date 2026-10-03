package dev.theredstonee.trslauncher

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import java.io.File

@InvokeArg
class InstallApkArgs {
    lateinit var path: String
}

/**
 * Update-Installation fuer den Kanal `mobile`: Der Rust-Kern laedt und prueft die APK
 * (signiertes Manifest, SHA-256, Groesse) und gibt nur den Pfad im App-Ordner hierher.
 * Installiert wird ueber den PackageInstaller – das System fragt den Nutzer immer.
 * Das Webview darf dieses Plugin nicht direkt aufrufen (keine Rechte in den Capabilities).
 */
@TauriPlugin
class TrsMobilePlugin(private val activity: Activity) : Plugin(activity) {
    companion object {
        private const val TAG = "TrsMobile"
        private const val ACTION_STATUS = "dev.theredstonee.trslauncher.INSTALL_STATUS"
    }

    private var receiverRegistered = false

    /** Rueckmeldung des Installers: Bestaetigungsdialog zeigen oder Ergebnis loggen. */
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(confirm)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Update installiert")
                else -> Log.w(TAG, "Installation beendet: Status $status")
            }
        }
    }

    @Command
    fun installApk(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(InstallApkArgs::class.java)
            val file = File(args.path).canonicalFile
            val dataDir = File(activity.applicationInfo.dataDir).canonicalFile
            // Nur APKs aus dem eigenen App-Ordner.
            if (!file.path.startsWith(dataDir.path + File.separator) || !file.name.endsWith(".apk") || !file.isFile) {
                invoke.reject("invalid_path")
                return
            }
            // „Unbekannte Apps installieren“ fuer den Launcher noch nicht erlaubt: Einstellung oeffnen.
            if (!activity.packageManager.canRequestPackageInstalls()) {
                val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                activity.startActivity(settings)
                invoke.resolve(outcome("permissionRequired"))
                return
            }
            registerReceiver()
            val installer = activity.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(activity.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("trs-launcher.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val intent = Intent(ACTION_STATUS).setPackage(activity.packageName)
                val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(activity, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
                session.commit(pending.intentSender)
            }
            invoke.resolve(outcome("started"))
        } catch (e: Exception) {
            Log.e(TAG, "Installation konnte nicht gestartet werden", e)
            invoke.reject("install_failed")
        }
    }

    private fun outcome(status: String): JSObject {
        val result = JSObject()
        result.put("status", status)
        return result
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            activity.applicationContext,
            statusReceiver,
            IntentFilter(ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }
}
