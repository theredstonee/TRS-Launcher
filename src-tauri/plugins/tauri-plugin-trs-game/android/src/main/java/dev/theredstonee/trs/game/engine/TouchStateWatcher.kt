package dev.theredstonee.trs.game.engine

import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.io.File

/**
 * Tastatur-Wunsch des TRS Clients im Touch-Modus (docs/touch-mode.md, Abschnitt 4b):
 * `<gameDir>/config/trsclient/touch-state.json` mit `seq` – bei neuer `seq` Tastatur zeigen/verbergen.
 * Zusätzlich schreibt die Mod je Änderung eine Log-Zeile ([fromLogLine]); doppelt zeigen schadet nicht.
 */
internal class TouchStateWatcher(gameDir: File, private val keyboard: (Boolean) -> Unit) {
    companion object {
        private const val POLL_MS = 150L
        /** Größer ist die Datei nie (eine Zeile JSON). */
        private const val MAX_BYTES = 4096L
        private const val LOG_SHOW = "[TRS-Touch] keyboard.show"
        private const val LOG_HIDE = "[TRS-Touch] keyboard.hide"

        /** `true`/`false` für die Log-Zeilen der Mod, sonst `null`. */
        fun fromLogLine(line: String): Boolean? = when {
            line.contains(LOG_SHOW) -> true
            line.contains(LOG_HIDE) -> false
            else -> null
        }

        /** `keyboard` einer Zustandsdatei mit neuer `seq` (sonst `null`). */
        fun parse(text: String, lastSeq: Long): Pair<Long, Boolean>? {
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                return null
            }
            if (json.optInt("version", 1) != 1) return null
            val seq = json.optLong("seq", -1)
            if (seq < 0 || seq == lastSeq) return null
            return seq to json.optBoolean("keyboard", false)
        }
    }

    private val file = File(gameDir, "config/trsclient/touch-state.json")
    private val thread = HandlerThread("trs-touch-state")
    private var handler: Handler? = null
    private var lastSeq = -1L
    private var lastModified = 0L

    fun start() {
        // Reste des letzten Starts zählen nicht (die Mod beginnt bei seq 1).
        file.delete()
        thread.start()
        handler = Handler(thread.looper).also { it.post(poll) }
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private val poll = object : Runnable {
        override fun run() {
            try {
                val modified = file.lastModified()
                if (modified != 0L && modified != lastModified && file.length() in 1..MAX_BYTES) {
                    lastModified = modified
                    parse(file.readText(), lastSeq)?.let { (seq, show) ->
                        lastSeq = seq
                        keyboard(show)
                    }
                }
            } catch (_: Exception) {
                // Datei wird gerade ersetzt – beim nächsten Mal.
            }
            handler?.postDelayed(this, POLL_MS)
        }
    }
}
