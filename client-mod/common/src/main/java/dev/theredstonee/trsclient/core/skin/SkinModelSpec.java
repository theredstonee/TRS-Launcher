package dev.theredstonee.trsclient.core.skin;

import dev.theredstonee.trsclient.core.ui.TextureRef;

/**
 * Was {@link SkinModel} zeichnen soll: Skin (64×64, Classic/Slim), optional Umhang (aktuelles
 * Animationsbild, Vanilla-Aufteilung in beliebiger Auflösung 64×32 … 512×256), Blickwinkel und Pose.
 * Veränderlich und zum Wiederverwenden gedacht (ein Objekt je Vorschau, keine Allokation je Bild).
 */
public final class SkinModelSpec {
	/** Skin-Textur im 64×64-Format (64×32-Skins vorher mit {@link SkinImage#normalize} umwandeln). */
	public TextureRef skin;
	/** Schmale Arme (3 statt 4 Pixel, "Alex"). */
	public boolean slim;
	/** Umhang (aktuelles Bild) oder null. */
	public TextureRef cape;
	/** Drehung um die senkrechte Achse in Grad: 0 = Blick zum Betrachter, 180 = Rücken. */
	public float yaw;
	/** Kamera-Neigung in Grad: positiv = etwas von oben (Oberseiten sichtbar). */
	public float pitch = 8f;
	/** Kopf relativ zum Körper in Grad (links/rechts, hoch/runter; positiv = nach unten). */
	public float headYaw;
	public float headPitch;
	/** Laufen: Phase (wie Vanillas limbSwing) und Stärke 0..1. */
	public float walkPhase;
	public float walkAmount;
	/** Zeit in Sekunden für das leichte Armwippen und Umhangschwingen im Stand (0 = ruhig). */
	public float idleTime;
	/** Zweite Skin-Ebene (Hut, Jacke, Ärmel, Hosenbeine) zeichnen. */
	public boolean layers = true;
	/** Zusätzliche Umhang-Neigung in Grad (Wind/Laufen). */
	public float capeLift;
	/** Einfärbung/Deckkraft der ganzen Figur (0xFFFFFFFF = normal). */
	public int tint = 0xFFFFFFFF;
	/** Flächen je nach Lichteinfall abschatten (wie im Spiel). */
	public boolean shade = true;
	/**
	 * Freie Haltung (z. B. Emote-Vorschau) oder null: je Teil (Kopf, Körper, rechter/linker Arm, rechtes/linkes Bein)
	 * sechs Werte {@code x, y, z, xRot, yRot, zRot} im Modellraum von Minecraft (y nach unten, Vorderseite −z, Winkel
	 * in Bogenmaß, Reihenfolge Z·Y·X) – genau das Format von {@code EmoteRig}. Ersetzt Lauf-/Kopfwinkel.
	 */
	public float[] pose;
	/** Grundebene zeichnen (der Editor blendet sie aus, um nur die zweite Ebene zu zeigen). */
	public boolean base = true;

	/** Setzt alles auf die Grundwerte zurück (Skin/Umhang bleiben). */
	public SkinModelSpec resetPose() {
		yaw = 0f;
		pitch = 8f;
		headYaw = 0f;
		headPitch = 0f;
		walkPhase = 0f;
		walkAmount = 0f;
		idleTime = 0f;
		capeLift = 0f;
		tint = 0xFFFFFFFF;
		pose = null;
		return this;
	}
}
