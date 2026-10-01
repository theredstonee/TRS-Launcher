package dev.theredstonee.trsclient.core.hunger;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

/**
 * Die Vanilla-Symbole für Keulen und Herzen (9×9) – je nach Version aus {@code icons.png} (bis 1.20.1) oder als
 * einzelne Sprite-Dateien (ab 1.20.2). Über die Spiel-Ressourcen geladen, damit Resource Packs mitgehen.
 */
public final class HudIcons {
	public static final int SIZE = 9;

	public static final int FOOD_EMPTY = 0;
	public static final int FOOD_FULL = 1;
	public static final int FOOD_HALF = 2;
	public static final int FOOD_EMPTY_HUNGER = 3;
	public static final int FOOD_FULL_HUNGER = 4;
	public static final int FOOD_HALF_HUNGER = 5;
	public static final int HEART_FULL = 6;
	public static final int HEART_HALF = 7;
	public static final int HEART_HARDCORE_FULL = 8;
	public static final int HEART_HARDCORE_HALF = 9;
	private static final int COUNT = 10;

	private static final String[] SPRITES = {"food_empty", "food_full", "food_half", "food_empty_hunger", "food_full_hunger",
			"food_half_hunger", "heart/full", "heart/half", "heart/hardcore_full", "heart/hardcore_half"};
	/** Lage in {@code icons.png} (u, v). */
	private static final int[][] SHEET = {{16, 27}, {52, 27}, {61, 27}, {133, 27}, {88, 27}, {97, 27}, {52, 0}, {61, 0},
			{52, 45}, {61, 45}};

	private final TextureRef[] textures = new TextureRef[COUNT];
	private final int[] u = new int[COUNT];
	private final int[] v = new int[COUNT];

	private HudIcons() {
	}

	/** Ab 1.20.2: {@code textures/gui/sprites/hud/…}. */
	public static HudIcons sprites(Textures.Store store) {
		HudIcons icons = new HudIcons();
		for (int i = 0; i < COUNT; i++) {
			icons.textures[i] = store.game("minecraft:textures/gui/sprites/hud/" + SPRITES[i] + ".png", SIZE, SIZE);
		}
		return icons;
	}

	/** Bis 1.20.1: alles in {@code textures/gui/icons.png} (256×256). */
	public static HudIcons sheet(Textures.Store store) {
		HudIcons icons = new HudIcons();
		TextureRef sheet = store.game("minecraft:textures/gui/icons.png", 256, 256);
		for (int i = 0; i < COUNT; i++) {
			icons.textures[i] = sheet;
			icons.u[i] = SHEET[i][0];
			icons.v[i] = SHEET[i][1];
		}
		return icons;
	}

	/** Zeichnet Symbol {@code icon} an (x, y), eingefärbt mit {@code argb} (Alpha = Deckkraft). */
	public void draw(Canvas c, int icon, int x, int y, int argb) {
		TextureRef t = textures[icon];
		if (t == null) return;
		c.push();
		c.translate(x, y);
		c.image(t, u[icon], v[icon], SIZE, SIZE, argb);
		c.pop();
	}
}
