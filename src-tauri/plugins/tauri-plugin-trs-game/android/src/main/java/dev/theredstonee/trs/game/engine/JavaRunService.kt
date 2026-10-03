package dev.theredstonee.trs.game.engine

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import net.kdt.pojavlaunch.Logger
import java.io.File

/**
 * Kopflose JVM im eigenen Prozess `:trsjava` (Forge-/NeoForge-Processors,
 * `java -version`). Eine JVM pro Prozess – nach dem Lauf beendet sich der Prozess.
 */
class JavaRunService : Service() {
    companion object {
        private const val TAG = "TrsJavaRun"
        internal const val EXTRA_CONFIG = "dev.theredstonee.trs.game.JAVA_CONFIG"

        @Volatile private var started = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val file = intent?.getStringExtra(EXTRA_CONFIG)?.let(::File)
        val config = try {
            file?.takeIf { it.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator) }?.let(JavaRunConfig::read)
        } catch (e: Exception) {
            Log.e(TAG, "Auftrag unlesbar", e)
            null
        }
        if (config == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (started) {
            // Zweiter Auftrag im selben Prozess geht nicht (eine JVM pro Prozess).
            EngineEvents.init(this, config.request)
            EngineEvents.javaResult(config.request, -2)
            return START_NOT_STICKY
        }
        started = true
        EngineEvents.init(this, config.request)
        ExitBridge.install(ExitBridge.Mode.JAVA, config.request)
        JvmLauncher.loadNatives()
        val log = File(cacheDir, "java-${config.request}.log").apply { writeText("") }
        Logger.begin(log.absolutePath)
        Logger.addLogListener { chunk ->
            for (line in chunk.split('\n')) if (line.isNotEmpty()) EngineEvents.log(line)
        }
        Thread({
            var code = 1
            try {
                code = JvmLauncher.runHeadless(applicationContext, config)
            } catch (t: Throwable) {
                Log.e(TAG, "JVM-Start fehlgeschlagen", t)
                EngineEvents.log("[TRS] Engine-Fehler: ${t.javaClass.simpleName}: ${t.message}")
            }
            // stdout-Rest abwarten, dann melden und den Prozess beenden.
            System.out.flush()
            Thread.sleep(150)
            ExitBridge.report(code, false)
            Process.killProcess(Process.myPid())
        }, "JVM Main thread").start()
        return START_NOT_STICKY
    }
}
