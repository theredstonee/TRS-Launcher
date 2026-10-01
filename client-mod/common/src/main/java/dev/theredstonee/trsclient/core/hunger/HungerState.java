package dev.theredstonee.trsclient.core.hunger;

/**
 * Was die Hunger-Anzeige je Bild braucht – von den Bäumen befüllt ({@code comfort/HungerHooks}), hier nur Daten.
 * Ein Objekt je Baum, wird jedes Bild überschrieben.
 */
public final class HungerState {
	/** Zeichnet Vanilla gerade Herzen und Hungerleiste (Überleben/Abenteuer, HUD sichtbar)? */
	public boolean survival;
	/** Hungerleiste sichtbar (beim Reiten auf einem Lebewesen zeigt Vanilla dort dessen Herzen). */
	public boolean foodBar;
	/** GUI-skalierte Bildschirmgröße. */
	public int width;
	public int height;
	/** Tick-Zähler der Vanilla-HUD ({@code Gui.tickCount} bzw. {@code GuiIngame.updateCounter}) – für das Wackeln. */
	public int guiTicks;

	public int food;
	public float saturation;
	/** Erschöpfung 0–4; {@link Float#NaN}, wenn unbekannt (Mehrspieler: der Server schickt sie nicht). */
	public float exhaustion = Float.NaN;

	public float health;
	public float maxHealth;
	public float absorption;
	public boolean hardcore;
	/** Effekt „Hunger“ (grüne Keulen). */
	public boolean hungerEffect;
	/** Effekt „Regeneration“ (Herz-Welle). */
	public boolean regenEffect;

	/** Essen in der Hand: Hunger-Punkte (2 = eine Keule) und Sättigung in Punkten; 0 = nichts Essbares. */
	public int heldNutrition;
	public float heldSaturation;
	/** Kann das Essen jetzt gegessen werden (nicht satt bzw. „immer essbar“ wie Goldäpfel)? */
	public boolean heldCanEat;

	/** Ab 1.11: schnelle Heilung aus Sättigung bei voller Leiste, Heilung kostet 6 statt 3 Erschöpfung. */
	public boolean modernRegen = true;

	/** Alles auf „nichts anzeigen“ zurücksetzen (Anfang jedes Bildes). */
	public void reset() {
		survival = false;
		foodBar = false;
		exhaustion = Float.NaN;
		hardcore = false;
		hungerEffect = false;
		regenEffect = false;
		heldNutrition = 0;
		heldSaturation = 0;
		heldCanEat = false;
	}

	/** Hält der Spieler Essbares, das er jetzt essen kann? */
	public boolean holdsFood() {
		return heldCanEat && (heldNutrition > 0 || heldSaturation > 0);
	}
}
