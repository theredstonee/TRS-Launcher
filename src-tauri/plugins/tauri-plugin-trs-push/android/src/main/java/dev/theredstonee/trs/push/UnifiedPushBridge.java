package dev.theredstonee.trs.push;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

import org.unifiedpush.android.connector.UnifiedPush;

/**
 * Alle Aufrufe des UnifiedPush-Connectors (Java: siehe {@link TrsPushService}).
 * Eine Registrierung je App ({@link #INSTANCE}); die Konten wechseln nur beim Server.
 */
public final class UnifiedPushBridge {
    public static final String INSTANCE = "default";

    private UnifiedPushBridge() {}

    /** Installierte Verteiler: Paketname und App-Name. */
    public static List<String[]> distributors(Context context) {
        PackageManager pm = context.getPackageManager();
        List<String[]> out = new ArrayList<>();
        for (String pkg : UnifiedPush.getDistributors(context)) {
            String name = pkg;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                name = String.valueOf(pm.getApplicationLabel(info));
            } catch (PackageManager.NameNotFoundException ignored) {
                // Paket gerade entfernt: Paketname zeigen.
            }
            out.add(new String[] {pkg, name});
        }
        return out;
    }

    /** Bestätigter Verteiler (hat die Anmeldung angenommen), sonst der gewählte, sonst null. */
    public static String current(Context context) {
        String ack = UnifiedPush.getAckDistributor(context);
        return ack != null ? ack : UnifiedPush.getSavedDistributor(context);
    }

    public static void choose(Context context, String pkg) {
        UnifiedPush.saveDistributor(context, pkg);
    }

    /** Anmelden beim gewählten Verteiler; die Adresse kommt über {@link TrsPushService#onNewEndpoint}. */
    public static void register(Context context, String vapid) {
        UnifiedPush.register(context, INSTANCE, "TRS Launcher", vapid);
    }

    public static void unregister(Context context) {
        UnifiedPush.unregister(context, INSTANCE);
    }
}
