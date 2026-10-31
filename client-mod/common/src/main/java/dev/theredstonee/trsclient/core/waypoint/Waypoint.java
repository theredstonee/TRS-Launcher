package dev.theredstonee.trsclient.core.waypoint;

/** Ein gespeicherter Wegpunkt (Gson-DTO – alle Felder öffentlich und einfach). */
public final class Waypoint {
	/** Erlaubte Kennung (wie die TRS API: 1–40 Zeichen {@code [A-Za-z0-9_-]}). */
	private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,40}");
	private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
	private static final char[] ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-".toCharArray();

	/**
	 * Feste, zufällige Kennung (fürs Live-Teilen mit Freunden). Alte Dateien haben keine – {@link #normalized()} vergibt
	 * dann eine neue (der Speicher schreibt sie beim nächsten Speichern mit).
	 */
	public String id;
	public String name = defaultName();
	public int x;
	public int y;
	public int z;
	/** Dimension, z. B. "minecraft:overworld" (leer = alle). */
	public String dimension = "";
	/** Farbe als 0xRRGGBB. */
	public int color = 0xE0281E;
	/** Im Spiel anzeigen? */
	public boolean visible = true;
	/** Automatisch angelegter Todespunkt. */
	public boolean death;
	/** Mit Freunden live teilen (Freigabe je Freund; Standard aus). */
	public boolean shared;
	/** Zeitpunkt der Erstellung (ms). */
	public long created;
	/**
	 * Vorübergehend (z. B. Koordinate aus einer Notiz): wird nie gespeichert und verschwindet beim Erreichen bzw.
	 * spätestens beim Neustart. {@code transient} = Gson schreibt/liest es nicht.
	 */
	public transient boolean temporary;

	/** Für Gson: die Kennung vergibt {@link #normalized()} (so erkennt der Speicher alte Einträge ohne Kennung). */
	public Waypoint() {
	}

	public Waypoint(String name, int x, int y, int z, String dimension, int color) {
		this.id = newId();
		this.name = name == null || name.isEmpty() ? defaultName() : name;
		this.x = x;
		this.y = y;
		this.z = z;
		this.dimension = dimension == null ? "" : dimension;
		this.color = color & 0xFFFFFF;
	}

	/** Name für Wegpunkte ohne Namen in der aktiven Sprache ("Waypoint", "Wegpunkt" …). */
	public static String defaultName() {
		return dev.theredstonee.trsclient.core.i18n.I18n.tr("waypoint.defaultName");
	}

	/** Gilt der Wegpunkt in dieser Dimension? */
	public boolean inDimension(String dim) {
		return dimension == null || dimension.isEmpty() || dim == null || dim.isEmpty() || dimension.equals(dim);
	}

	/** Waagerechte Entfernung zu einem Punkt. */
	public double distanceTo(double px, double py, double pz) {
		double dx = x + 0.5 - px;
		double dy = y + 0.5 - py;
		double dz = z + 0.5 - pz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Neue zufällige Kennung (12 Zeichen, 72 Bit). */
	public static String newId() {
		char[] out = new char[12];
		for (int i = 0; i < out.length; i++) out[i] = ID_CHARS[RANDOM.nextInt(ID_CHARS.length)];
		return new String(out);
	}

	/** Gültige Kennung? */
	public static boolean validId(String id) {
		return id != null && ID.matcher(id).matches();
	}

	/** Fehlende/kaputte Werte einer geladenen Datei geradeziehen. */
	public Waypoint normalized() {
		if (!validId(id)) id = newId();
		if (name == null || name.trim().isEmpty()) name = defaultName();
		if (name.length() > 32) name = name.substring(0, 32);
		if (dimension == null) dimension = "";
		color &= 0xFFFFFF;
		return this;
	}
}
