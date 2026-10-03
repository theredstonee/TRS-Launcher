package dev.theredstonee.trs.push

import org.json.JSONObject

/** Inhalt einer Push-Nachricht (§33.5), geprüft wie im Rust-Kern. */
data class Payload(
    val id: String,
    val category: String,
    val title: String,
    val body: String,
    val target: String,
    val collapse: String?,
) {
    /** Link, den ein Tipp auf die Benachrichtigung öffnet (die App leitet ihn auf ihre Seite um). */
    fun link(): String = "trs-launcher://notify$target"

    companion object {
        private val ID = Regex("^[A-Za-z0-9._-]{3,64}$")
        private val CATEGORY = Regex("^[a-z_]{1,32}$")
        private val TARGET = Regex("^/[a-z]{1,24}(/[A-Za-z0-9_-]{1,64}){0,3}$")
        private val COLLAPSE = Regex("^[A-Za-z0-9:_.-]{1,120}$")

        /** Steuer- und Formatzeichen raus, Leerraum zusammenfassen, kürzen. */
        fun clean(text: String, max: Int): String {
            val kept = text.filter { c ->
                !Character.isISOControl(c) &&
                    Character.getType(c) != Character.FORMAT.toInt() &&
                    Character.getType(c) != Character.PRIVATE_USE.toInt()
            }
            val single = kept.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            return if (single.length > max) single.substring(0, max) else single
        }

        fun parse(json: String): Payload? = try {
            if (json.length > 8192) null else from(JSONObject(json))
        } catch (e: Exception) {
            null
        }

        fun from(o: JSONObject): Payload? {
            if (o.optInt("v", 0) != 1) return null
            val id = o.optString("id")
            val category = o.optString("category")
            val target = o.optString("target")
            if (!ID.matches(id) || !CATEGORY.matches(category) || !TARGET.matches(target)) return null
            val collapse = if (o.isNull("collapse")) null else o.optString("collapse").takeIf { COLLAPSE.matches(it) }
            val title = clean(o.optString("title"), 80)
            if (title.isEmpty()) return null
            return Payload(id, category, title, clean(o.optString("body"), 200), target, collapse)
        }
    }
}
