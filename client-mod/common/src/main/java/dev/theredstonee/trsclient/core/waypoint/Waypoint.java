package dev.theredstonee.trsclient.core.waypoint;

/** Ein gespeicherter Wegpunkt (Gson-DTO – alle Felder öffentlich und einfach). */
public final class Waypoint {
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
	/** Zeitpunkt der Erstellung (ms). */
	public long created;

	public Waypoint() {
	}

	public Waypoint(String name, int x, int y, int z, String dimension, int color) {
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

	/** Fehlende/kaputte Werte einer geladenen Datei geradeziehen. */
	public Waypoint normalized() {
		if (name == null || name.trim().isEmpty()) name = defaultName();
		if (name.length() > 32) name = name.substring(0, 32);
		if (dimension == null) dimension = "";
		color &= 0xFFFFFF;
		return this;
	}
}
