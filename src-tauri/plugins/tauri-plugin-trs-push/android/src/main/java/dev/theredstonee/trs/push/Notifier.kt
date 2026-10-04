package dev.theredstonee.trs.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Zeigt Hinweise als Android-Benachrichtigung: ein Kanal je Kategorie (der Nutzer kann sie in den
 * System-Einstellungen einzeln stummschalten), gleiche `collapse`-Werte ersetzen sich, ein Tipp öffnet
 * die passende Seite der App über `trs-launcher://notify/…`.
 */
object Notifier {
    private const val TAG = "TrsPush"
    private const val ID = 1

    /** Kategorie → (Name, Wichtigkeit). Unbekannte landen in „Sonstiges“. */
    private fun channel(context: Context, category: String): Pair<String, Int> {
        val r = context.resources
        return when (category) {
            "chat" -> r.getString(R.string.trs_push_channel_chat) to NotificationManager.IMPORTANCE_HIGH
            "friends" -> r.getString(R.string.trs_push_channel_friends) to NotificationManager.IMPORTANCE_DEFAULT
            "friend_online" -> r.getString(R.string.trs_push_channel_friend_online) to NotificationManager.IMPORTANCE_LOW
            "invites" -> r.getString(R.string.trs_push_channel_invites) to NotificationManager.IMPORTANCE_HIGH
            "hosting" -> r.getString(R.string.trs_push_channel_hosting) to NotificationManager.IMPORTANCE_HIGH
            "packs" -> r.getString(R.string.trs_push_channel_packs) to NotificationManager.IMPORTANCE_DEFAULT
            "team" -> r.getString(R.string.trs_push_channel_team) to NotificationManager.IMPORTANCE_DEFAULT
            "achievements" -> r.getString(R.string.trs_push_channel_achievements) to NotificationManager.IMPORTANCE_LOW
            else -> r.getString(R.string.trs_push_channel_other) to NotificationManager.IMPORTANCE_DEFAULT
        }
    }

    private val KNOWN = setOf("chat", "friends", "friend_online", "invites", "hosting", "packs", "team", "achievements")

    private fun channelId(category: String) = "trs_" + if (category in KNOWN) category else "other"

    /** Kanal anlegen (bzw. Namen in der aktuellen Sprache auffrischen). */
    private fun ensureChannel(context: Context, category: String): String {
        val id = channelId(category)
        if (Build.VERSION.SDK_INT >= 26) {
            val (name, importance) = channel(context, category)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(NotificationChannel(id, name, importance))
        }
        return id
    }

    @JvmStatic
    fun showJson(context: Context, json: String) {
        val payload = Payload.parse(json)
        if (payload == null) {
            Log.w(TAG, "Ungültige Push-Nachricht verworfen")
            return
        }
        show(context, payload)
    }

    fun show(context: Context, payload: Payload) {
        if (!PushPrefs.firstTime(context, payload.id)) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Log.i(TAG, "Benachrichtigungen sind für die App ausgeschaltet")
            return
        }
        val channel = ensureChannel(context, payload.category)
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(payload.link()))
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tag = payload.collapse ?: payload.id
        val pending = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.trs_push_icon)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(if (payload.category == "chat") NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
            .setColor(0xFFD93A2B.toInt())
            .build()
        try {
            manager.notify(tag, ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS wurde gerade entzogen.
            Log.w(TAG, "Benachrichtigung nicht erlaubt", e)
        }
    }
}
