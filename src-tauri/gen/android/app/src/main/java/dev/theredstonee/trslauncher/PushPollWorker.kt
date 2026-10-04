package dev.theredstonee.trslauncher

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.theredstonee.trs.push.Notifier
import io.crates.keyring.Keyring
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONArray

/**
 * Android ohne UnifiedPush-Verteiler: Push-Hinweise etwa alle 15 Minuten abholen (§33.4) und als
 * Benachrichtigung zeigen. Läuft auch, wenn die App geschlossen ist – dann lädt der Worker den Rust-Kern
 * selbst (Datenordner, Konto, TRS-Token; kein Spiel, kein Echtzeit-Kanal).
 */
class PushPollWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    companion object {
        private const val TAG = "TrsPush"
        private const val NAME = "trs-push-poll"
        private const val KEY_ROOT = "root"

        @JvmStatic
        private external fun nativePoll(root: String): String?

        /** Planen (`enabled`) oder abbestellen. [root] = Datenordner des Launchers. */
        fun schedule(context: Context, enabled: Boolean, root: String) {
            val work = WorkManager.getInstance(context.applicationContext)
            if (!enabled) {
                work.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<PushPollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_ROOT to root))
                .build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }

    override fun doWork(): Result {
        val root = inputData.getString(KEY_ROOT) ?: return Result.failure()
        val dataDir = File(applicationContext.applicationInfo.dataDir).canonicalFile
        val rootDir = File(root).canonicalFile
        // Nur der eigene Datenordner der App (Tauri nimmt ihn direkt, Unterordner sind auch in Ordnung).
        val own = rootDir == dataDir || rootDir.path.startsWith(dataDir.path + File.separator)
        if (!own || !rootDir.isDirectory) {
            Log.w(TAG, "Datenordner für das Abholen ungültig: $rootDir (App: $dataDir)")
            return Result.failure()
        }
        return try {
            System.loadLibrary("trs_launcher_lib")
            Keyring.initializeNdkContext(applicationContext)
            val json = nativePoll(rootDir.path) ?: "[]"
            val list = JSONArray(json)
            for (i in 0 until minOf(list.length(), 50)) {
                val entry = list.optJSONObject(i) ?: continue
                Notifier.showJson(applicationContext, entry.toString())
            }
            Result.success()
        } catch (e: Throwable) {
            Log.w(TAG, "Abholen im Hintergrund fehlgeschlagen", e)
            Result.retry()
        }
    }
}
