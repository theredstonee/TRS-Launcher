package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.ui.Brand;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;

/** Tastenanzeige: W A S D, linke/rechte Maustaste (optional mit CPS) und Leertaste. */
public final class KeystrokesHud extends HudElement {
	private static final int KEY = 22;
	private static final int GAP = 2;
	private static final int WIDTH = KEY * 3 + GAP * 2;
	private static final int MOUSE_W = (WIDTH - GAP) / 2;
	private static final int MOUSE_H_CPS = 28;
	private static final int SPACE_H = 11;
	/** Vorberechnete CPS-Texte (keine String-Verkettung pro Frame). */
	private static final String[] CPS_TEXT = new String[100];

	static {
		for (int i = 0; i < CPS_TEXT.length; i++) CPS_TEXT[i] = i + " CPS";
	}

	private final TrsModules modules;
	/** Beschriftungen der Bewegungstasten, neu berechnet nur bei geänderter Belegung. */
	private final String[] labels = new String[4];
	private final String[] boundKeys = new String[4];
	/** Bewegungstasten (lazy, da die Optionen beim Mod-Start evtl. noch fehlen). */
	private KeyMapping[] move;

	public KeystrokesHud(HudModule module, TrsModules modules) {
		super(module);
		this.modules = modules;
	}

	@Override
	public int width(Font font, boolean preview) {
		return WIDTH;
	}

	@Override
	public int height(Font font, boolean preview) {
		int h = KEY * 2 + GAP + GAP + mouseHeight();
		if (modules.keystrokesShowSpace.get()) h += GAP + SPACE_H;
		return h;
	}

	@Override
	public void draw(Gfx g, Font font, boolean preview) {
		if (move == null) move = new KeyMapping[]{mc.options.keyUp, mc.options.keyLeft, mc.options.keyDown, mc.options.keyRight};
		int bg = module.backgroundArgb();
		int color = textColor();

		key(g, font, KEY + GAP, 0, KEY, KEY, label(font, 0, move[0]), move[0].isDown(), bg, color, module.shadow());
		int y = KEY + GAP;
		for (int i = 1; i < 4; i++) {
			key(g, font, (i - 1) * (KEY + GAP), y, KEY, KEY, label(font, i, move[i]), move[i].isDown(), bg, color, module.shadow());
		}
		y += KEY + GAP;

		long now = System.currentTimeMillis();
		int mh = mouseHeight();
		boolean showCps = modules.keystrokesShowCps.get();
		mouse(g, font, 0, y, mh, "LMT", mc.options.keyAttack.isDown(), bg, color, module.shadow(),
				showCps ? TrsClient.get().leftClicks().count(now) : -1);
		mouse(g, font, MOUSE_W + GAP, y, mh, "RMT", mc.options.keyUse.isDown(), bg, color, module.shadow(),
				showCps ? TrsClient.get().rightClicks().count(now) : -1);
		y += mh;

		if (modules.keystrokesShowSpace.get()) {
			y += GAP;
			boolean down = mc.options.keyJump.isDown();
			fillKey(g, 0, y, WIDTH, SPACE_H, down, bg);
			int bar = down ? Brand.BG : color;
			g.fill(WIDTH / 2 - 14, y + 5, WIDTH / 2 + 14, y + 6, bar);
		}
	}

	private int mouseHeight() {
		return modules.keystrokesShowCps.get() ? MOUSE_H_CPS : KEY;
	}

	private String label(Font font, int idx, KeyMapping mapping) {
		// saveString() liefert den gespeicherten Namen der Taste – gleiche Referenz, solange unverändert.
		String bound = mapping.saveString();
		if (bound != boundKeys[idx] || labels[idx] == null) {
			boundKeys[idx] = bound;
			labels[idx] = Gfx.clip(font, Mc.keyName(mapping), KEY - 4);
		}
		return labels[idx];
	}

	private static void key(Gfx g, Font font, int x, int y, int w, int h, String label, boolean down, int bg, int color, boolean shadow) {
		fillKey(g, x, y, w, h, down, bg);
		int tw = font.width(label);
		g.text(font, label, x + (w - tw) / 2, y + (h - 8) / 2, down ? Brand.BG : color, shadow && !down);
	}

	private static void mouse(Gfx g, Font font, int x, int y, int h, String label, boolean down, int bg, int color, boolean shadow, int cps) {
		fillKey(g, x, y, MOUSE_W, h, down, bg);
		int textColor = down ? Brand.BG : color;
		boolean textShadow = shadow && !down;
		if (cps < 0) {
			g.text(font, label, x + (MOUSE_W - font.width(label)) / 2, y + (h - 8) / 2, textColor, textShadow);
		} else {
			String cpsText = CPS_TEXT[Math.min(cps, CPS_TEXT.length - 1)];
			g.text(font, label, x + (MOUSE_W - font.width(label)) / 2, y + 5, textColor, textShadow);
			g.text(font, cpsText, x + (MOUSE_W - font.width(cpsText)) / 2, y + 16, textColor, textShadow);
		}
	}

	private static void fillKey(Gfx g, int x, int y, int w, int h, boolean down, int bg) {
		if (down) g.fill(x, y, x + w, y + h, Brand.HUD_BG_PRESSED);
		else if (bg != 0) g.fill(x, y, x + w, y + h, bg);
	}
}
