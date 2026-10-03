package dev.theredstonee.trsgame.overlay

import java.io.File

/**
 * Layout-Dateien in `<App-Daten>/controls/<id>.json`. Der Launcher legt die
 * fertigen Layouts (pvp/build/redstone) dort ab; das Overlay liest sie nur
 * und schreibt beim Speichern im Editor dieselbe Datei.
 */
class LayoutStore(private val dir: File) {
    fun file(id: String): File? = if (Layout.isValidId(id)) File(dir, "$id.json") else null

    /** Layout laden; kaputt/fehlt → PvP, sonst eingebautes Notfall-Layout. */
    fun load(id: String?): Layout {
        for (candidate in listOfNotNull(id, DEFAULT_ID).distinct()) {
            read(candidate)?.let { return it }
        }
        return Layout.fallback()
    }

    fun read(id: String): Layout? {
        val f = file(id) ?: return null
        return try {
            if (!f.isFile || f.length() > Json.MAX_CHARS * 4L) return null
            Layout.parse(f.readText(Charsets.UTF_8)).takeIf { it.id == id }
        } catch (_: Exception) {
            null
        }
    }

    /** Speichert atomar (erst Temp-Datei, dann umbenennen). */
    fun save(layout: Layout) {
        Layout.validate(layout)
        val target = file(layout.id) ?: throw Layout.InvalidLayout("id")
        dir.mkdirs()
        val tmp = File(dir, ".${layout.id}.json.tmp")
        tmp.writeText(layout.toJson(), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            // Windows/manche Dateisysteme ersetzen nicht beim Umbenennen.
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("cannot save ${target.name}")
            }
        }
    }

    companion object {
        const val DEFAULT_ID = "pvp"
    }
}

/**
 * Overlay ausblenden, sobald Controller oder echte Maus benutzt werden;
 * die nächste Berührung holt es zurück.
 */
class VisibilityPolicy {
    var hidden = false
        private set

    /** `true` = Sichtbarkeit hat sich geändert. */
    fun onHardwareInput(): Boolean {
        if (hidden) return false
        hidden = true
        return true
    }

    fun onTouch(): Boolean {
        if (!hidden) return false
        hidden = false
        return true
    }
}
