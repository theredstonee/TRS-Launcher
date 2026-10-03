package dev.theredstonee.trs.game.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Startbeschreibung im Spielprozess (aus der JSON-Datei, die das Plugin schreibt). */
internal data class LaunchConfig(
    val session: String,
    val javaHome: String,
    val gameDir: String,
    val assetsDir: String,
    val nativesDir: String,
    val classpath: List<String>,
    val mainClass: String,
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
    val javaMajor: Int,
    /** `mobileglues`, `gl4es` oder `zink` (nie `auto`). */
    val renderer: String,
    val memoryMb: Int,
    val extraEnv: Map<String, String>,
    val touchProfile: String?,
    /** Ordner der Touch-Layouts (`<Launcher-Daten>/controls`). */
    val controlsDir: String?,
    val trsClient: Boolean,
    /** LWJGL-Fork der Engine: `3.3.3` oder `3.4.1`. */
    val lwjgl: String,
    val lwjglx: Boolean,
    /** Spiel nutzt SDL3 statt GLFW (Minecraft 26.3+). */
    val sdl: Boolean = false,
) {
    companion object {
        fun parse(json: JSONObject): LaunchConfig {
            val spec = json.getJSONObject("spec")
            return LaunchConfig(
                session = json.getString("session"),
                javaHome = json.getString("javaHome"),
                gameDir = spec.getString("gameDir"),
                assetsDir = spec.getString("assetsDir"),
                nativesDir = spec.optString("nativesDir", ""),
                classpath = spec.getJSONArray("classpath").strings(),
                mainClass = spec.getString("mainClass"),
                jvmArgs = spec.optJSONArray("jvmArgs").strings(),
                gameArgs = spec.optJSONArray("gameArgs").strings(),
                javaMajor = spec.getInt("javaMajor"),
                renderer = json.optString("renderer", "mobileglues"),
                memoryMb = spec.optInt("memoryMb", 2048),
                extraEnv = spec.optJSONObject("extraEnv").stringMap(),
                touchProfile = spec.optString("touchProfile").takeIf { it.isNotEmpty() && it != "null" },
                controlsDir = spec.optString("controlsDir").takeIf { it.isNotEmpty() && it != "null" },
                trsClient = spec.optBoolean("trsClient", false),
                lwjgl = json.optString("lwjgl", "3.3.3").takeIf { it == "3.4.1" } ?: "3.3.3",
                lwjglx = json.optBoolean("lwjglx", false),
                sdl = spec.optBoolean("usesSdl", false),
            )
        }

        fun read(file: File) = parse(JSONObject(file.readText()))
    }
}

/** Kopflose JVM (Processors, `java -version`). */
internal data class JavaRunConfig(
    val request: String,
    val javaHome: String,
    val javaMajor: Int,
    val classpath: List<String>,
    val mainClass: String,
    val args: List<String>,
    val jvmArgs: List<String>,
    val cwd: String,
    val memoryMb: Int,
) {
    companion object {
        fun parse(json: JSONObject): JavaRunConfig {
            val spec = json.getJSONObject("spec")
            return JavaRunConfig(
                request = json.getString("request"),
                javaHome = json.getString("javaHome"),
                javaMajor = spec.getInt("javaMajor"),
                classpath = spec.optJSONArray("classpath").strings(),
                mainClass = spec.optString("mainClass", ""),
                args = spec.optJSONArray("args").strings(),
                jvmArgs = spec.optJSONArray("jvmArgs").strings(),
                cwd = spec.getString("cwd"),
                memoryMb = spec.optInt("memoryMb", 1024),
            )
        }

        fun read(file: File) = parse(JSONObject(file.readText()))
    }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { getString(it) }
}

private fun JSONObject?.stringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (key in keys()) out[key] = getString(key)
    return out
}
