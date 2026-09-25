package dev.theredstonee.trsclient.core.module;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seit welcher Mod-Version es ein Modul bzw. eine Einstellung gibt – Grundlage der „NEU“-Markierung im TRS-Menü
 * (siehe {@link NewMarkers}). Module heißen hier wie ihre ID ({@code "zoom"}), Einstellungen
 * {@code "<modul>.<schlüssel>"} ({@code "trsOnline.sync"}).
 *
 * <p><b>Neue Module/Einstellungen bitte hier mit der kommenden Mod-Version eintragen</b> – ohne Eintrag gilt ein
 * Eintrag als alt (nie „NEU“). Die Werte bis 0.4.0 stammen aus den Release-Tags (mod_version je Release).
 */
public final class NewSince {
	/**
	 * Mod-Version vor den „NEU“-Markierungen: Wer von dieser (oder einer älteren) Version aktualisiert, bekommt
	 * alles Neuere markiert.
	 */
	public static final String LEGACY_BASELINE = "0.5.0";

	private static final Map<String, String> SINCE = new LinkedHashMap<String, String>();

	static {
		add("0.1.0", "fps", "cps", "keystrokes", "ping", "zoom", "fullbright");
		add("0.2.0", "armor", "effects", "coords", "clock", "memory", "server", "packs", "toggleSprint", "toggleSneak",
				"reach", "combo", "speed", "minimap", "crosshair", "hitColor", "freelook", "titleScreen", "oldAnimations",
				"lowFire", "blockOutline", "hitboxes", "noHurtCam", "chat", "autoGg", "textHotkeys", "waypoints");
		add("0.3.0", "trsOnline", "capePhysics");
		add("0.4.0", "colors", "emotes", "redstoneSignal", "redstoneOverlay", "redstoneClock", "clips", "fpsBoost",
				"dynamicFps", "entityCulling", "particles", "worldDetails");
		// Kommende Version (Client-Sync, Einführung, Modul-Pakete):
		add("0.6.0", "trsOnline.sync");
	}

	private NewSince() {
	}

	private static void add(String version, String... ids) {
		for (String id : ids) SINCE.put(id, version);
	}

	/** Weiteren Eintrag setzen (Tests, andere Pakete beim Start). */
	public static synchronized void put(String id, String version) {
		if (id != null && version != null) SINCE.put(id, version);
	}

	/** Version, seit der es {@code id} gibt, oder null (= alt, nie „NEU“). */
	public static synchronized String of(String id) {
		return SINCE.get(id);
	}

	/** Alle Einträge (nur lesen). */
	public static synchronized Map<String, String> all() {
		return Collections.unmodifiableMap(new LinkedHashMap<String, String>(SINCE));
	}

	/** Neueste Version aller Einträge – bei einer Neuinstallation ist nichts neuer als sie. */
	public static synchronized String latest() {
		String best = LEGACY_BASELINE;
		for (String v : SINCE.values()) {
			if (compare(v, best) > 0) best = v;
		}
		return best;
	}

	/** Versionsvergleich "a.b.c" (fehlende Teile = 0, Nicht-Ziffern am Ende eines Teils werden ignoriert). */
	public static int compare(String a, String b) {
		String[] pa = a == null ? new String[0] : a.split("\\.");
		String[] pb = b == null ? new String[0] : b.split("\\.");
		for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
			int x = i < pa.length ? num(pa[i]) : 0;
			int y = i < pb.length ? num(pb[i]) : 0;
			if (x != y) return x < y ? -1 : 1;
		}
		return 0;
	}

	private static int num(String part) {
		int n = 0;
		for (int i = 0; i < part.length() && i < 6; i++) {
			char c = part.charAt(i);
			if (c < '0' || c > '9') break;
			n = n * 10 + (c - '0');
		}
		return n;
	}

	/** Gültige Versionsangabe (für Werte aus Dateien/Sync)? */
	public static boolean valid(String version) {
		return version != null && version.matches("[0-9]{1,4}(\\.[0-9]{1,4}){0,3}");
	}
}
