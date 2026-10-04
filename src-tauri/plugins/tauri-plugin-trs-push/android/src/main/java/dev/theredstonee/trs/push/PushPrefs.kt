package dev.theredstonee.trs.push

import android.content.Context
import android.content.SharedPreferences

/**
 * Was der Verteiler zuletzt gemeldet hat (Adresse + öffentliche Schlüssel, letzter Fehler) und welche
 * Hinweise schon gezeigt wurden. Liegt in den privaten Einstellungen der App; der private Schlüssel
 * bleibt beim Connector (mit einem Android-Keystore-Schlüssel versiegelt).
 */
object PushPrefs {
    private const val FILE = "trs_push"
    private const val SEEN_MAX = 100

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    data class Endpoint(val url: String, val p256dh: String, val auth: String, val temporary: Boolean)

    @JvmStatic
    fun saveEndpoint(context: Context, url: String, p256dh: String, auth: String, temporary: Boolean) {
        prefs(context).edit()
            .putString("endpoint", url)
            .putString("p256dh", p256dh)
            .putString("auth", auth)
            .putBoolean("temporary", temporary)
            .remove("failure")
            .apply()
    }

    @JvmStatic
    fun saveFailure(context: Context, reason: String) {
        prefs(context).edit().putString("failure", reason).apply()
    }

    @JvmStatic
    fun clearEndpoint(context: Context) {
        prefs(context).edit().remove("endpoint").remove("p256dh").remove("auth").remove("temporary").apply()
    }

    fun clearFailure(context: Context) {
        prefs(context).edit().remove("failure").apply()
    }

    fun endpoint(context: Context): Endpoint? {
        val p = prefs(context)
        val url = p.getString("endpoint", null) ?: return null
        val key = p.getString("p256dh", null) ?: return null
        val auth = p.getString("auth", null) ?: return null
        return Endpoint(url, key, auth, p.getBoolean("temporary", false))
    }

    fun failure(context: Context): String? = prefs(context).getString("failure", null)

    /** Zuletzt gezeigte Hinweise (Ereignis-IDs, älteste zuerst). */
    fun seenIds(context: Context): List<String> =
        prefs(context).getString("seen", "")!!.split('\n').filter { it.isNotEmpty() }

    /** `true`, wenn [id] neu ist (und merkt sie sich) – doppelte Zustellung zeigt nichts zweimal. */
    @Synchronized
    fun firstTime(context: Context, id: String): Boolean {
        val p = prefs(context)
        val seen = p.getString("seen", "")!!.split('\n').filter { it.isNotEmpty() }
        if (id in seen) return false
        val next = (seen + id).takeLast(SEEN_MAX)
        p.edit().putString("seen", next.joinToString("\n")).commit()
        return true
    }
}
