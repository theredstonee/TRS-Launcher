package dev.theredstonee.trsclient.core.waypoint;

import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MapPlatform;
import dev.theredstonee.trsclient.core.map.WorldMapUi;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.social.Chat;
import dev.theredstonee.trsclient.core.social.SocialOverlay;
import dev.theredstonee.trsclient.core.social.SocialPlatform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wegpunkte teilen (API.md §18.10): eigene Wegpunkte/Positionen zu Karten machen, empfangene Karten mit der aktuellen
 * Welt vergleichen, übernehmen oder auf der Weltkarte zeigen. Die Welt reist nie als Name: Server als Adresse,
 * Einzelspielerwelten nur als Kennung (erste 16 Hex von SHA-256 des Weltschlüssels {@code sp:<welt>}).
 *
 * <p>Spielzugriffe laufen über die {@link MapPlatform} der Karte (je Loader vorhanden, auch ohne sichtbare Karte).
 * Alles aus dem Spiel-Thread.
 */
public final class WaypointShare {
	private WaypointShare() {
	}

	/** Ergebnis einer Aktion; {@link #key()} = i18n-Schlüssel des Hinweises. */
	public enum Result {
		OK("waypoint.share.ok"),
		ADDED("waypoint.share.added"),
		ALREADY("waypoint.share.already"),
		NO_WORLD("waypoint.share.noWorld"),
		OTHER_SERVER("waypoint.share.otherServer"),
		OTHER_WORLD("waypoint.share.otherWorld"),
		OTHER_DIMENSION("waypoint.share.otherDimension"),
		MAP_OFF("waypoint.share.mapOff"),
		OFFLINE("waypoint.share.offline"),
		UNSUPPORTED("waypoint.share.unsupported"),
		INVALID("waypoint.share.invalid");

		private final String key;

		Result(String key) {
			this.key = key;
		}

		public String key() {
			return key;
		}
	}

	private static final Pattern LEGACY_DIM = Pattern.compile("dim(-?\\d{1,9})");
	private static final Pattern NAMESPACED = Pattern.compile("[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}");
	private static final Pattern EMBEDDED = Pattern.compile("([a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64})");
	private static final int DEFAULT_COLOR = 0x3D7BFF;

	/** Karte, die als Nächstes an eine Unterhaltung gehen soll (Sozial-Bildschirm holt sie ab). */
	private static Chat.Waypoint pending;

	// --- Reine Logik (getestet) ---

	/** Kennung einer Einzelspielerwelt: erste 16 Hex von SHA-256(UTF-8(weltschlüssel)). */
	public static String worldId(String worldKey) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(worldKey.getBytes(StandardCharsets.UTF_8));
			StringBuilder b = new StringBuilder(16);
			for (int i = 0; i < 8; i++) b.append(String.format(Locale.ROOT, "%02x", d[i] & 0xFF));
			return b.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Serveradresse zum Vergleichen: klein, ohne Standardport 25565, ohne Punkt am Ende. */
	public static String normalizeServer(String address) {
		if (address == null) return "";
		String a = address.trim().toLowerCase(Locale.ROOT);
		if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
		while (a.endsWith(".")) a = a.substring(0, a.length() - 1);
		return a;
	}

	/**
	 * Dimension als Namensraum-Kennung: Legacy {@code dim0/dim-1/dim1} → {@code minecraft:overworld/the_nether/the_end},
	 * andere Legacy-Nummern → {@code legacy:dim<n>}, eingebettete Kennungen („DimensionType{minecraft:overworld}“)
	 * werden herausgelöst. Unbrauchbar → null.
	 */
	public static String canonicalDimension(String dim) {
		if (dim == null) return null;
		String d = dim.trim().toLowerCase(Locale.ROOT);
		Matcher legacy = LEGACY_DIM.matcher(d);
		if (legacy.matches()) {
			String n = legacy.group(1);
			if (n.equals("0")) return "minecraft:overworld";
			if (n.equals("-1")) return "minecraft:the_nether";
			if (n.equals("1")) return "minecraft:the_end";
			return "legacy:dim" + n;
		}
		if (NAMESPACED.matcher(d).matches()) return d;
		Matcher m = EMBEDDED.matcher(d);
		return m.find() ? m.group(1) : null;
	}

