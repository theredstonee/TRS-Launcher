package dev.theredstonee.trsclient.core.hunger;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

/**
 * Hunger-Anzeige über der Vanilla-Hungerleiste (Idee von AppleSkin, eigene Umsetzung): Sättigung als goldener Rand,
 * blinkende Vorschau, was das Essen in der Hand bringt (Hunger, Sättigung, Herzen), und – wenn bekannt – die
 * Erschöpfung als heller Balken. Zeichnet nur über den Canvas; die Lage folgt den Vanilla-Formeln
 * (Hunger rechts, Herzen links, je 39 px über dem unteren Rand, 8 px Abstand).
 */
public final class HungerOverlay {
	/** Umriss der Keule (aus {@code food_empty}): Pixel am Rand der Form. */
	private static final String[] SHAPE = {
			"..##.....",
			".####....",
			"######...",
			"#######..",
			".######..",
			"..#####..",
			"...######",
			"......###",
			"......##."};
	/** Rand-Pixel je Zeile als Läufe {start, ende(exkl.)} – einmal berechnet. */
	private static final int[][][] OUTLINE = outline();
	private static final int SATURATION = 0xFFFFC21A;
	private static final int SATURATION_SHADE = 0xFFB07A00;
	private static final int EXHAUSTION = 0x50FFFFFF;

	private final int[] heartJitter = new int[1000];
	private final int[] foodJitter = new int[10];
	/** Gecachter Blick (AppleSkin-Textur oder Pack-Umriss). {@link Integer#MIN_VALUE} = noch nie gebaut. */
	private int seenGen = Integer.MIN_VALUE;
	private HungerLook.Source source = HungerLook.Source.BUILTIN;
	private int[][][] packOutline = OUTLINE;
	private TextureRef appleskin;

	/** Was gezeichnet wird. */
	public boolean showSaturation = true;
	public boolean showHeldFood = true;
	public boolean showHealth = true;
	public boolean showExhaustion = true;

	/** Zeichnet alles für Zustand {@code s} (Wanduhr {@code nowMs} für das Blinken). */
	public void draw(Canvas c, HungerState s, HudIcons icons, long nowMs) {
		if (!s.survival) return;
		refresh();
		HungerMath.jitter(s, heartJitter, foodJitter);
		int right = s.width / 2 + 91;
		int left = s.width / 2 - 91;
		int top = s.height - 39;
		float flash = HungerMath.flashAlpha(nowMs);
		boolean held = showHeldFood && s.holdsFood();

		if (s.foodBar) {
			if (showExhaustion && !Float.isNaN(s.exhaustion)) drawExhaustion(c, s.exhaustion, right, top);
			if (showSaturation) drawSaturation(c, 0, s.saturation, right, top, 1f);
			if (held && icons != null) {
				drawFoodPreview(c, icons, s, right, top, flash);
				if (showSaturation) {
					float after = HungerMath.saturationAfter(s.food, s.saturation, s.heldNutrition, s.heldSaturation);
					if (after > s.saturation) drawSaturation(c, s.saturation, after, right, top, flash / HungerMath.FLASH_MAX);
				}
			}
		}
		if (held && showHealth && icons != null) drawHealthPreview(c, icons, s, left, top, flash);
	}

	/** Erschöpfung 0–4 als heller Balken über den Keulen, von rechts nach links. Mit AppleSkin aus deren Textur. */
	private void drawExhaustion(Canvas c, float exhaustion, int right, int top) {
		float ratio = Math.max(0f, Math.min(1f, exhaustion / HungerMath.EXHAUSTION_STEP));
		int w = HungerLook.exhaustionWidth(ratio);
		if (w <= 0) return;
		if (source == HungerLook.Source.APPLESKIN && appleskin != null) {
			c.push();
			c.translate(right - w, top);
			c.image(appleskin, 81 - w, 18, w, HudIcons.SIZE, 0xFFFFFFFF);
			c.pop();
			return;
		}
		c.fill(right - w, top, right, top + HudIcons.SIZE, EXHAUSTION);
	}

