package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.module.HudModule;

/**
 * Bilder pro Sekunde. 1.7.10 hat kein {@code Minecraft.getDebugFPS()} (das Feld ist privat) –
 * Minecraft schreibt den Wert aber jede Sekunde in {@code mc.debug} ("123 fps, 4 chunk updates").
 */
public final class FpsHud extends TextHudElement {
	private String lastDebug;
	private int fps;

	public FpsHud(HudModule module) {
		super(module);
	}

	@Override
	protected long valueKey(boolean preview) {
		String debug = mc.debug;
		if (debug != lastDebug) {
			lastDebug = debug;
			fps = parse(debug);
		}
		return fps;
	}

	/** Führende Zahl von "123 fps, ..." (0, falls noch nichts gemessen). */
	static int parse(String debug) {
		if (debug == null) return 0;
		int n = 0;
		for (int i = 0; i < debug.length(); i++) {
			char c = debug.charAt(i);
			if (c < '0' || c > '9') break;
			n = n * 10 + (c - '0');
			if (n > 100000) break;
		}
		return n;
	}

	@Override
	protected String format(long key) {
		return key + " FPS";
	}
}
