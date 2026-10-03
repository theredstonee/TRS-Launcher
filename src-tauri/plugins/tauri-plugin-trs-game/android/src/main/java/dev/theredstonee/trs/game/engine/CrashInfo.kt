package dev.theredstonee.trs.game.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Warum ist der Spielprozess beendet? Liest Androids Ende-Gründe (ab Android 11) und bei
 * nativen Abstürzen den Tombstone (ab Android 12) – damit landet die Ursache im Spiel-Log,
 * auch wenn der Prozess vor der ersten Log-Zeile gestorben ist.
 */
internal object CrashInfo {
    private const val TAG = "TrsGameCrash"
    private const val MAX_FRAMES = 24

    /** Letztes Ende des Spielprozesses nach [since] (ms, System-Uhr) als Log-Zeilen. */
    fun lastExit(context: Context, processSuffix: String, since: Long): List<String> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val name = context.packageName + processSuffix
            val all = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            Log.i(TAG, "Ende-Gründe: ${all.joinToString { "${it.processName}@${it.timestamp}/${it.reason}" }} (seit $since)")
            val info = all.firstOrNull { it.processName == name && it.timestamp >= since } ?: return emptyList()
            describe(info)
        } catch (e: Exception) {
            Log.w(TAG, "Ende-Grund nicht lesbar", e)
            emptyList()
        }
    }

    private fun describe(info: ApplicationExitInfo): List<String> {
        val out = ArrayList<String>()
        out += "[TRS] Spielprozess beendet: ${reason(info.reason)} (Status ${info.status})" +
            (info.description?.takeIf { it.isNotBlank() }?.let { " – $it" } ?: "")
        if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= 31) {
            try {
                info.traceInputStream?.use { out += Tombstone.lines(it.readBytes()) }
            } catch (e: Exception) {
                Log.w(TAG, "Tombstone nicht lesbar", e)
            }
        }
        return out
    }

    private fun reason(code: Int): String = when (code) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "nativer Absturz"
        ApplicationExitInfo.REASON_CRASH -> "Java-Absturz"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "zu wenig Arbeitsspeicher (vom System beendet)"
        ApplicationExitInfo.REASON_SIGNALED -> "Signal"
        ApplicationExitInfo.REASON_EXIT_SELF -> "selbst beendet"
        ApplicationExitInfo.REASON_ANR -> "reagiert nicht (ANR)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "zu hoher Verbrauch"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Start fehlgeschlagen"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Berechtigung geändert"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "vom Nutzer beendet"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "abhängiger Prozess beendet"
        ApplicationExitInfo.REASON_OTHER -> "anderer Grund"
        else -> "Grund $code"
    }

    /** Letzte [max] Zeilen einer Log-Datei (höchstens 64 KB vom Ende). */
    fun tail(file: File, max: Int): List<String> {
        if (!file.isFile) return emptyList()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val start = maxOf(0L, len - 64 * 1024)
                val buf = ByteArray((len - start).toInt())
                raf.seek(start)
                raf.readFully(buf)
                val lines = buf.decodeToString().split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
                lines.takeLast(max)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Minimaler Leser für Androids Tombstone-Protobuf (`system/core/debuggerd/proto/tombstone.proto`):
     * Signal, Abbruch-Meldung und der Stack des abgestürzten Threads.
     */
    object Tombstone {
        fun lines(data: ByteArray): List<String> {
            val root = Proto(data)
            var tid = 0L
            var signal = ""
            var abort = ""
            var fault = ""
            val threads = HashMap<Long, ByteArray>()
            for ((field, value) in root.fields()) {
                when (field) {
                    6 -> tid = value as Long
                    10 -> {
                        val sig = Proto(value as ByteArray)
                        var number = 0L
                        var name = ""
                        var codeName = ""
                        var addr: Long? = null
                        for ((f, v) in sig.fields()) {
                            when (f) {
                                1 -> number = v as Long
                                2 -> name = (v as ByteArray).decodeToString()
                                4 -> codeName = (v as ByteArray).decodeToString()
                                9 -> addr = v as Long
                            }
                        }
                        signal = "signal $number ($name)" + (if (codeName.isNotEmpty()) ", $codeName" else "")
                        if (addr != null) fault = "0x" + java.lang.Long.toHexString(addr)
                    }
                    14 -> abort = (value as ByteArray).decodeToString()
                    16 -> {
                        // map<uint32, Thread>: Eintrag mit key=1, value=2
                        var key = -1L
                        var thread: ByteArray? = null
                        for ((f, v) in Proto(value as ByteArray).fields()) {
                            if (f == 1) key = v as Long
                            if (f == 2) thread = v as ByteArray
                        }
                        if (key >= 0 && thread != null) threads[key] = thread
                    }
                }
            }
            val out = ArrayList<String>()
            if (signal.isNotEmpty()) out += "[TRS] $signal" + (if (fault.isNotEmpty()) ", Adresse $fault" else "")
            if (abort.isNotBlank()) out += "[TRS] Abbruch: ${abort.lineSequence().first().take(300)}"
            val thread = threads[tid] ?: return out
            var threadName = ""
            val frames = ArrayList<String>()
            for ((f, v) in Proto(thread).fields()) {
                if (f == 2) threadName = (v as ByteArray).decodeToString()
                if (f == 4 && frames.size < MAX_FRAMES) frames += frame(v as ByteArray, frames.size)
            }
            out += "[TRS] Thread $tid ($threadName):"
            out += frames
            return out
        }

        private fun frame(data: ByteArray, index: Int): String {
            var relPc = 0L
            var function = ""
            var offset = 0L
            var file = ""
            for ((f, v) in Proto(data).fields()) {
                when (f) {
                    1 -> relPc = v as Long
                    4 -> function = (v as ByteArray).decodeToString()
                    5 -> offset = v as Long
                    6 -> file = (v as ByteArray).decodeToString()
                }
            }
            val fn = if (function.isNotEmpty()) " ($function+$offset)" else ""
            return "[TRS]   #%02d pc %s %s%s".format(index, java.lang.Long.toHexString(relPc), file.substringAfterLast('/'), fn)
        }
    }

    /** Protobuf-Felder einer Nachricht: Varint → Long, längenbegrenzt → ByteArray, Rest übersprungen. */
    class Proto(private val data: ByteArray) {
        fun fields(): List<Pair<Int, Any>> {
            val out = ArrayList<Pair<Int, Any>>()
            var pos = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (pos < data.size && shift < 64) {
                    val b = data[pos++].toInt() and 0xff
                    result = result or ((b and 0x7f).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
                throw IllegalArgumentException("Varint kaputt")
            }
            while (pos < data.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> out += field to varint()
                    1 -> { require(pos + 8 <= data.size); pos += 8 }
                    2 -> {
                        val len = varint()
                        require(len >= 0 && pos + len <= data.size) { "Länge kaputt" }
                        out += field to data.copyOfRange(pos, pos + len.toInt())
                        pos += len.toInt()
                    }
                    5 -> { require(pos + 4 <= data.size); pos += 4 }
                    else -> throw IllegalArgumentException("Wire-Typ unbekannt")
                }
            }
            return out
        }
    }
}