	/** Schreibt diese Version Dimensionen als Zahl ({@code dim0}, {@code dim-1} – Forge bis 1.12.2)? */
	public static boolean legacyDimensions(String current) {
		return current != null && LEGACY_DIM.matcher(current.trim()).matches();
	}

	/** Kennung zurück in die Schreibweise dieser Version ({@code current} = Dimension des Spiels). */
	public static String localDimension(String canonical, String current) {
		if (canonical == null) return "";
		boolean legacy = current != null && LEGACY_DIM.matcher(current.trim()).matches();
		if (!legacy) return canonical;
		if (canonical.equals("minecraft:overworld")) return "dim0";
		if (canonical.equals("minecraft:the_nether")) return "dim-1";
		if (canonical.equals("minecraft:the_end")) return "dim1";
		if (canonical.startsWith("legacy:dim")) return canonical.substring("legacy:".length());
		return canonical;
	}

	/**
	 * Karte aus lokalen Angaben ({@code worldKey} wie im {@link WaypointStore}); null, wenn etwas nicht passt (z. B.
	 * Weltschlüssel unbekannt, Dimension leer, Koordinaten außerhalb).
	 */
	public static Chat.Waypoint card(String name, int x, int y, int z, String localDimension, String worldKey, int color) {
		String dim = canonicalDimension(localDimension);
		if (dim == null || worldKey == null) return null;
		Long c = color < 0 ? null : (long) (color & 0xFFFFFF);
		if (worldKey.startsWith("mp:")) {
			return Chat.Waypoint.of(name, x, y, z, dim, "server", normalizeServer(worldKey.substring(3)), null, c);
		}
		if (worldKey.startsWith("sp:") && worldKey.length() > 3) {
			return Chat.Waypoint.of(name, x, y, z, dim, "world", null, worldId(worldKey), c);
		}
		return null;
	}

	/** Gehört die Karte zu dieser Welt? */
	public static Result matchWorld(Chat.Waypoint w, String worldKey) {
		if (w == null) return Result.INVALID;
		if (worldKey == null || worldKey.isEmpty()) return Result.NO_WORLD;
		if (w.server()) {
			if (!worldKey.startsWith("mp:")) return Result.OTHER_SERVER;
			return normalizeServer(worldKey.substring(3)).equals(normalizeServer(w.address)) ? Result.OK : Result.OTHER_SERVER;
		}
		if (!worldKey.startsWith("sp:")) return Result.OTHER_WORLD;
		return worldId(worldKey).equals(w.worldId) ? Result.OK : Result.OTHER_WORLD;
	}

	/** Welt und Dimension passen? */
	public static Result match(Chat.Waypoint w, String worldKey, String localDimension) {
		Result r = matchWorld(w, worldKey);
		if (r != Result.OK) return r;
		String dim = canonicalDimension(localDimension);
		return w.dimension.equals(dim) ? Result.OK : Result.OTHER_DIMENSION;
	}

	/** Liegt schon ein gleicher Wegpunkt (Name + Position) in der Liste? */
	static boolean contains(Iterable<Waypoint> list, Chat.Waypoint w, String localDim) {
		for (Waypoint p : list) {
			if (p.x == w.x && p.y == w.y && p.z == w.z && p.name.equals(w.name)
					&& (p.dimension == null || p.dimension.isEmpty() || p.dimension.equals(localDim))) {
				return true;
			}
		}
		return false;
	}

