package dev.theredstonee.trs.game.engine

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ende der JVM melden – genau einmal. Wege: Rückkehr von `launchJVM`, der
 * Exit-Trap von libpojavexec (nur Codes ≠ 0, ruft `ExitActivity.showExitMessage`)
 * und der Shutdown-Hook von Android (`System.exit` → Code 0).
 */
object ExitBridge {
    private const val TAG = "TrsGameExit"

    internal enum class Mode { GAME, JAVA }

    @Volatile internal var mode = Mode.GAME
    @Volatile internal var request = ""
    private val reported = AtomicBoolean(false)
    private val hooked = AtomicBoolean(false)

    internal fun install(mode: Mode, request: String = "") {
        this.mode = mode
        this.request = request
        if (hooked.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(Thread { report(0, false) })
        }
    }

    /** Aus `net.kdt.pojavlaunch.ExitActivity.showExitMessage` (Exit-Trap). */
    @JvmStatic
    fun onJvmExit(ctx: Context, code: Int, isSignal: Boolean) {
        report(code, isSignal)
    }

    internal fun report(code: Int, isSignal: Boolean) {
        if (!reported.compareAndSet(false, true)) return
        Log.i(TAG, "JVM beendet: code=$code signal=$isSignal")
        try {
            when (mode) {
                Mode.JAVA -> EngineEvents.javaResult(request, if (isSignal) 128 + code else code)
                Mode.GAME -> EngineEvents.state(if (code == 0 && !isSignal) "exited" else "crashed", code)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ende konnte nicht gemeldet werden", e)
        }
    }
}
