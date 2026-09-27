package dev.theredstonee.trsclient.core.tooltip;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.TextureRef;

/**
 * Zusatzkarte am Tooltip: Karten-Vorschau, Shulker-Raster (in der Farbe der Kiste) und Hunger/Sättigung – untereinander
 * in einer Karte im Stil des Vanilla-Tooltips. Wiederverwendbar (je Bild {@link #clear()} und neu füllen).
 */
public final class TooltipCard {
	static final int PAD = 4;
	static final int GAP = 4;
	static final int SLOT = 18;
	/** Vanilla-Tooltip: Hintergrund und Rahmen (oben/unten). */
	static final int BACKGROUND = 0xF0100010;
	static final int BORDER_TOP = 0x505000FF;
	static final int BORDER_BOTTOM = 0x5028007F;

	// Karte
	private TextureRef mapTexture;
	private int mapSize;
	private boolean mapMissing;
	// Raster
	private Object[] items;
	private int itemCount;
	private int columns;
	private int gridColor;
	// Essen
	private boolean food;
	private int nutrition;
	private float saturation;

	public void clear() {
		mapTexture = null;
		mapMissing = false;
		items = null;
		itemCount = 0;
		food = false;
	}

	public boolean empty() {
		return mapTexture == null && !mapMissing && items == null && !food;
	}

	/** Karten-Vorschau (Textur aus {@link MapPreviews}) in {@code size}×{@code size}; null = Daten fehlen noch. */
	public void map(TextureRef texture, int size) {
		mapTexture = texture;
		mapSize = size;
		mapMissing = texture == null;
	}

	/**
	 * Raster eines Behälters.
	 *
	 * @param stacks  ItemStacks der Version (null/leer = freies Feld); Länge = Anzahl Felder
	 * @param columns Spalten (Shulker: 9)
	 * @param argb    Farbe der Kiste
	 */
	public void grid(Object[] stacks, int count, int columns, int argb) {
		this.items = stacks;
		this.itemCount = Math.max(0, Math.min(count, stacks == null ? 0 : stacks.length));
		this.columns = Math.max(1, columns);
		this.gridColor = argb;
	}

	/** Hunger (Punkte, 2 = eine Keule) und Sättigung (Punkte). */
	public void food(int nutrition, float saturation) {
		this.food = nutrition > 0 || saturation > 0;
		this.nutrition = nutrition;
		this.saturation = saturation;
	}

	// --- Maße ---

	public int width(Canvas c) {
		int w = 0;
		if (mapTexture != null) w = Math.max(w, mapSize + 2);
		if (mapMissing) w = Math.max(w, c.textWidth(I18n.tr("tooltips.mapMissing")));
		if (items != null) w = Math.max(w, columns * SLOT + 2);
		if (food) w = Math.max(w, Math.max(foodRow(c, nutrition, false), foodRow(c, saturation, true)));
		return w + PAD * 2;
	}

	public int height(Canvas c) {
		int h = 0;
		int sections = 0;
		if (mapTexture != null) {
			h += mapSize + 2;
			sections++;
		}
		if (mapMissing) {
			h += 9;
			sections++;
		}
		if (items != null) {
			h += rows() * SLOT + 2;
			sections++;
		}
		if (food) {
			h += FoodIcons.SIZE * 2 + 2;
			sections++;
		}
		return h + Math.max(0, sections - 1) * GAP + PAD * 2;
	}

	private int rows() {
		return Math.max(1, (itemCount + columns - 1) / columns);
	}

	private int foodRow(Canvas c, float points, boolean sat) {
		return FoodIcons.rowWidth(points) + 4 + c.textWidth(foodText(points, sat));
	}

	private static String foodText(float points, boolean sat) {
		if (sat) return I18n.tr("tooltips.food.saturation", format(points));
		return I18n.tr("tooltips.food.hunger", Math.round(points));
	}

