package dev.theredstonee.trsclient.core.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seit welcher Mod-Version es ein Modul bzw. eine Einstellung gibt – Grundlage der „NEU“-Markierung im TRS-Menü
 * (siehe {@link NewMarkers}). Module heißen hier wie ihre ID ({@code "zoom"}), Einstellungen
 * {@code "<modul>.<schlüssel>"} ({@code "trsOnline.sync"}).
 *
 * <p>Außer Modulen und Einstellungen gibt es noch drei Arten von Einträgen:
 * <ul>
 *   <li>Bereiche der Menü-Leiste bzw. der Seitenleiste des Startbildschirms ({@code "menu:<bereich>"}, siehe
 *   {@link #MENU_WARDROBE} …) – verlieren das Schild beim Öffnen.</li>
 *   <li>Tastenbelegungen ({@code "key.trsclient.<name>"}) – markiert im Einführungsschritt „Tastenbelegung“.</li>
 *   <li>Zusatzbereiche einer Modulseite ({@link #extras(String)}, z. B. {@link #FPS_MODE}) – zählen für die Kachel
 *   wie eine Einstellung.</li>
 * </ul>
 *
 * <p><b>Neue Module/Einstellungen/Bereiche bitte hier mit der kommenden Mod-Version eintragen</b> – ohne Eintrag
 * gilt ein Eintrag als alt (nie „NEU“; ein Test prüft, dass jedes Modul eingetragen ist). Die Werte bis 0.5.0
 * stammen aus den Release-Tags (mod_version je Release).
 */
public final class NewSince {
	/**
	 * Mod-Version vor den „NEU“-Markierungen: Wer von dieser (oder einer älteren) Version aktualisiert, bekommt
	 * alles Neuere markiert.
	 */
	public static final String LEGACY_BASELINE = "0.5.0";

	/** Kommende Mod-Version (alles, was seit dem letzten Release dazukam). */
	public static final String NEXT = "0.6.0";

	/** Menü-Bereiche (Leiste im TRS-Menü, Seitenleiste des Startbildschirms). */
	public static final String MENU_WARDROBE = "menu:wardrobe";
	public static final String MENU_ACCOUNTS = "menu:accounts";
	public static final String MENU_FRIENDS = "menu:friends";
	public static final String MENU_CLIPS = "menu:clips";
	/** Modul-Pakete + „Einführung erneut starten“. */
	public static final String MENU_PACKS = "menu:modPacks";
	/** Taste „Garderobe öffnen“. */
	public static final String KEY_WARDROBE = "key.trsclient.wardrobe";
	/** Garderobe → Umhänge: „Mit Freund teilen“ (Umhänge mit Freunden teilen). */
	public static final String WARDROBE_CAPE_SHARE = "wardrobe:capeShare";
	/** Clips &amp; Bilder: Vorschau eines Clips im Spiel + „Im Launcher öffnen“. */
	public static final String CLIPS_PREVIEW = "clips:preview";
	/** Grafik-Modus „Schön / Max FPS“ auf der Seite „FPS-Boost“. */
	public static final String FPS_MODE = "fpsBoost.graphicsMode";
	/** Karten-Paket: flüssige Minimap, Weltkarte, Höhlenansicht, Fair Play. */
	public static final String MAPS = "0.7.0";
	public static final String KEY_WORLD_MAP = "key.trsclient.worldMap";
	/** Sozial-Paket (TRS Client 0.8.0): Chat im Spiel, Echtzeit, Benachrichtigungen, Schnellantwort, Meldungen. */
	public static final String SOCIAL = "0.8.0";
	/** Leisten-/Pausen-Eintrag „Sozial“ (ersetzt „Freunde“). */
	public static final String MENU_SOCIAL = "menu:social";
	public static final String KEY_SOCIAL = "key.trsclient.social";
	public static final String KEY_QUICK_REPLY = "key.trsclient.quickReply";
	/** Schild-Position (nach TRS Client 0.8.0): Schild in der 1. Person seitlich/tiefer, eigene Block-Haltung. */
	public static final String SHIELD = "0.8.1";

	private static final Map<String, String> SINCE = new LinkedHashMap<String, String>();
	/** Zusatzbereiche je Modul (Modul-ID → Einträge). */
	private static final Map<String, List<String>> EXTRAS = new LinkedHashMap<String, List<String>>();

	static {
		add("0.1.0", "fps", "cps", "keystrokes", "ping", "zoom", "fullbright");
		add("0.2.0", "armor", "effects", "coords", "clock", "memory", "server", "packs", "toggleSprint", "toggleSneak",
				"reach", "combo", "speed", "minimap", "crosshair", "hitColor", "freelook", "titleScreen", "oldAnimations",
				"lowFire", "blockOutline", "hitboxes", "noHurtCam", "chat", "autoGg", "textHotkeys", "waypoints");
		add("0.3.0", "trsOnline", "capePhysics");
		add("0.4.0", "colors", "emotes", "redstoneSignal", "redstoneOverlay", "redstoneClock", "clips", "fpsBoost",
				"dynamicFps", "entityCulling", "particles", "worldDetails");
		// Kommende Version: Client-Sync, Einführung + Modul-Pakete, Garderobe, Menü-Stil, Freunde, Clips & Bilder,
		// Konten im Spiel, eingebaute Optimierungen, Grafik-Modus.
		add(NEXT, "trsOnline.sync");
		add(NEXT, "menuStyle", "menuStyle.pause", "menuStyle.pauseButtons", "menuStyle.multiplayer", "menuStyle.loading",
				"menuStyle.options", "menuStyle.worlds");
		add(NEXT, "builtinOptimizations");
		add(NEXT, FPS_MODE);
		extra("fpsBoost", FPS_MODE);
		add(NEXT, MENU_WARDROBE, MENU_ACCOUNTS, MENU_FRIENDS, MENU_CLIPS, MENU_PACKS, KEY_WARDROBE);
		add(MAPS, "worldMap", "minimap.fairPlay", "minimap.shape", "minimap.opacity", "minimap.caveMode", "minimap.showDeath",
				"minimap.showFriends", "minimap.showHostile", "minimap.showPassive", "minimap.compass", "minimap.biome",
				"minimap.time", KEY_WORLD_MAP);
		// TRS Client 0.7.1: Umhänge mit Freunden teilen.
		add("0.7.1", WARDROBE_CAPE_SHARE);
		// TRS Client 0.7.2: Clip-Vorschau im Spiel und „Im Launcher öffnen“.
		add("0.7.2", CLIPS_PREVIEW);
		// TRS Client 0.8.0: Sozial (Chat, Gruppen, Bilder, Einladungen, Toasts, Schnellantwort, Meldungen).
		add(SOCIAL, "social", MENU_SOCIAL, KEY_SOCIAL, KEY_QUICK_REPLY);
		// Nach 0.8.0: Schild-Position (Vorlagen, eigene Regler, weiches Blocken, durchsichtig beim Blocken).
		add(SHIELD, "shieldPosition");
	}

	private NewSince() {
	}

	private static void add(String version, String... ids) {
		for (String id : ids) SINCE.put(id, version);
	}

	private static void extra(String moduleId, String id) {
		List<String> list = EXTRAS.get(moduleId);
		if (list == null) {
			list = new ArrayList<String>();
			EXTRAS.put(moduleId, list);
		}
		if (!list.contains(id)) list.add(id);
	}

	/** Zusatzbereiche einer Modulseite, die eine eigene „NEU“-Markierung haben (leer = keine). */
	public static synchronized List<String> extras(String moduleId) {
		List<String> list = EXTRAS.get(moduleId);
		return list == null ? Collections.<String>emptyList() : new ArrayList<String>(list);
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
