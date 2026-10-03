package dev.theredstonee.trs.game.engine

import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import android.view.View
import java.io.File

/**
 * Touch-Overlay über der Spielfläche. Das Touch-Steuerungs-Modul meldet seine
 * Implementierung per Manifest an (läuft im Spielprozess):
 *
 * ```xml
 * <meta-data android:name="dev.theredstonee.trs.game.OVERLAY_PROVIDER"
 *            android:value="com.example.MyOverlayProvider" />
 * ```
 * Die Klasse braucht einen parameterlosen Konstruktor. Ohne Eintrag nutzt die
 * Engine das eingebaute [FallbackOverlay].
 */
interface OverlayProvider {
    /** Erzeugt das Overlay (Vollbild-View über dem Spiel). [profile] = `touchProfile` der Startbeschreibung. */
    fun createOverlay(activity: Activity, input: GameInput, profile: String?): View

    /** Wie oben, mit dem Ordner der Touch-Layouts (`controlsDir` der Startbeschreibung, `null` = unbekannt). */
    fun createOverlay(activity: Activity, input: GameInput, profile: String?, controlsDir: File?): View =
        createOverlay(activity, input, profile)

    /** Maus gefangen (im Spiel) oder frei (Menü). */
    fun onGrabChanged(grabbed: Boolean) {}

    /** Controller/Maus/Tastatur benutzt: Overlay ausblenden (`true`) bzw. nach Touch wieder zeigen. */
    fun onHardwareInput(active: Boolean) {}

    /** Ränder (Notch/Navigationsleiste) in Pixeln. */
    fun onSafeInsets(left: Int, top: Int, right: Int, bottom: Int) {}

    /** Editor der Steuerung an/aus (z. B. aus dem Pause-Menü). */
    fun setEditing(editing: Boolean) {}
}

internal object OverlayProviders {
    const val META_KEY = "dev.theredstonee.trs.game.OVERLAY_PROVIDER"
    private const val TAG = "TrsGameOverlay"

    fun load(activity: Activity): OverlayProvider {
        val name = try {
            activity.packageManager
                .getApplicationInfo(activity.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString(META_KEY)
        } catch (e: Exception) {
            null
        }
        if (name.isNullOrBlank()) return FallbackOverlay()
        return try {
            Class.forName(name).getDeclaredConstructor().newInstance() as OverlayProvider
        } catch (e: Exception) {
            Log.w(TAG, "Overlay-Provider $name nicht ladbar, nehme eingebautes Overlay", e)
            FallbackOverlay()
        }
    }
}
