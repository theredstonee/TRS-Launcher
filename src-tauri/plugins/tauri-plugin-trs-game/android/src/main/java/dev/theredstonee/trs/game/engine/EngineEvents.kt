package dev.theredstonee.trs.game.engine

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import java.util.ArrayDeque

/**
 * Meldungen aus Spiel-/Java-Prozess an den Launcher-Prozess (gleiche App, gleiche
 * UID – Broadcasts nur an das eigene Paket).
 */
internal object EngineEvents {
    const val ACTION = "dev.theredstonee.trs.game.ENGINE_EVENT"
    const val EXTRA_KIND = "kind" // state | log | javaResult
    const val EXTRA_SESSION = "session"
    const val EXTRA_STATE = "state"
    const val EXTRA_EXIT_CODE = "exitCode"
    const val EXTRA_LINES = "lines"

    /** Zeilen für `logTail` bei Ende/Absturz. */
    private const val TAIL = 80
    /** Gesammelt senden, höchstens so viele Zeilen je Paket. */
    private const val BATCH = 200
    private const val FLUSH_MS = 250L
    /** Überlange Zeilen kürzen (Broadcast-Größe). */
    private const val MAX_LINE = 2000

    private val tail = ArrayDeque<String>()
    private val pending = ArrayList<String>()
    private var handler: Handler? = null
    private var session: String = ""
    private lateinit var appContext: Context

    @Synchronized
    fun init(context: Context, sessionId: String) {
        appContext = context.applicationContext
        session = sessionId
        if (handler == null) {
            val thread = HandlerThread("trs-engine-events").apply { start() }
            handler = Handler(thread.looper)
        }
    }

    fun state(state: String, exitCode: Int? = null) {
        flush()
        val intent = base("state").putExtra(EXTRA_STATE, state)
        if (exitCode != null) intent.putExtra(EXTRA_EXIT_CODE, exitCode)
        if (state == "exited" || state == "crashed") {
            intent.putExtra(EXTRA_LINES, synchronized(this) { ArrayList(tail) })
        }
        appContext.sendBroadcast(intent)
    }

    /** Ergebnis einer kopflosen JVM. */
    fun javaResult(request: String, exitCode: Int) {
        flush()
        val intent = base("javaResult")
            .putExtra(EXTRA_SESSION, request)
            .putExtra(EXTRA_EXIT_CODE, exitCode)
            .putExtra(EXTRA_LINES, synchronized(this) { ArrayList(tail) })
        appContext.sendBroadcast(intent)
    }

    /** Eine Log-Zeile (aus dem stdout/stderr-Umleiter); wird gesammelt verschickt. */
    fun log(line: String) {
        val text = if (line.length > MAX_LINE) line.substring(0, MAX_LINE) + "…" else line
        val schedule: Boolean
        synchronized(this) {
            tail.addLast(text)
            while (tail.size > TAIL) tail.removeFirst()
            pending += text
            schedule = pending.size == 1
        }
        if (schedule) handler?.postDelayed({ flush() }, FLUSH_MS)
    }

    fun flush() {
        while (true) {
            val batch: ArrayList<String> = synchronized(this) {
                if (pending.isEmpty()) return
                val n = minOf(BATCH, pending.size)
                val out = ArrayList(pending.subList(0, n))
                pending.subList(0, n).clear()
                out
            }
            appContext.sendBroadcast(base("log").putExtra(EXTRA_LINES, batch))
        }
    }

    private fun base(kind: String): Intent = Intent(ACTION)
        .setPackage(appContext.packageName)
        .putExtra(EXTRA_KIND, kind)
        .putExtra(EXTRA_SESSION, session)
}