	/**
	 * Goldener Rand für die Sättigung von {@code from} bis {@code to} Punkten (2 Punkte = eine Keule, angefangene
	 * Keulen in Vierteln wie AppleSkin), Deckkraft {@code alpha}.
	 */
	private void drawSaturation(Canvas c, float from, float to, int right, int top, float alpha) {
		to = Math.max(0f, Math.min(to, HungerMath.MAX_FOOD));
		if (to <= 0 || alpha <= 0) return;
		int start = from > 0 ? (int) (from / 2f) : 0;
		int end = HungerMath.ceil(to / 2f);
		int a = Math.round(alpha * 255) << 24;
		int gold = (SATURATION & 0xFFFFFF) | a;
		int shade = (SATURATION_SHADE & 0xFFFFFF) | a;
		for (int i = start; i < end && i < 10; i++) {
			float part = to / 2f - i;
			int x = right - i * 8 - 9;
			int y = top + foodJitter[i];
			if (source == HungerLook.Source.APPLESKIN && appleskin != null) {
				c.push();
				c.translate(x, y);
				c.image(appleskin, HungerLook.saturationU(part), 0, HudIcons.SIZE, HudIcons.SIZE, color(alpha));
				c.pop();
			} else {
				// Sichtbare Spalten von rechts: ganz, ¾, ½, ¼.
				int cols = part >= 1 ? 9 : part > 0.5f ? 7 : part > 0.25f ? 5 : 3;
				paint(c, x, y, 9 - cols, gold, shade);
			}
		}
	}

	/** Blinkende Keulen für den Hunger, den das Essen in der Hand auffüllt (mit zartem leeren Hintergrund). */
	private void drawFoodPreview(Canvas c, HudIcons icons, HungerState s, int right, int top, float flash) {
		int after = HungerMath.foodAfter(s.food, s.heldNutrition);
		if (after <= s.food) return;
		int bg = color(flash * 0.25f);
		int fg = color(flash);
		int empty = s.hungerEffect ? HudIcons.FOOD_EMPTY_HUNGER : HudIcons.FOOD_EMPTY;
		for (int i = Math.max(0, s.food / 2); i < HungerMath.ceil(after / 2f) && i < 10; i++) {
			int x = right - i * 8 - 9;
			int y = top + foodJitter[i];
			boolean half = i * 2 + 1 == after;
			int icon = s.hungerEffect ? (half ? HudIcons.FOOD_HALF_HUNGER : HudIcons.FOOD_FULL_HUNGER)
					: (half ? HudIcons.FOOD_HALF : HudIcons.FOOD_FULL);
			icons.draw(c, empty, x, y, bg);
			icons.draw(c, icon, x, y, fg);
		}
	}

	/** Blinkende Herzen für das Leben, das die natürliche Heilung nach dem Essen voraussichtlich bringt. */
	private void drawHealthPreview(Canvas c, HudIcons icons, HungerState s, int left, int top, float flash) {
		int food = HungerMath.foodAfter(s.food, s.heldNutrition);
		float sat = HungerMath.saturationAfter(s.food, s.saturation, s.heldNutrition, s.heldSaturation);
		float gain = HungerMath.healthGain(food, sat, s.exhaustion, s.health, s.maxHealth, s.modernRegen);
		if (gain <= 0) return;
		int now = HungerMath.ceil(s.health);
		int after = HungerMath.ceil(Math.min(s.maxHealth, s.health + gain));
		if (after <= now) return;
		int slots = HungerMath.heartSlots(s);
		int healthSlots = HungerMath.ceil(Math.max(s.maxHealth, s.health) / 2f);
		int rowHeight = HungerMath.heartRowHeight(s);
		int wave = HungerMath.regenWaveIndex(s);
		int fg = color(flash);
		for (int i = Math.max(0, now / 2); i < HungerMath.ceil(after / 2f) && i < slots && i < healthSlots; i++) {
			int x = left + (i % 10) * 8;
			int y = top - (i / 10) * rowHeight + heartJitter[i];
			if (i == wave) y -= 2;
			boolean half = i * 2 + 1 == after;
			int icon = s.hardcore ? (half ? HudIcons.HEART_HARDCORE_HALF : HudIcons.HEART_HARDCORE_FULL)
					: (half ? HudIcons.HEART_HALF : HudIcons.HEART_FULL);
			icons.draw(c, icon, x, y, fg);
		}
	}

