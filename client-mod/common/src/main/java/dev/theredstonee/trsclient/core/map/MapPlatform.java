package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.waypoint.WaypointStore;

/**
 * Was die Karte von der jeweiligen Minecraft-Version braucht – je Loader eine kleine Umsetzung
 * ({@code map/MapBridge}). Alles andere (Abtasten, Speichern, Zeichnen, Weltkarte) steckt in {@code core.map}.
 * Alle Aufrufe aus dem Spiel-Thread.
 */
public interface MapPlatform {
	/** Nimmt Kartenobjekte entgegen (wiederverwendet; null = voll). */
	interface EntitySink {
		MapEntity add();
	}

	/** Welt geladen und Spieler vorhanden? */
	boolean inWorld();

	/** Welt-/Server-Schlüssel wie bei den Wegpunkten ({@code sp:<welt>}/{@code mp:<adresse>}), "" = unbekannt. */
	String worldKey();

	/** Dimension, z. B. {@code minecraft:overworld} (Legacy: {@code dim0}). */
	String dimension();

	double x();

	double y();

	double z();

	/** Blickrichtung (Minecraft-Yaw in Grad) – wird je Bild gelesen. */
	float yaw();

	/** Chunk-Zugriff der Version. */
	ChunkReader reader();

	/** Sichtweite in Chunks. */
	int renderDistance();

	/** Himmelslicht am Kopf des Spielers (0..15). */
	int skyLight();

	/**
	 * Spieler und Kreaturen im Umkreis. {@code lineOfSightOnly}: nur, was der Spieler direkt sehen kann (Fair Play).
	 * Der eigene Spieler gehört nicht dazu.
	 */
	void entities(EntitySink sink, double radius, boolean lineOfSightOnly);

	/** Biom am Spieler (übersetzt), "" = unbekannt. */
	String biome();

	/** Tageszeit in Ticks (0 = 6:00 Uhr) oder -1. */
	long dayTime();

	/** GUI-Skalierung (Bildschirmpixel je GUI-Pixel) – für gestochen scharfe Rahmen/Symbole. */
	double guiScale();

	/** Wegpunkte (gemeinsamer Speicher) und Schlüssel der aktuellen Welt. */
	WaypointStore waypoints();

	String waypointWorldKey();

	/** Wegpunkte wurden auf der Karte geändert → speichern. */
	void waypointsChanged();

	/** MOTD des Servers (für Fair-Play-Codes) oder null. */
	String serverMotd();
}
