package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.waypoint.WaypointStore;

/**
 * Was die Karte von der jeweiligen Minecraft-Version braucht – je Loader eine kleine Umsetzung
 * ({@code map/MapBridge}). Alles andere (Abtasten, Speichern, Zeichnen, Weltkarte) steckt in {@code core.map}.
 * Alle Aufrufe aus dem Spiel-Thread.
 */
public interface MapPlatform {
	/** {@link #movement()}: Spieler sprintet. */
	int SPRINTING = 1;
	/** {@link #movement()}: Spieler reitet/fährt (Pferd, Lore, Schwein …). */
	int RIDING = 2;
	/** {@link #movement()}: Spieler sitzt in einem Boot. */
	int BOAT = 4;
	/** {@link #movement()}: Spieler gleitet mit Elytren. */
	int GLIDING = 8;

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

	/**
	 * Weltkarte öffnen (ersetzt den offenen Bildschirm); false = geht in dieser Version nicht. Für „Anzeigen“ an geteilten
	 * Wegpunkten – die Stelle kommt vorher über {@link WorldMapUi#requestFocus}.
	 */
	default boolean openWorldMap() {
		return false;
	}

	/**
	 * Bewegungsart des Spielers als Merker ({@link #SPRINTING}, {@link #RIDING}, {@link #BOAT}, {@link #GLIDING}) –
	 * für den Auto-Zoom der Minimap. 0 = zu Fuß/unbekannt.
	 */
	default int movement() {
		return 0;
	}

	/**
	 * Liest eine Datei aus den gerade geladenen Ressourcen (Resource Packs vor Vanilla), z. B.
	 * {@code ("minecraft", "textures/block/stone.png")}. Wird aus dem Hintergrund-Thread der Texturfarben
	 * aufgerufen ({@link TexturePalette}). null = gibt es nicht (oder die Version kann es nicht).
	 */
	default byte[] readResource(String namespace, String path) throws java.io.IOException {
		return null;
	}

	/**
	 * Ein Objekt, das bei jedem Neuladen der Ressourcen (Resource Pack gewechselt, F3+T) neu entsteht – z. B. das
	 * Blockmodell von Stein. Ändert es sich, rechnet die Karte die Texturfarben neu. null = unbekannt.
	 */
	default Object resourceGeneration() {
		return null;
	}

	/** Kurze Meldung im Chat (nur für den Spieler, z. B. „Karte gespeichert als …“). Nur Spiel-Thread. */
	default void message(String text) {
	}
}
