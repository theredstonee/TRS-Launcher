package dev.theredstonee.trsclient.ui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;

/** Markenfarben (ARGB) und kleine Zeichenhilfen im TRS-Stil (1.8.9-Variante). */
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

	private Brand() {
	}

	/** Gefülltes Rechteck (x, y, Breite, Höhe). */
	public static void rect(int x, int y, int w, int h, int color) {
		Gui.drawRect(x, y, x + w, y + h, color);
	}

	/** Rahmen mit 1 px Stärke. */
	public static void outline(int x, int y, int w, int h, int color) {
		Gui.drawRect(x, y, x + w, y + 1, color);
		Gui.drawRect(x, y + h - 1, x + w, y + h, color);
		Gui.drawRect(x, y + 1, x + 1, y + h - 1, color);
		Gui.drawRect(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	/** Text zeichnen; setzt vorher die GL-Farbe zurück (sonst färbt drawRect nach). */
	public static void text(FontRenderer font, String s, int x, int y, int color, boolean shadow) {
		GlStateManager.color(1f, 1f, 1f, 1f);
		font.drawString(s, x, y, color, shadow);
	}

	public static void centered(FontRenderer font, String s, int centerX, int y, int color, boolean shadow) {
		text(font, s, centerX - font.getStringWidth(s) / 2, y, color, shadow);
	}

	/** An/Aus-Pille; liefert die Breite. */
	public static int pill(FontRenderer font, int x, int y, boolean on, boolean hover) {
		String label = on ? "An" : "Aus";
		int w = 26;
		int h = 11;
		int bg = on ? AMBER : (hover ? 0xFF4A4A5A : OFF);
		rect(x, y, w, h, bg);
		// Kleiner "Schalter"-Knopf
		int knobX = on ? x + w - 4 : x + 1;
		rect(knobX, y + 1, 3, h - 2, on ? 0xFFFFE2B0 : 0xFF6A6A7A);
		int tx = on ? x + 3 : x + 6;
		text(font, label, tx, y + 2, on ? BG : TEXT, false);
		return w;
	}

	/** Umrandeter Text-Button. */
	public static void button(FontRenderer font, int x, int y, int w, int h, String label, boolean primary, boolean hover) {
		if (primary) {
			rect(x, y, w, h, hover ? RED_HOVER : RED);
		} else {
			rect(x, y, w, h, hover ? SURFACE_HOVER : SURFACE);
			outline(x, y, w, h, hover ? TEXT_DIM : BORDER);
		}
		centered(font, label, x + w / 2, y + (h - 8) / 2, primary ? 0xFFFFFFFF : TEXT, primary);
	}
}
