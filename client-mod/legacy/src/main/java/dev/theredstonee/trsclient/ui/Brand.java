package dev.theredstonee.trsclient.ui;

import net.minecraft.client.gui.FontRenderer;

/** Markenfarben (ARGB) und kleine Zeichenhilfen im TRS-Stil. */
public final class Brand {
	/** Tiefenschiefer – Hintergrund. */
	public static final int BG = 0xFF17171E;
	/** Tiefenschiefer – Flächen/Karten. */
	public static final int SURFACE = 0xFF1D1D26;
	public static final int SURFACE_HOVER = 0xFF262631;
	public static final int BORDER = 0xFF2E2E3B;
	/** Redstone-Rot – Akzent. */
	public static final int RED = 0xFFE0281E;
	public static final int RED_HOVER = 0xFFF0463C;
	/** Lampen-Bernstein – "aktiv/an". */
	public static final int AMBER = 0xFFFFB84D;
	public static final int OFF = 0xFF3A3A48;
	public static final int TEXT = 0xFFECECF1;
	public static final int TEXT_DIM = 0xFF8C8C9E;
	/** Halbtransparenter HUD-Hintergrund. */
	public static final int HUD_BG = 0x9017171E;
	public static final int HUD_BG_PRESSED = 0xE0FFB84D;
	public static final int SCRIM = 0xB00C0C11;

	private Brand() {
	}

	/** Rahmen mit 1 px Stärke. */
	public static void outline(Gfx g, int x, int y, int w, int h, int color) {
		g.outline(x, y, w, h, color);
	}

	/** An/Aus-Pille; liefert die Breite. */
	public static int pill(Gfx g, FontRenderer font, int x, int y, boolean on, boolean hover) {
		String label = on ? "An" : "Aus";
		int w = 26;
		int h = 11;
		int bg = on ? AMBER : (hover ? 0xFF4A4A5A : OFF);
		g.fill(x, y, x + w, y + h, bg);
		// Kleiner "Schalter"-Knopf
		int knobX = on ? x + w - 4 : x + 1;
		g.fill(knobX, y + 1, knobX + 3, y + h - 1, on ? 0xFFFFE2B0 : 0xFF6A6A7A);
		int tx = on ? x + 3 : x + 6;
		g.text(font, label, tx, y + 2, on ? BG : TEXT, false);
		return w;
	}

	/** Umrandeter Text-Button. */
	public static void button(Gfx g, FontRenderer font, int x, int y, int w, int h, String label, boolean primary, boolean hover) {
		if (primary) {
			g.fill(x, y, x + w, y + h, hover ? RED_HOVER : RED);
		} else {
			g.fill(x, y, x + w, y + h, hover ? SURFACE_HOVER : SURFACE);
			outline(g, x, y, w, h, hover ? TEXT_DIM : BORDER);
		}
		g.centered(font, label, x + w / 2, y + (h - 8) / 2, primary ? 0xFFFFFFFF : TEXT);
	}
}
