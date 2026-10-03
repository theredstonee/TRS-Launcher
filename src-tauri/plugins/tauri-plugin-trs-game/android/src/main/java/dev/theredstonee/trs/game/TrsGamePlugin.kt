package dev.theredstonee.trs.game

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import android.util.Log
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Channel
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSArray
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import dev.theredstonee.trs.game.engine.EngineEvents
import dev.theredstonee.trs.game.engine.GameActivity
import dev.theredstonee.trs.game.engine.JavaRunService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@InvokeArg
class LaunchArgs {
    lateinit var session: String
    lateinit var onEvent: Channel
}

@InvokeArg
class StopArgs {
    lateinit var session: String
}

/**
 * Tauri-Seite der Engine (Launcher-Prozess). Startet Spiel-Activity bzw.
 * Java-Dienst in eigenen Prozessen und reicht deren Meldungen weiter.
 */
@TauriPlugin
class TrsGamePlugin(private val activity: Activity) : Plugin(activity) {
    companion object {
        private const val TAG = "TrsGamePlugin"
        private const val GAME_PROCESS = ":trsgame"
        private const val JAVA_PROCESS = ":trsjava"
    }

    /** Sitzung → Kanal für state/log. */
    private val channels = ConcurrentHashMap<String, Channel>()
    /** Auftrag → wartender Aufruf (runJava). */
    private val pendingJava = ConcurrentHashMap<String, Invoke>()
    private val worker = Executors.newSingleThreadExecutor()
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getStringExtra(EngineEvents.EXTRA_SESSION) ?: return
            val lines = intent.getStringArrayListExtra(EngineEvents.EXTRA_LINES) ?: arrayListOf()
            when (intent.getStringExtra(EngineEvents.EXTRA_KIND)) {
                "state" -> {
                    val state = intent.getStringExtra(EngineEvents.EXTRA_STATE) ?: return
                    val payload = JSObject()
                    payload.put("type", "state")
                    payload.put("session", session)
                    payload.put("state", state)
                    if (intent.hasExtra(EngineEvents.EXTRA_EXIT_CODE)) {
                        payload.put("exitCode", intent.getIntExtra(EngineEvents.EXTRA_EXIT_CODE, 0))
                    }
                    payload.put("logTail", JSArray(lines))
                    send(session, payload)
                    if (state == "exited" || state == "crashed") channels.remove(session)
                }
                "log" -> {
                    val payload = JSObject()
                    payload.put("type", "log")
                    payload.put("session", session)
                    payload.put("lines", JSArray(lines))
                    send(session, payload)
                }
                "javaResult" -> {
                    val invoke = pendingJava.remove(session) ?: return
                    val result = JSObject()
                    result.put("exitCode", intent.getIntExtra(EngineEvents.EXTRA_EXIT_CODE, -1))
                    result.put("logTail", JSArray(lines))
                    invoke.resolve(result)
                }
            }
        }
    }

    private fun send(session: String, payload: JSObject) {
        try {
            channels[session]?.send(payload)
        } catch (e: Exception) {
            Log.w(TAG, "Kanal für $session nicht erreichbar", e)
        }
    }

    private fun ensureReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(EngineEvents.ACTION)
        val ctx = activity.applicationContext
        // Gleiche App (andere Prozesse, gleiche UID): nicht exportiert reicht.
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    @Command
    fun launch(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(LaunchArgs::class.java)
            if (!args.session.matches(Regex("^[A-Za-z0-9-]{1,64}$"))) {
                invoke.reject("game.invalidSpec")
                return
            }
            if (isRunning(GAME_PROCESS)) {
                invoke.reject("game.alreadyRunning")
                return
            }
            ensureReceiver()
            // Rohdaten (ohne Kanal) als Datei an den Spielprozess – Intents haben eine Größengrenze.
            val raw = invoke.getArgs()
            raw.remove("onEvent")
            val file = File(activity.cacheDir, "trs-launch-${args.session}.json")
            file.writeText(raw.toString())
            channels[args.session] = args.onEvent
            val intent = Intent(activity, GameActivity::class.java).putExtra(GameActivity.EXTRA_CONFIG, file.absolutePath)
            activity.startActivity(intent)
            invoke.resolve()
        } catch (e: Exception) {
            Log.e(TAG, "Start fehlgeschlagen", e)
            invoke.reject("game.engine")
        }
    }

    @Command
    fun runJava(invoke: Invoke) {
        ensureReceiver()
        val raw = invoke.getArgs()
        // Nacheinander: eine JVM pro Prozess, der Dienst-Prozess muss erst beendet sein.
        worker.execute {
            try {
                waitUntilGone(JAVA_PROCESS)
                val request = "j${System.currentTimeMillis()}-${Process.myPid()}"
                raw.put("request", request)
                val file = File(activity.cacheDir, "trs-java-$request.json")
                file.writeText(raw.toString())
                pendingJava[request] = invoke
                val intent = Intent(activity, JavaRunService::class.java).putExtra(JavaRunService.EXTRA_CONFIG, file.absolutePath)
                if (activity.applicationContext.startService(intent) == null) {
                    pendingJava.remove(request)
                    invoke.reject("game.engine")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Java-Lauf fehlgeschlagen", e)
                invoke.reject("game.engine")
            }
        }
    }

    @Command
    fun stop(invoke: Invoke) {
        val args = invoke.parseArgs(StopArgs::class.java)
        val pid = processPid(GAME_PROCESS)
        if (pid != null) {
            Process.killProcess(pid)
            val payload = JSObject()
            payload.put("type", "state")
            payload.put("session", args.session)
            payload.put("state", "exited")
            payload.put("exitCode", 0)
            payload.put("logTail", JSArray())
            send(args.session, payload)
            channels.remove(args.session)
        }
        invoke.resolve()
    }

    private fun processPid(suffix: String): Int? {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val name = activity.packageName + suffix
        return am.runningAppProcesses?.firstOrNull { it.processName == name }?.pid
    }

    private fun isRunning(suffix: String) = processPid(suffix) != null

    private fun waitUntilGone(suffix: String) {
        repeat(50) {
            if (!isRunning(suffix)) return
            Thread.sleep(100)
        }
        processPid(suffix)?.let { Process.killProcess(it) }
        Thread.sleep(200)
    }
}
