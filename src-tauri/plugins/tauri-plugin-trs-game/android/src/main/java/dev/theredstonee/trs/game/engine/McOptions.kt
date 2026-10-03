package dev.theredstonee.trs.game.engine

import android.util.Log
import java.io.File

/**
 * Minecraft-Optionen vor dem Start anpassen. 26.3+ (SDL3) bekommt Vulkan als bevorzugte
 * Grafik-Schnittstelle, solange der Spieler nichts anderes gewählt hat: Die GL-Übersetzung
 * (MobileGlues) kann die neuen Shader nicht in GLSL ES umsetzen; Minecraft fällt von selbst auf
 * OpenGL zurück, wenn das Gerät Vulkan nicht kann.
 */
internal object McOptions {
    private const val TAG = "TrsGameOptions"
    private const val KEY = "preferredGraphicsBackend"

    fun preferVulkan(file: File) {
        try {
            val lines = if (file.isFile) file.readLines() else emptyList()
            val updated = withVulkan(lines) ?: return
            file.parentFile?.mkdirs()
            file.writeText(updated.joinToString("\n", postfix = "\n"))
            Log.i(TAG, "Vulkan als bevorzugte Grafik-Schnittstelle gesetzt")
        } catch (e: Exception) {
            Log.w(TAG, "options.txt nicht anpassbar", e)
        }
    }

    /** Neue Zeilen oder `null`, wenn der Spieler schon gewählt hat (opengl/vulkan). */
    fun withVulkan(lines: List<String>): List<String>? {
        val index = lines.indexOfFirst { it.startsWith("$KEY:") }
        if (index < 0) return lines + "$KEY:\"vulkan\""
        val value = lines[index].substringAfter(':').trim()
        if (value != "\"default\"" && value.isNotEmpty()) return null
        return lines.toMutableList().also { it[index] = "$KEY:\"vulkan\"" }
    }
}