	/** Eine Nachkommastelle, ohne „.0“. */
	static String format(float v) {
		int tenths = Math.round(v * 10);
		return tenths % 10 == 0 ? String.valueOf(tenths / 10) : (tenths / 10) + "." + Math.abs(tenths % 10);
	}

	// --- Zeichnen ---

	/** Zeichnet die Karte mit linker oberer Ecke (x, y). */
	public void draw(Canvas c, int x, int y) {
		int w = width(c), h = height(c);
		frame(c, x, y, w, h);
		int cx = x + PAD, cy = y + PAD;
		if (mapTexture != null) {
			c.fill(cx, cy, cx + mapSize + 2, cy + mapSize + 2, 0xFF6B5A3A);
			if (c.images()) {
				c.push();
				c.translate(cx + 1, cy + 1);
				c.scale(mapSize / 128f);
				c.image(mapTexture, 0, 0, 128, 128, 0xFFFFFFFF);
				c.pop();
			} else {
				c.fill(cx + 1, cy + 1, cx + 1 + mapSize, cy + 1 + mapSize, MapPreviews.PAPER);
			}
			cy += mapSize + 2 + GAP;
		}
		if (mapMissing) {
			c.text(I18n.tr("tooltips.mapMissing"), cx, cy, 0xFFAAAAAA, true);
			cy += 9 + GAP;
		}
		if (items != null) {
			int rows = rows();
			int gw = columns * SLOT + 2, gh = rows * SLOT + 2;
			int base = gridColor | 0xFF000000;
			c.fill(cx, cy, cx + gw, cy + gh, ColorMath.lerp(base, 0xFF000000, 0.45f));
			for (int i = 0; i < rows * columns; i++) {
				int sx = cx + 1 + (i % columns) * SLOT, sy = cy + 1 + (i / columns) * SLOT;
				c.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, ColorMath.lerp(base, 0xFF000000, 0.2f));
				c.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + 2, ColorMath.lerp(base, 0xFF000000, 0.55f));
				c.fill(sx + 1, sy + 1, sx + 2, sy + SLOT - 1, ColorMath.lerp(base, 0xFF000000, 0.55f));
			}
			c.flush();
			for (int i = 0; i < itemCount; i++) {
				Object stack = items[i];
				if (stack == null) continue;
				c.item(stack, cx + 2 + (i % columns) * SLOT, cy + 2 + (i / columns) * SLOT);
			}
			cy += gh + GAP;
		}
		if (food) {
			FoodIcons.row(c, cx, cy, nutrition, false);
			int tx = cx + Math.max(FoodIcons.rowWidth(nutrition), FoodIcons.rowWidth(saturation)) + 4;
			c.text(foodText(nutrition, false), tx, cy + 1, 0xFFE0C090, true);
			int sy = cy + FoodIcons.SIZE + 2;
			if (saturation > 0) FoodIcons.row(c, cx, sy, saturation, true);
			c.text(foodText(saturation, true), tx, sy + 1, 0xFFFFE27A, true);
		}
		c.flush();
	}

	/** Hintergrund und Rahmen wie der Vanilla-Tooltip (Ecken ausgespart). */
	static void frame(Canvas c, int x, int y, int w, int h) {
		c.fill(x + 1, y, x + w - 1, y + h, BACKGROUND);
		c.fill(x, y + 1, x + 1, y + h - 1, BACKGROUND);
		c.fill(x + w - 1, y + 1, x + w, y + h - 1, BACKGROUND);
		// Rahmen: oben hell, unten dunkel, Seiten in zwei Hälften.
		c.fill(x + 1, y + 1, x + w - 1, y + 2, BORDER_TOP);
		c.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, BORDER_BOTTOM);
		int mid = y + h / 2;
		c.fill(x + 1, y + 2, x + 2, mid, BORDER_TOP);
		c.fill(x + w - 2, y + 2, x + w - 1, mid, BORDER_TOP);
		c.fill(x + 1, mid, x + 2, y + h - 2, BORDER_BOTTOM);
		c.fill(x + w - 2, mid, x + w - 1, y + h - 2, BORDER_BOTTOM);
	}
}
