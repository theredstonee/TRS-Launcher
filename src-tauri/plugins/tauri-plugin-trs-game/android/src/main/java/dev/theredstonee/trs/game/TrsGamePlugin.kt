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
import dev.theredstonee.trs.game.engine.CrashInfo
import dev.theredstonee.trs.game.engine.EngineEvents
import dev.theredstonee.trs.game.engine.GameActivity
import dev.theredstonee.trs.game.engine.JavaRunService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@InvokeArg
class LaunchArgs {
    lateinit var session: String
    lateinit var onEvent: Channel
}

@InvokeArg
class StopArgs {
    lateinit var session: String
}

@InvokeArg
class SessionEndArgs {
    lateinit var session: String
    var sinceMs: Long = 0
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
        /** Zeilen aus der Log-Datei, wenn der Prozess ohne Ende-Meldung starb. */
        private const val FILE_TAIL = 80
    }

    /** Laufende Spielsitzung: Startzeit (für Androids Ende-Gründe) und ob der Prozess schon lief. */
    private class Running(val session: String, val since: Long) {
        @Volatile var seen = false
    }

    @Volatile private var running: Running? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor()

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
                    if (state == "crashed") {
                        // Androids Ende-Grund (Signal, Stack) liegt erst kurz nach dem Tod vor.
                        val run = running?.takeIf { it.session == session }
                        running = null
                        watchdog.schedule({ finishCrash(session, run?.since ?: 0L, lines, payload) }, 1500, TimeUnit.MILLISECONDS)
                        return
                    }
                    payload.put("logTail", JSArray(lines))
                    send(session, payload)
                    if (state == "exited") {
                        if (running?.session == session) running = null
                        channels.remove(session)
                    }
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

    /** Absturz melden – ergänzt um den Ende-Grund des Spielprozesses (nativer Stack). */
    private fun finishCrash(session: String, since: Long, lines: List<String>, payload: JSObject) {
        val tail = ArrayList(lines)
        if (tail.isEmpty()) tail += CrashInfo.tail(File(activity.cacheDir, "game-$session.log"), FILE_TAIL)
        tail += CrashInfo.lastExit(activity.applicationContext, GAME_PROCESS, since)
        Log.i(TAG, "Absturz gemeldet: ${tail.size} Zeilen")
        payload.put("logTail", JSArray(tail))
        send(session, payload)
        channels.remove(session)
    }

    /**
     * Stirbt der Spielprozess ohne Ende-Meldung (z. B. nativer Absturz vor dem Start der JVM,
     * vom System beendet), meldet der Wächter das Ende selbst.
     */
    private fun watch(run: Running) {
        watchdog.schedule({
            if (running !== run) return@schedule
            val alive = isRunning(GAME_PROCESS)
            if (alive) run.seen = true
            val gone = !alive && (run.seen || System.currentTimeMillis() - run.since > 30_000)
            if (!gone) {
                watch(run)
                return@schedule
            }
            // Spätere Meldungen aus dem Prozess abwarten (Broadcast unterwegs).
            Thread.sleep(1500)
            if (running !== run) return@schedule
            running = null
            Log.w(TAG, "Spielprozess ohne Ende-Meldung beendet")
            val payload = JSObject()
            payload.put("type", "state")
            payload.put("session", run.session)
            payload.put("state", "crashed")
            payload.put("exitCode", -1)
            finishCrash(run.session, run.since, emptyList(), payload)
        }, 2, TimeUnit.SECONDS)
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
            // Androids Ende-Gründe haben Millisekunden-Zeitstempel – etwas Luft nach hinten.
            val run = Running(args.session, System.currentTimeMillis() - 1000)
            running = run
            watch(run)
            // Eigene Aufgabe: Zurück zum Launcher (Symbol, Übersicht) beendet das Spiel nicht.
            val intent = Intent(activity, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_CONFIG, file.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
            running = null
            send(args.session, payload)
            channels.remove(args.session)
        }
        invoke.resolve()
    }

    /**
     * Ende einer Sitzung, deren Meldung den Launcher nicht erreicht hat (Launcher-Prozess war
     * beendet, z. B. vom System bei wenig Speicher): Läuft das Spiel noch? Sonst Grund + Log-Ende.
     */
    @Command
    fun sessionEnd(invoke: Invoke) {
        val args = invoke.parseArgs(SessionEndArgs::class.java)
        if (!args.session.matches(Regex("^[A-Za-z0-9-]{1,64}$"))) {
            invoke.reject("game.invalidSpec")
            return
        }
        worker.execute {
            val result = JSObject()
            if (isRunning(GAME_PROCESS)) {
                result.put("running", true)
                invoke.resolve(result)
                return@execute
            }
            val log = File(activity.cacheDir, "game-${args.session}.log")
            val tail = ArrayList(CrashInfo.tail(log, FILE_TAIL))
            val exit = CrashInfo.lastExitInfo(activity.applicationContext, GAME_PROCESS, args.sinceMs)
            if (exit != null) tail += exit.lines
            val endedAt = exit?.timestamp ?: log.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
            result.put("running", false)
            // Ohne Ende-Grund (Android < 11) lieber kein Absturz behaupten.
            result.put("crashed", exit?.crashed ?: false)
            result.put("endedAtMs", endedAt)
            result.put("logTail", JSArray(tail))
            invoke.resolve(result)
        }
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