	/** Übernehmen in einen Speicher (ohne Spiel): Rückgabe ADDED, ALREADY oder ein Grund. */
	public static Result adopt(WaypointStore store, String worldKey, String localDimension, Chat.Waypoint w) {
		Result r = matchWorld(w, worldKey);
		if (r != Result.OK) return r;
		String dim = localDimension(w.dimension, localDimension);
		if (contains(store.all(worldKey), w, dim)) return Result.ALREADY;
		store.add(worldKey, new Waypoint(w.name, w.x, w.y, w.z, dim, w.color >= 0 ? w.color : DEFAULT_COLOR));
		return Result.ADDED;
	}

	/**
	 * Geteilten Wegpunkt eines Freundes übernehmen (Live-Teilen, ohne Spiel): die Liste gehört per Definition zur
	 * aktuellen Welt, deshalb kein Weltvergleich. Die Kopie ist ein normaler eigener Wegpunkt (nicht geteilt, kein Tod).
	 */
	public static Result adopt(WaypointStore store, String worldKey, String localDimension,
			dev.theredstonee.trsclient.core.waypoint.live.FriendWaypoint w) {
		if (w == null) return Result.INVALID;
		if (store == null || worldKey == null || worldKey.isEmpty()) return Result.NO_WORLD;
		String dim = localDimension(w.dimension, localDimension);
		for (Waypoint p : store.all(worldKey)) {
			if (p.x == w.x && p.y == w.y && p.z == w.z && p.name.equals(w.name)
					&& (p.dimension == null || p.dimension.isEmpty() || p.dimension.equals(dim))) {
				return Result.ALREADY;
			}
		}
		store.add(worldKey, new Waypoint(w.name, w.x, w.y, w.z, dim, w.color));
		return Result.ADDED;
	}

	/**
	 * Wegpunkt aus einer Chat-Nachricht von Xaero's Minimap bzw. JourneyMap speichern (ohne Spiel): in der Dimension der
	 * Nachricht ({@code canonicalDim}, null = {@code localDimension}), mit ihrer Farbe (-1 = Standard) und ihrem Kürzel.
	 * Rückgabe ADDED, ALREADY oder NO_WORLD.
	 */
	public static Result saveShared(WaypointStore store, String worldKey, String localDimension, String name, int x, int y,
			int z, String canonicalDim, int color, String mark) {
		if (store == null || worldKey == null || worldKey.isEmpty()) return Result.NO_WORLD;
		String current = localDimension == null ? "" : localDimension;
		String dim = canonicalDim == null || canonicalDim.isEmpty() ? current : localDimension(canonicalDim, current);
		Waypoint w = new Waypoint(name, x, y, z, dim, color < 0 ? DEFAULT_COLOR : color);
		for (Waypoint p : store.all(worldKey)) {
			if (p.x == x && p.y == y && p.z == z && p.name.equals(w.name)
					&& (p.dimension == null || p.dimension.isEmpty() || p.dimension.equals(dim))) {
				return Result.ALREADY;
			}
		}
		// Kürzel nur merken, wenn es vom Anfangsbuchstaben abweicht (sonst folgt es dem Namen).
		String m = Waypoint.cleanMark(mark);
		if (!m.isEmpty() && !m.equals(Waypoint.initial(w.name))) w.mark = m;
		store.add(worldKey, w);
		return Result.ADDED;
	}

	// --- Im Spiel ---

	private static MapPlatform platform() {
		MapEngine e = MapEngine.get();
		return e == null ? null : e.platform();
	}

	/** Aktueller Weltschlüssel ("" = keine Welt). */
	public static String currentWorldKey() {
		MapPlatform p = platform();
		if (p == null || !p.inWorld()) return "";
		String k = p.waypointWorldKey();
		return k == null ? "" : k;
	}

	/** Aktuelle Dimension in der Schreibweise dieser Version ("" = keine Welt). */
	public static String currentDimension() {
		MapPlatform p = platform();
		if (p == null || !p.inWorld()) return "";
		String d = p.dimension();
		return d == null ? "" : d;
	}

	/** Karte für die aktuelle Position (null = keine Welt). */
	public static Chat.Waypoint here(String name) {
		MapPlatform p = platform();
		if (p == null || !p.inWorld()) return null;
		return card(name, floor(p.x()), floor(p.y()), floor(p.z()), p.dimension(), p.waypointWorldKey(), DEFAULT_COLOR);
	}

