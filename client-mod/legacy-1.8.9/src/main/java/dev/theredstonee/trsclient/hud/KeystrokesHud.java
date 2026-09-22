package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.ui.Brand;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

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
	private final int[] boundKeys = new int[4];
	/** Bewegungstasten (lazy, da die Optionen beim Mod-Start evtl. noch fehlen). */
	private KeyBinding[] move;

	public KeystrokesHud(HudModule module, TrsModules modules) {
		super(module);
		this.modules = modules;
	}

	@Override
	public int width(FontRenderer font, boolean preview) {
		return WIDTH;
	}

	@Override
	public int height(FontRenderer font, boolean preview) {
		int h = KEY * 2 + GAP + GAP + mouseHeight();
		if (modules.keystrokesShowSpace.get()) h += GAP + SPACE_H;
		return h;
	}

	@Override
	public void draw(FontRenderer font, boolean preview) {
		if (move == null) {
			GameSettings o = mc.gameSettings;
			move = new KeyBinding[]{o.keyBindForward, o.keyBindLeft, o.keyBindBack, o.keyBindRight};
		}
		boolean bg = module.background.get();
		int color = textColor();

		key(font, KEY + GAP, 0, KEY, KEY, label(font, 0, move[0]), move[0].isKeyDown(), bg, color);
		int y = KEY + GAP;
		for (int i = 1; i < 4; i++) {
			key(font, (i - 1) * (KEY + GAP), y, KEY, KEY, label(font, i, move[i]), move[i].isKeyDown(), bg, color);
		}
		y += KEY + GAP;

		long now = System.currentTimeMillis();
		int mh = mouseHeight();
		boolean showCps = modules.keystrokesShowCps.get();
		mouse(font, 0, y, mh, "LMT", mc.gameSettings.keyBindAttack.isKeyDown(), bg, color,
				showCps ? TrsClient.get().leftClicks().count(now) : -1);
		mouse(font, MOUSE_W + GAP, y, mh, "RMT", mc.gameSettings.keyBindUseItem.isKeyDown(), bg, color,
				showCps ? TrsClient.get().rightClicks().count(now) : -1);
		y += mh;

		if (modules.keystrokesShowSpace.get()) {
			y += GAP;
			boolean down = mc.gameSettings.keyBindJump.isKeyDown();
			fillKey(0, y, WIDTH, SPACE_H, down, bg);
			int bar = down ? Brand.BG : color;
			Brand.rect(WIDTH / 2 - 14, y + 5, 28, 1, bar);
		}
	}

	private int mouseHeight() {
		return modules.keystrokesShowCps.get() ? MOUSE_H_CPS : KEY;
	}

	private String label(FontRenderer font, int idx, KeyBinding mapping) {
		int bound = mapping.getKeyCode();
		if (bound != boundKeys[idx] || labels[idx] == null) {
			boundKeys[idx] = bound;
			labels[idx] = font.trimStringToWidth(GameSettings.getKeyDisplayString(bound), KEY - 4);
		}
		return labels[idx];
	}

	private static void key(FontRenderer font, int x, int y, int w, int h, String label, boolean down, boolean bg, int color) {
		fillKey(x, y, w, h, down, bg);
		int tw = font.getStringWidth(label);
		Brand.text(font, label, x + (w - tw) / 2, y + (h - 8) / 2, down ? Brand.BG : color, !bg && !down);
	}

	private static void mouse(FontRenderer font, int x, int y, int h, String label, boolean down, boolean bg, int color, int cps) {
		fillKey(x, y, MOUSE_W, h, down, bg);
		int textColor = down ? Brand.BG : color;
		boolean shadow = !bg && !down;
		if (cps < 0) {
			Brand.text(font, label, x + (MOUSE_W - font.getStringWidth(label)) / 2, y + (h - 8) / 2, textColor, shadow);
		} else {
			String cpsText = CPS_TEXT[Math.min(cps, CPS_TEXT.length - 1)];
			Brand.text(font, label, x + (MOUSE_W - font.getStringWidth(label)) / 2, y + 5, textColor, shadow);
			Brand.text(font, cpsText, x + (MOUSE_W - font.getStringWidth(cpsText)) / 2, y + 16, textColor, shadow);
		}
	}

	private static void fillKey(int x, int y, int w, int h, boolean down, boolean bg) {
		if (down) Brand.rect(x, y, w, h, Brand.HUD_BG_PRESSED);
		else if (bg) Brand.rect(x, y, w, h, Brand.HUD_BG);
	}
}
