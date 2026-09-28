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

	/** Welt-Hosting (TRS Client 0.9.0): Welt für Freunde öffnen, Beitritt per Einladung/Code, öffentlicher Link. */
	public static final String HOSTING = "0.9.0";

	/** Ping &amp; Latenz (TRS Client 0.10.0): echte Ping-Anzeige, Netzwerk-Optimierung, niedrige Eingabeverzögerung. */
	public static final String LATENCY = "0.10.0";

	/**
	 * Komfort- und PvP-Paket (nächste Version nach TRS Client 0.9.1): Erwähnungen, Chat-Filter, Auto-Reconnect,
	 * Warteschlange &amp; Hinweise, Scoreboard/Tab/Bossleiste/Titel, Warnungen, Zähler, Treffer-Feedback, Streamer-Modus.
	 */
	public static final String QOL = "0.10.0";

	/** Teilen-Paket (TRS Client 0.11.0): Wegpunkte teilen, Koordinaten im Chat anklicken, Bildschirmfotos als Link. */
	public static final String SHARE = "0.11.0";
	/** Sozial → Eingabe: „Wegpunkt teilen“. */
	public static final String SOCIAL_WAYPOINT = "social:waypoint";
	/** Clips &amp; Bilder: „Als Link teilen“ + „Meine geteilten Bilder“. */
	public static final String CLIPS_SHARE = "clips:share";
	/** Komfort-Paket 2 (TRS Client 0.11.0): bessere Tooltips, Server-Profile, Panorama-Screenshots. */
	public static final String COMFORT = "0.11.0";
	/** Menü-Leiste „Profile“ → Reiter „Server-Profile“. */
	public static final String MENU_SERVER_PROFILES = "menu:serverProfiles";
	/** Taste „Panorama aufnehmen“ (standardmäßig unbelegt). */
	public static final String KEY_PANORAMA = "key.trsclient.panorama";
	/** Schaltungs-Bibliothek (TRS Client 0.11.0): fertige Redstone-Schaltungen, als Vorlage in der Welt einblendbar. */
	public static final String CIRCUITS = "0.11.0";
	/** Suche in der Minecraft-Tastenbelegung (TRS Client 0.12.0). */
	public static final String KEY_SEARCH = "0.12.0";
	/** Fehlerbildschirme im TRS-Stil mit „Neu anmelden“, „Erneut verbinden“ … (TRS Client 0.12.0). */
	public static final String ERROR_SCREENS = "0.12.0";
	/**
	 * Minimap nach Art von Xaero (TRS Client 0.13.0): Wegpunkte am Rand, Köpfe/Symbole für Kreaturen, Farben aus
	 * den Block-Texturen, Auto-Zoom.
	 */
	public static final String MINIMAP_2 = "0.13.0";
	/** Weltkarte 2 (TRS Client 0.13.0): stufenlos zoomen, Schwung, Wegpunkt-Liste, andere Dimensionen, Export. */
	public static final String WORLD_MAP_2 = "0.13.0";

	/** „Bug melden“ im TRS-Menü (TRS Client 0.13.0). */
	public static final String BUG_REPORT = "0.13.0";
	/** Leisten-Eintrag „Bug melden“. */
	public static final String MENU_BUG_REPORT = "menu:bugReport";

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
		// TRS Client 0.9.0: Welt-Hosting.
		add(HOSTING, "social.hostingDirect");
		// TRS Client 0.8.1: Karten blenden Dächer aus (Innenansicht).
		add("0.8.1", "minimap.hideRoof");
		add(LATENCY, "netOptimize", "lowLatency", "ping.jitter", "ping.graph", "ping.details", "ping.interval",
				"ping.spikeWarning", "ping.spikeThreshold");
		// Komfort- und PvP-Paket.
		add(QOL, "chatMentions", "chatFilter", "autoReconnect", "queueAlerts", "scoreboard", "tabPing", "bossBar", "titles",
				"warnings", "itemCounter", "hitFeedback", "streamerMode");
		add(QOL, "chat.timestampTwelveHour", "chat.history", "chat.copyMode", "autoGg.presets");
		// Teilen-Paket.
		add(SHARE, SOCIAL_WAYPOINT, CLIPS_SHARE, "chat.coordLinks");
		// Komfort-Paket 2: Tooltips, Server-Profile, Panorama.
		add(COMFORT, "tooltips", "serverProfiles", "panorama", MENU_SERVER_PROFILES, KEY_PANORAMA);
		// Schaltungs-Bibliothek.
		add(CIRCUITS, "circuitLibrary");
		// Suche in der Tastenbelegung.
		add(KEY_SEARCH, "keySearch");
		add(ERROR_SCREENS, "menuStyle.errors");
		// Minimap 2: Wegpunkte am Rand, Kreatur-Köpfe/Symbole, Texturfarben, Auto-Zoom.
		add(MINIMAP_2, "minimap.edgeWaypoints", "minimap.mobIcons", "minimap.colors", "minimap.autoZoomSpeed",
				"minimap.autoZoomIndoor");
		// Weltkarte 2.
		add(WORLD_MAP_2, "worldMap.smoothZoom", "worldMap.inertia", "worldMap.waypointList", "worldMap.otherDimensions",
				"worldMap.netherCoords", "worldMap.exportSize");
		// Bug melden (Issue-Tracker der Website).
		add(BUG_REPORT, MENU_BUG_REPORT);
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