	/** Karte aus einem eigenen Wegpunkt der aktuellen Welt (null = keine Welt). */
	public static Chat.Waypoint of(Waypoint w) {
		MapPlatform p = platform();
		if (p == null || !p.inWorld() || w == null) return null;
		String dim = w.dimension == null || w.dimension.isEmpty() ? p.dimension() : w.dimension;
		return card(w.name, w.x, w.y, w.z, dim, p.waypointWorldKey(), w.color);
	}

	/** Welt passt (für die Knöpfe der Karte)? */
	public static Result check(Chat.Waypoint w) {
		MapPlatform p = platform();
		if (p == null || !p.inWorld()) return Result.NO_WORLD;
		return match(w, p.waypointWorldKey(), p.dimension());
	}

	/** „Übernehmen“: Wegpunkt anlegen, wenn Welt passt. */
	public static Result adopt(Chat.Waypoint w) {
		MapPlatform p = platform();
		if (p == null || !p.inWorld()) return Result.NO_WORLD;
		WaypointStore store = p.waypoints();
		String key = p.waypointWorldKey();
		if (store == null || key == null || key.isEmpty()) return Result.NO_WORLD;
		Result r = adopt(store, key, p.dimension(), w);
		if (r == Result.ADDED) p.waypointsChanged();
		return r;
	}

	/** „Übernehmen“ (Live-Teilen): Wegpunkt eines Freundes als eigenen anlegen. */
	public static Result adopt(dev.theredstonee.trsclient.core.waypoint.live.FriendWaypoint w) {
		MapPlatform p = platform();
		if (p == null || !p.inWorld()) return Result.NO_WORLD;
		Result r = adopt(p.waypoints(), p.waypointWorldKey(), p.dimension(), w);
		if (r == Result.ADDED) p.waypointsChanged();
		return r;
	}

	/** Koordinaten aus dem Chat als Wegpunkt der aktuellen Welt und Dimension speichern (nur lokal). */
	public static Result saveHere(String name, int x, int y, int z, int color) {
		MapPlatform p = platform();
		if (p == null || !p.inWorld()) return Result.NO_WORLD;
		WaypointStore store = p.waypoints();
		String key = p.waypointWorldKey();
		if (store == null || key == null || key.isEmpty()) return Result.NO_WORLD;
		Waypoint w = new Waypoint(name, x, y, z, p.dimension(), color);
		for (Waypoint e : store.all(key)) {
			if (e.x == x && e.y == y && e.z == z && e.name.equals(w.name)) return Result.ALREADY;
		}
		store.add(key, w);
		p.waypointsChanged();
		return Result.ADDED;
	}

	/**
	 * Wegpunkt von Xaero's Minimap bzw. JourneyMap aus dem Chat in der aktuellen Welt speichern (nur lokal) – in der
	 * Dimension der Nachricht ({@code canonicalDim}, null = aktuelle).
	 */
	public static Result saveShared(String name, int x, int y, int z, String canonicalDim, int color, String mark) {
		MapPlatform p = platform();
		if (p == null || !p.inWorld()) return Result.NO_WORLD;
		Result r = saveShared(p.waypoints(), p.waypointWorldKey(), p.dimension(), name, x, y, z, canonicalDim, color, mark);
		if (r == Result.ADDED) p.waypointsChanged();
		return r;
	}

