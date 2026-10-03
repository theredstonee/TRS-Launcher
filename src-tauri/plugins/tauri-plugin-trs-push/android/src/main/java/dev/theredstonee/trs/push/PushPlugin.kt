package dev.theredstonee.trs.push

import android.app.Activity
import android.os.Build
import android.util.Log
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSArray
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin

@InvokeArg
class RegisterArgs {
    var vapid: String? = null
    var distributor: String? = null
}

@InvokeArg
class NotificationArg {
    lateinit var id: String
    lateinit var category: String
    lateinit var title: String
    lateinit var body: String
    lateinit var target: String
    var collapse: String? = null
}

@InvokeArg
class NotifyArgs {
    var notifications: Array<NotificationArg> = emptyArray()
}

/**
 * Push über UnifiedPush. Nur der Rust-Kern ruft dieses Plugin auf (keine Rechte für das Webview):
 * `state` (Verteiler, Adresse, Schlüssel), `register`/`unregister` beim Verteiler, `notify` für geholte
 * Hinweise. Empfangen und anzeigen macht [TrsPushService] – auch ohne laufende App.
 */
@TauriPlugin
class PushPlugin(private val activity: Activity) : Plugin(activity) {
    companion object {
        private const val TAG = "TrsPush"
        /** So lange wartet `register` auf die Adresse des Verteilers. */
        private const val WAIT_MS = 8000L
        private val VAPID = Regex("^[A-Za-z0-9_-]{87}$")
    }

    private fun state(): JSObject {
        val ctx = activity.applicationContext
        val result = JSObject()
        val list = JSArray()
        for (d in UnifiedPushBridge.distributors(ctx)) {
            val o = JSObject()
            o.put("id", d[0])
            o.put("name", d[1])
            list.put(o)
        }
        result.put("distributors", list)
        result.put("distributor", UnifiedPushBridge.current(ctx))
        PushPrefs.endpoint(ctx)?.let {
            result.put("endpoint", it.url)
            result.put("p256dh", it.p256dh)
            result.put("auth", it.auth)
            result.put("temporary", it.temporary)
        }
        result.put("failure", PushPrefs.failure(ctx))
        result.put("deviceName", deviceName())
        return result
    }

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        val name = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
        return name.trim().ifEmpty { "Android" }.take(64)
    }

    @Command
    fun state(invoke: Invoke) {
        invoke.resolve(state())
    }

    @Command
    fun register(invoke: Invoke) {
        val args = invoke.parseArgs(RegisterArgs::class.java)
        val ctx = activity.applicationContext
        val vapid = args.vapid?.takeIf { VAPID.matches(it) }
        val installed = UnifiedPushBridge.distributors(ctx).map { it[0] }
        val wanted = args.distributor?.takeIf { it in installed }
        when {
            wanted != null -> UnifiedPushBridge.choose(ctx, wanted)
            UnifiedPushBridge.current(ctx) != null -> {}
            installed.size == 1 -> UnifiedPushBridge.choose(ctx, installed[0])
            installed.isEmpty() -> {
                invoke.reject("no_distributor", "no_distributor")
                return
            }
            else -> {
                invoke.reject("choose_distributor", "choose_distributor")
                return
            }
        }
        val before = PushPrefs.endpoint(ctx)
        PushPrefs.clearFailure(ctx)
        try {
            UnifiedPushBridge.register(ctx, vapid)
        } catch (e: Exception) {
            Log.e(TAG, "Anmeldung beim Verteiler nicht möglich", e)
            invoke.reject("register_failed", "register_failed")
            return
        }
        // Auf die (neue) Adresse warten – der Verteiler antwortet meist in unter einer Sekunde.
        Thread {
            val until = System.currentTimeMillis() + WAIT_MS
            while (System.currentTimeMillis() < until) {
                val now = PushPrefs.endpoint(ctx)
                if (PushPrefs.failure(ctx) != null || (now != null && (before == null || now != before || wanted == null))) break
                try {
                    Thread.sleep(200)
                } catch (e: InterruptedException) {
                    break
                }
            }
            invoke.resolve(state())
        }.start()
    }

    @Command
    fun unregister(invoke: Invoke) {
        val ctx = activity.applicationContext
        try {
            UnifiedPushBridge.unregister(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "Abmelden beim Verteiler fehlgeschlagen", e)
        }
        PushPrefs.clearEndpoint(ctx)
        invoke.resolve()
    }

    @Command
    fun notify(invoke: Invoke) {
        val args = invoke.parseArgs(NotifyArgs::class.java)
        val ctx = activity.applicationContext
        for (n in args.notifications.take(50)) {
            val o = org.json.JSONObject()
            o.put("v", 1)
            o.put("id", n.id)
            o.put("category", n.category)
            o.put("title", n.title)
            o.put("body", n.body)
            o.put("target", n.target)
            o.put("collapse", n.collapse ?: org.json.JSONObject.NULL)
            Payload.from(o)?.let { Notifier.show(ctx, it) }
        }
        invoke.resolve()
    }

    /** Schon als Benachrichtigung gezeigte Ereignisse – die App holt sie im Echtzeit-Kanal still nach. */
    @Command
    fun shownIds(invoke: Invoke) {
        val list = JSArray()
        for (id in PushPrefs.seenIds(activity.applicationContext)) list.put(id)
        val result = JSObject()
        result.put("ids", list)
        invoke.resolve(result)
    }

    /** Den gewählten Verteiler öffnen (ntfy verbindet sich erst nach dem ersten Öffnen mit seinem Server). */
    @Command
    fun openDistributor(invoke: Invoke) {
        val ctx = activity.applicationContext
        val pkg = UnifiedPushBridge.current(ctx) ?: UnifiedPushBridge.distributors(ctx).firstOrNull()?.get(0)
        val intent = pkg?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
        if (intent == null) {
            invoke.reject("no_distributor", "no_distributor")
            return
        }
        activity.startActivity(intent)
        invoke.resolve()
    }

    /** Android: Das Abholen im Hintergrund plant die App (WorkManager im App-Modul). */
    @Command
    fun setPoll(invoke: Invoke) {
        invoke.resolve()
    }
}
