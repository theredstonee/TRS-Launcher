package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.FontRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD-Element aus einer oder mehreren Textzeilen. Der Inhalt wird höchstens alle
 * {@link #REFRESH_MS} Millisekunden neu aufgebaut (Uhrzeit, Koordinaten, Effekte …),
 * Breiten werden dabei einmal berechnet – das Zeichnen selbst alloziert nichts.
 */
public abstract class LinesHudElement extends HudElement {
	private static final int PAD_X = 5;
	private static final int PAD_Y = 4;
	private static final int LINE_H = 10;
	private static final long REFRESH_MS = 100;

	private final List<String> lines = new ArrayList<>();
	/** Zeilenfarbe (ARGB) oder 0 = Textfarbe des Moduls. */
	private final List<Integer> colors = new ArrayList<>();
	private int maxWidth;
	private long lastRefresh;
	private boolean lastPreview;

	protected LinesHudElement(HudModule module) {
		super(module);
	}

	/** Füllt die Zeilen neu über {@link #line}. {@code preview} = Editor mit Beispielwerten. */
	protected abstract void build(boolean preview);

	/** Fügt eine Zeile hinzu; {@code argb} = 0 → Textfarbe des Moduls. */
	protected final void line(String text, int argb) {
		lines.add(text);
		colors.add(argb);
	}

	protected final void line(String text) {
		line(text, 0);
	}

	private void refresh(FontRenderer font, boolean preview) {
		long now = System.currentTimeMillis();
		if (now - lastRefresh < REFRESH_MS && preview == lastPreview && !lines.isEmpty()) return;
		lastRefresh = now;
		lastPreview = preview;
		lines.clear();
		colors.clear();
		build(preview);
		maxWidth = 0;
		for (String l : lines) maxWidth = Math.max(maxWidth, font.getStringWidth(l));
	}

	/** Sichtbar, wenn es (im Spiel) überhaupt Zeilen gibt. */
	@Override
	public boolean visible() {
		refresh(Mc.font(), false);
		return !lines.isEmpty();
	}

	@Override
	public int width(FontRenderer font, boolean preview) {
		refresh(font, preview);
		return maxWidth + PAD_X * 2;
	}

	@Override
	public int height(FontRenderer font, boolean preview) {
		refresh(font, preview);
		return Math.max(1, lines.size()) * LINE_H - 2 + PAD_Y * 2;
	}

	@Override
	public void draw(Gfx g, FontRenderer font, boolean preview) {
		refresh(font, preview);
		int bg = module.backgroundArgb();
		if (bg != 0) g.fill(0, 0, width(font, preview), height(font, preview), bg);
		int text = textColor();
		for (int i = 0, n = lines.size(); i < n; i++) {
			int c = colors.get(i);
			g.text(font, lines.get(i), PAD_X, PAD_Y + i * LINE_H, c == 0 ? text : c, module.shadow());
		}
	}
}