	/**
	 * „[Add]“ an einem geteilten Wegpunkt von Xaero's Minimap/JourneyMap im Chat: sofort speichern, ohne Fenster – mit
	 * Name, Farbe, Kürzel und Dimension der Nachricht, ohne Höhe die des Spielers. Nur lokal. Rückgabe: Hinweis für die
	 * Aktionsleiste („Wegpunkt „Dorf“ gespeichert“, „… hast du schon“, „keine Welt“ …).
	 */
	public static String addShared(dev.theredstonee.trsclient.core.chat.ChatWaypoints.Shared s) {
		String name = dev.theredstonee.trsclient.core.social.SafeText.line(
				dev.theredstonee.trsclient.core.chat.ChatWaypoints.displayName(s), dev.theredstonee.trsclient.core.social.SafeText.MAX_NAME);
		if (name == null || name.isEmpty()) name = dev.theredstonee.trsclient.core.i18n.I18n.tr("waypoint.save.defaultName");
		Result r = saveShared(name, s.x, s.hasY ? s.y : playerBlockY(), s.z, s.dimension, s.color, s.mark);
		if (r != Result.ADDED && r != Result.ALREADY) return dev.theredstonee.trsclient.core.i18n.I18n.tr(r.key());
		String text = dev.theredstonee.trsclient.core.i18n.I18n.tr(r.key(), name) + " · "
				+ dev.theredstonee.trsclient.core.chat.ChatWaypoints.details(s);
		// Andere Dimension als die aktuelle: dort gespeichert, auf Minimap/Weltkarte erst dort zu sehen.
		String here = canonicalDimension(currentDimension());
		if (s.dimension != null && here != null && !s.dimension.equals(here)) {
			text += " – " + dev.theredstonee.trsclient.core.i18n.I18n.tr("chat.waypoint.otherDimension", WorldMapUi.dimensionName(s.dimension));
		}
		return text;
	}

	/** Blockhöhe der Füße des Spielers (für geteilte Wegpunkte ohne Höhe), 64 ohne Welt. */
	public static int playerBlockY() {
		MapPlatform p = platform();
		return p == null || !p.inWorld() ? 64 : floor(p.y());
	}

	/** „Anzeigen“: Weltkarte auf den Punkt zentrieren (Welt und Dimension müssen passen). */
	public static Result show(Chat.Waypoint w) {
		Result r = check(w);
		if (r != Result.OK) return r;
		MapEngine e = MapEngine.get();
		if (e == null || !e.modules().worldMap.isEnabled()) return Result.MAP_OFF;
		WorldMapUi.requestFocus(w.name, w.x, w.z, w.color >= 0 ? w.color : DEFAULT_COLOR);
		MapPlatform p = platform();
		return p != null && p.openWorldMap() ? Result.OK : Result.UNSUPPORTED;
	}

	// --- Teilen (Sozial-Bildschirm mit Zielauswahl) ---

	/** Karte vormerken und den Sozial-Bildschirm mit Zielauswahl öffnen. */
	public static Result request(Chat.Waypoint w) {
		if (w == null) return Result.NO_WORLD;
		TrsOnline online = TrsOnline.current();
		if (online == null || online.social() == null || !online.social().signedIn()) return Result.OFFLINE;
		SocialPlatform sp = SocialOverlay.platform();
		pending = w;
		if (sp == null || !sp.openSocial()) {
			pending = null;
			return Result.UNSUPPORTED;
		}
		return Result.OK;
	}

	/** Eigenen Wegpunkt teilen. */
	public static Result shareLocal(Waypoint w) {
		return request(of(w));
	}

	/** Eine Stelle teilen (z. B. von der Weltkarte). */
	public static Result sharePosition(String name, int x, int y, int z) {
		MapPlatform p = platform();
		if (p == null || !p.inWorld()) return Result.NO_WORLD;
		return request(card(name, x, y, z, p.dimension(), p.waypointWorldKey(), DEFAULT_COLOR));
	}

	/** Vorgemerkte Karte abholen (Sozial-Bildschirm) – danach leer. */
	public static Chat.Waypoint takePending() {
		Chat.Waypoint w = pending;
		pending = null;
		return w;
	}

	/** Für Selbsttests: Karte vormerken, ohne einen Bildschirm zu öffnen. */
	public static void setPendingForTest(Chat.Waypoint w) {
		pending = w;
	}

	private static int floor(double v) {
		return (int) Math.floor(v);
	}
}
