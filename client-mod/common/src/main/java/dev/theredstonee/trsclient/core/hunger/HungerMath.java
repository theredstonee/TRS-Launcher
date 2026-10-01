package dev.theredstonee.trsclient.core.hunger;

import java.util.Random;

/**
 * Rechnungen der Hunger-Anzeige: Werte nach dem Essen, geschätzte Heilung (Vanilla-{@code FoodData.tick} nachgebaut),
 * Blinken und das Wackeln der Vanilla-Symbole (damit die Überlagerung genau auf den Symbolen liegt).
 */
public final class HungerMath {
	/** Volle Hungerleiste. */
	public static final int MAX_FOOD = 20;
	/** Ab so viel Hunger heilt Vanilla langsam. */
	public static final int REGEN_FOOD = 18;
	/** Erschöpfung, die einen Sättigungs- bzw. Hungerpunkt kostet. */
	public static final float EXHAUSTION_STEP = 4f;
	/** Deckkraft der blinkenden Vorschau auf dem Höhepunkt. */
	public static final float FLASH_MAX = 0.65f;
	/** Dauer eines Blink-Zyklus (wie AppleSkin: 32 Ticks). */
	public static final long FLASH_PERIOD_MS = 1600;

	private HungerMath() {
	}

	/** Hunger nach dem Essen. */
	public static int foodAfter(int food, int nutrition) {
		return Math.max(0, Math.min(MAX_FOOD, food + nutrition));
	}

	/** Sättigung nach dem Essen: Vanilla deckelt sie beim neuen Hungerwert. */
	public static float saturationAfter(int food, float saturation, int nutrition, float saturationGain) {
		return Math.max(0f, Math.min(foodAfter(food, nutrition), saturation + saturationGain));
	}

	/**
	 * Wie viel Leben (halbe Herzen = 1) die natürliche Heilung aus diesem Hunger-/Sättigungsstand noch bringt, bis der
	 * Hunger unter {@link #REGEN_FOOD} fällt oder das Leben voll ist. Nachbau von {@code FoodData.tick}: schnelle Heilung
	 * aus Sättigung bei voller Leiste (ab 1.11), sonst 1 Leben je Schritt; jede Heilung kostet Erschöpfung.
	 */
	public static float healthGain(int food, float saturation, float exhaustion, float health, float maxHealth,
			boolean modernRegen) {
		if (Float.isNaN(exhaustion) || exhaustion < 0) exhaustion = 0;
		float missing = maxHealth - health;
		if (missing <= 0 || food < REGEN_FOOD) return 0;
		float slowCost = modernRegen ? 6f : 3f;
		float gain = 0;
		// Sicherheitsgrenze: höchstens 20 Hunger + 20 Sättigung → wenige hundert Schritte.
		for (int guard = 0; guard < 1000 && food >= REGEN_FOOD && gain < missing; guard++) {
			while (exhaustion > EXHAUSTION_STEP) {
				exhaustion -= EXHAUSTION_STEP;
				if (saturation > 0) saturation = Math.max(saturation - 1f, 0f);
				else food = Math.max(food - 1, 0);
			}
			if (food < REGEN_FOOD) break;
			if (modernRegen && food >= MAX_FOOD && saturation > Float.MIN_NORMAL) {
				float used = Math.min(saturation, 6f);
				gain += used / 6f;
				exhaustion += used;
			} else {
				gain += 1f;
				exhaustion += slowCost;
			}
		}
		return Math.min(gain, missing);
	}

	/** Deckkraft (0 … {@link #FLASH_MAX}) der blinkenden Vorschau zur Wanduhr-Zeit {@code nowMs}. */
	public static float flashAlpha(long nowMs) {
		float phase = (float) Math.floorMod(nowMs, FLASH_PERIOD_MS) / FLASH_PERIOD_MS;
		float tri = phase < 0.5f ? phase * 2f : 2f - phase * 2f;
		// Wie AppleSkin: Wert läuft von −0,5 bis 1,5 und wird auf 0…1 begrenzt → kurz ganz aus, kurz ganz an.
		float v = -0.5f + 2f * tri;
		return Math.max(0f, Math.min(1f, v)) * FLASH_MAX;
	}

	/** Anzahl der Herz-Symbole, die Vanilla zeichnet (Leben + Absorption); 0 bei unsinnig großen Werten. */
	public static int heartSlots(HungerState s) {
		float total = Math.max(s.maxHealth, s.health) + ceil(s.absorption);
		if (!(total >= 0) || total > 2000) return 0;
		return ceil(total / 2f);
	}

	/** Zeilenabstand der Herzen (Vanilla rückt bei vielen Reihen zusammen). */
	public static int heartRowHeight(HungerState s) {
		int rows = ceil(heartSlots(s) / 10f);
		return Math.max(10 - (rows - 2), 3);
	}

	/** Index des Herzens, das durch die Regenerations-Welle 2 px angehoben ist, sonst −1. */
	public static int regenWaveIndex(HungerState s) {
		if (!s.regenEffect) return -1;
		return Math.floorMod(s.guiTicks, ceil(Math.max(s.maxHealth, s.health) + 5f));
	}

	/**
	 * Vanilla-Wackeln nachgerechnet: derselbe Zufallsgenerator wie die HUD ({@code setSeed(tickCount * 312871)}),
	 * erst die Herzen (bei wenig Leben, vom letzten Herz an), dann die Keulen (ohne Sättigung, in Abständen).
	 *
	 * @param hearts Ausgabe: y-Versatz je Herz (Länge ≥ {@link #heartSlots})
	 * @param food   Ausgabe: y-Versatz je Keule (Länge 10, Index 0 = rechts)
	 */
	public static void jitter(HungerState s, int[] hearts, int[] food) {
		java.util.Arrays.fill(hearts, 0);
		java.util.Arrays.fill(food, 0);
		// Vanilla: (long)(tickCount * 312871) – die Multiplikation läuft als int über.
		Random r = new Random((long) (s.guiTicks * 312871));
		int slots = Math.min(heartSlots(s), hearts.length);
		if (ceil(s.health) <= 4) {
			for (int i = heartSlots(s) - 1; i >= 0; i--) {
				int off = r.nextInt(2);
				if (i < slots) hearts[i] = off;
			}
		}
		if (s.saturation <= 0f && s.guiTicks % (s.food * 3 + 1) == 0) {
			for (int i = 0; i < 10 && i < food.length; i++) food[i] = r.nextInt(3) - 1;
		}
	}

	static int ceil(float v) {
		int i = (int) v;
		return v > i ? i + 1 : i;
	}
}