	/** Umriss ab Spalte {@code fromCol}; die unteren Kanten etwas dunkler, damit der Rand auf hellem Grund lesbar bleibt. */
	private void paint(Canvas c, int x, int y, int fromCol, int gold, int shade) {
		int[][][] rows = packOutline != null ? packOutline : OUTLINE;
		for (int row = 0; row < rows.length; row++) {
			int color = row >= 6 ? shade : gold;
			if (rows[row] == null) continue;
			for (int[] run : rows[row]) {
				int a = Math.max(run[0], fromCol);
				if (a < run[1]) c.fill(x + a, y + row, x + run[1], y + row + 1, color);
			}
		}
	}

	/**
	 * Baut den Blick neu, wenn sich die Ressourcen geändert haben. Ohne Store (Tests) bleibt die eingebaute Keule,
	 * und das Fehlen wird nicht gecacht – der nächste Store mit Generation 0 soll noch ankommen.
	 */
	private void refresh() {
		Textures.Store store = Textures.store();
		if (store == null) {
			source = HungerLook.Source.BUILTIN;
			packOutline = OUTLINE;
			appleskin = null;
			return;
		}
		int gen = store.resourceGeneration();
		if (gen == seenGen) return;
		byte[] skin = store.gameBytes(HungerLook.APPLESKIN);
		if (skin != null && skin.length > 0) {
			TextureRef tex = store.game(HungerLook.APPLESKIN, 256, 256);
			if (tex != null) {
				source = HungerLook.Source.APPLESKIN;
				appleskin = tex;
				packOutline = OUTLINE;
				seenGen = gen;
				return;
			}
		}
		appleskin = null;
		int[][][] built = HungerLook.fromPng(store.gameBytes(HungerLook.FOOD_EMPTY), false);
		if (built == null) built = HungerLook.fromPng(store.gameBytes(HungerLook.ICONS), true);
		if (built != null) {
			source = HungerLook.Source.PACK;
			packOutline = built;
		} else {
			source = HungerLook.Source.BUILTIN;
			packOutline = OUTLINE;
		}
		seenGen = gen;
	}

	private static int color(float alpha) {
		return (Math.round(Math.max(0f, Math.min(1f, alpha)) * 255) << 24) | 0xFFFFFF;
	}

	private static boolean solid(int x, int y) {
		return y >= 0 && y < SHAPE.length && x >= 0 && x < SHAPE[y].length() && SHAPE[y].charAt(x) == '#';
	}

	/** Pixel der Form, die an Leere grenzen (4er-Nachbarschaft), als Läufe je Zeile. */
	static int[][][] outline() {
		int[][][] rows = new int[SHAPE.length][][];
		for (int y = 0; y < SHAPE.length; y++) {
			java.util.List<int[]> runs = new java.util.ArrayList<int[]>();
			int start = -1;
			for (int x = 0; x <= SHAPE[y].length(); x++) {
				boolean edge = solid(x, y) && (!solid(x - 1, y) || !solid(x + 1, y) || !solid(x, y - 1) || !solid(x, y + 1));
				if (edge && start < 0) start = x;
				if (!edge && start >= 0) {
					runs.add(new int[]{start, x});
					start = -1;
				}
			}
			rows[y] = runs.toArray(new int[0][]);
		}
		return rows;
	}
}
