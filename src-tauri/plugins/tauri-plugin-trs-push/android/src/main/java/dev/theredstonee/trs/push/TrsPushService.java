package dev.theredstonee.trs.push;

import android.util.Log;

import java.nio.charset.StandardCharsets;

import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.PushService;
import org.unifiedpush.android.connector.data.PublicKeySet;
import org.unifiedpush.android.connector.data.PushEndpoint;
import org.unifiedpush.android.connector.data.PushMessage;

/**
 * Empfang vom UnifiedPush-Verteiler – läuft auch bei geschlossener App.
 * Der Connector hat die Nachricht schon entschlüsselt (RFC 8291, Schlüssel im Android Keystore).
 * In Java, weil der Connector mit Kotlin 2.2 gebaut ist (siehe build.gradle.kts).
 */
public class TrsPushService extends PushService {
    private static final String TAG = "TrsPush";

    @Override
    public void onNewEndpoint(PushEndpoint endpoint, String instance) {
        PublicKeySet keys = endpoint.getPubKeySet();
        if (keys == null) {
            Log.w(TAG, "Neue Adresse ohne Web-Push-Schlüssel – verworfen");
            return;
        }
        // Die Adresse ist ein Geheimnis: nicht ins Log.
        PushPrefs.saveEndpoint(this, endpoint.getUrl(), keys.getPubKey(), keys.getAuth(), endpoint.getTemporary());
        Log.i(TAG, "Neue Push-Adresse vom Verteiler");
    }

    @Override
    public void onMessage(PushMessage message, String instance) {
        if (!message.getDecrypted()) {
            Log.w(TAG, "Nachricht nicht entschlüsselbar – verworfen");
            return;
        }
        Notifier.showJson(this, new String(message.getContent(), StandardCharsets.UTF_8));
    }

    @Override
    public void onRegistrationFailed(FailedReason reason, String instance) {
        Log.w(TAG, "Anmeldung beim Verteiler fehlgeschlagen: " + reason.name());
        PushPrefs.saveFailure(this, reason.name());
    }

    @Override
    public void onUnregistered(String instance) {
        Log.i(TAG, "Vom Verteiler abgemeldet");
        PushPrefs.clearEndpoint(this);
    }

    @Override
    public void onTempUnavailable(String instance) {
        Log.i(TAG, "Verteiler vorübergehend nicht erreichbar");
    }
}
