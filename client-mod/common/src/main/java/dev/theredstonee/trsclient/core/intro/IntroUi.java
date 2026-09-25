package dev.theredstonee.trsclient.core.intro;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.KeySetting;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.ModulePacks;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.perf.GameOptions;
import dev.theredstonee.trsclient.core.perf.Performance;
import dev.theredstonee.trsclient.core.sync.ClientSync;
import dev.theredstonee.trsclient.core.ui.Anim;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.FadeCanvas;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.PixelFont;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.core.ui.menu.MenuHost;
import dev.theredstonee.trsclient.core.ui.menu.PacksPage;

import java.util.ArrayList;
import java.util.List;

/**
 * Die Einführung beim ersten Start – im Redstone-Stil des TRS-Menüs, jederzeit überspringbar:
 * <ol>
 *   <li>Sprache und Aussehen (Thema, Akzent) – vorbelegt aus Launcher/TRS-Konto;</li>
 *   <li>Leistung: Config-Modus „Schön“/„Max FPS“, Bildraten-Grenze, VSync, Dynamische FPS;</li>
 *   <li>Tastenbelegung der TRS-Tasten mit Konflikten zu Vanilla-/Mod-Tasten, direkt änderbar;</li>
 *   <li>Modul-Paket mit HUD-Vorlage.</li>
 * </ol>
 * Einmal je TRS-Konto (Stand im {@code client}-Sync), ohne TRS-Dienste einmal je Instanz; im TRS-Menü unter
 * „Modul-Pakete“ erneut startbar.
 */
public final class IntroUi extends UiScreen {
	static final int STEPS = 4;
	static final int LOOK = 0;
	static final int PERF = 1;
	static final int KEYS = 2;
	static final int PACKS = 3;

	private static final String[] STEP_IDS = {"look", "perf", "keys", "packs"};
	/** Sprachen mit Eigennamen (werden nicht übersetzt). */
	private static final String[][] LANGUAGES = {
			{"en", "English"}, {"de", "Deutsch"}, {"es", "Español"}, {"fr", "Français"},
			{"pl", "Polski"}, {"pt-BR", "Português (BR)"}, {"tr", "Türkçe"}, {"nl", "Nederlands"}};
	private static final String[] THEMES = {"dark", "oled", "light"};
	private static final String[] ACCENTS = {"redstone", "lamp", "emerald", "lapis", "amethyst"};
	/** TRS-Tasten des Schritts „Tastenbelegung“ (Reihenfolge der Anzeige). */
	static final String[] TRS_KEYS = {"key.trsclient.menu", "key.trsclient.zoom", "key.trsclient.freelook",
			"key.trsclient.emoteWheel", "key.trsclient.saveClip", "key.trsclient.toggleRecording"};
	private static final int HEADER_H = 30;
	private static final int FOOTER_H = 28;
	private static final int PAD = 12;

	private final MenuHost host;
	private final TrsModules modules;
	private final PacksPage packs;
	private final List<KeyBind> allKeys;
	private final List<KeyBind> trsKeys = new ArrayList<KeyBind>();
	private int step;
	private float stepAnim;
	private KeyBind capturing;
	private boolean finished;

	public IntroUi(final MenuHost host) {
		this.host = host;
		this.modules = host.modules();
		I18n.refresh();
		this.packs = new PacksPage(modules, new ModulePacks.Support() {
			@Override
			public boolean supports(Module module) {
				return host.supports(module);
			}
		}, new Runnable() {
			@Override
			public void run() {
				host.playClick();
			}
		});
		List<KeyBind> keys;
		try {
			keys = host.keyBindings();
		} catch (RuntimeException e) {
			keys = new ArrayList<KeyBind>();
		}
		this.allKeys = keys;
		for (String id : TRS_KEYS) {
			for (KeyBind b : allKeys) {
				if (b.id.equals(id)) {
					trsKeys.add(b);
					break;
				}
			}
		}
	}

	// --- Für den Selbsttest ---

	public int step() {
		return step;
	}

	/** Springt zu einem Schritt (Selbsttest: Screenshot je Schritt). */
	public void goTo(int s) {
		step = Math.max(0, Math.min(STEPS - 1, s));
		capturing = null;
	}

	public PacksPage packs() {
		return packs;
	}

	/** TRS-Tasten im Schritt „Tastenbelegung“ (Selbsttest). */
	public List<KeyBind> trsKeys() {
		return trsKeys;
	}

	// --- Ablauf ---

	void next() {
		capturing = null;
		if (step < STEPS - 1) {
			step++;
			stepAnim = 0f;
		} else {
			finish();
		}
	}

	void back() {
		capturing = null;
		if (step > 0) {
			step--;
			stepAnim = 0f;
		}
	}

	/** Fertig: gewähltes Paket anwenden, Einführung als erledigt merken (auch fürs TRS-Konto). */
	public void finish() {
		if (finished) return;
		finished = true;
		ModulePacks.Pack pack = packs.selected();
		if (pack != null) packs.applySelected();
		modules.clientState.markIntro(ClientState.FINISHED, pack == null ? null : pack.id, System.currentTimeMillis());
		host.save();
		IntroGate.notice(I18n.tr("intro.doneNotice", host.menuKeyLabel()));
		requestClose();
	}

	/** Überspringen (jederzeit): bisher Gewähltes bleibt, die Einführung gilt als erledigt. */
	public void skip() {
		if (finished) return;
		finished = true;
		modules.clientState.markIntro(ClientState.SKIPPED, null, System.currentTimeMillis());
		host.save();
		IntroGate.notice(I18n.tr("intro.skippedNotice", host.menuKeyLabel()));
		requestClose();
	}

	@Override
	protected void onClosed() {
		host.closeScreen();
	}

	// --- Zeichnen ---

	@Override
	protected void draw(Canvas raw, int width, int height, int mouseX, int mouseY, float dt) {
		Theme t = Theme.get();
		Canvas c = FadeCanvas.of(raw, alpha());
		c.fill(0, 0, width, height, t.scrim);
		stepAnim = Anim.approach(stepAnim, 1f, dt, 0.06f);
		int pw = Math.min(width - 16, Math.max(Math.min(420, width - 16), Math.min(640, Math.round(width * 0.8f))));
		int ph = Math.min(height - 16, Math.max(Math.min(250, height - 16), Math.min(400, Math.round(height * 0.84f))));
		int px = (width - pw) / 2;
		int py = (height - ph) / 2 + Math.round((1 - Anim.easeOut(open)) * 14);
		c.push();
		c.raise(300f);
		Redstone.window(c, px, py, pw, ph);
		header(c, px, py, pw);
		int bx = px + PAD;
		int by = py + HEADER_H + 8;
		int bw = pw - PAD * 2;
		int bh = ph - HEADER_H - FOOTER_H - 14;
		// Titel und Text des Schritts
		String title = I18n.tr("intro.step." + STEP_IDS[step] + ".title");
		c.text(c.clip(title, bw), bx, by, t.text, false);
		int ty = Paint.paragraph(c, I18n.tr("intro.step." + STEP_IDS[step] + ".text"), bx, by + 12, bw, 10, t.textDim) + 6;
		int body = by + Math.max(0, ty - by);
		int bodyH = by + bh - body;
		int slide = Math.round((1 - Anim.easeOut(stepAnim)) * 10);
		c.push();
		c.translate(slide, 0);
		switch (step) {
			case LOOK:
				look(c, bx, body, bw, bodyH, mouseX, mouseY);
				break;
			case PERF:
				perf(c, bx, body, bw, bodyH, mouseX, mouseY);
				break;
			case KEYS:
				keys(c, bx, body, bw, bodyH, mouseX, mouseY);
				break;
			default:
				packs.draw(c, hits, bx, body, bw, bodyH, mouseX, mouseY, false, null);
				break;
		}
		c.pop();
		footer(c, px, py + ph - FOOTER_H, pw, mouseX, mouseY);
		c.pop();
	}

	private void header(Canvas c, int px, int py, int pw) {
		Theme t = Theme.get();
		c.fill(px + 1, py + 2, px + pw - 1, py + HEADER_H, t.surfaceHigh);
		c.fill(px + 1, py + HEADER_H - 1, px + pw - 1, py + HEADER_H, t.border);
		int lx = px + PAD;
		int ly = py + (HEADER_H - PixelFont.HEIGHT * 2) / 2;
		List<int[]> rects = PixelFont.rects("TRS");
		int light = ColorMath.lerp(t.accent, 0xFFFFFFFF, 0.3f);
		for (int[] r : rects) c.fill(lx + r[0] * 2, ly + r[1] * 2, lx + r[2] * 2, ly + r[3] * 2, r[1] == 0 ? light : t.accent);
		int textX = lx + PixelFont.width("TRS") * 2 + 6;
		c.text(I18n.tr("intro.title"), textX, py + (HEADER_H - 8) / 2, t.textDim, false);
		// Schritte als Lampen, verbunden durch Staub: erledigte und der aktuelle leuchten.
		int lamp = 12;
		int spacing = 26;
		int total = (STEPS - 1) * spacing + lamp;
		int sx = px + pw - PAD - total;
		int sy = py + (HEADER_H - lamp) / 2;
		for (int i = 0; i < STEPS; i++) {
			int x = sx + i * spacing;
			if (i > 0) {
				boolean powered = i <= step;
				Redstone.dustH(c, x - spacing + lamp, x, sy + lamp / 2 - 1, powered ? t.dustOn : t.dustOff, powered ? 0.6f : 0f);
			}
			float lit = i < step ? 0.85f : (i == step ? 0.75f + 0.25f * (float) Math.sin(System.currentTimeMillis() / 300.0) : 0f);
			Redstone.lamp(c, x, sy, lamp, lamp, lit, 0f);
		}
	}

	private void footer(Canvas c, int px, int fy, int pw, int mx, int my) {
		Theme t = Theme.get();
		c.fill(px + 1, fy, px + pw - 1, fy + 1, t.border);
		int y = fy + 6;
		int h = 17;
		String skip = I18n.tr("intro.skip");
		int sw = c.textWidth(skip) + 20;
		int sx = px + PAD;
		Paint.button(c, sx, y, sw, h, skip, false, inside(mx, my, sx, y, sw, h));
		hits.add(sx, y, sw, h, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				skip();
			}
		});
		String nextLabel = step == STEPS - 1 ? I18n.tr("intro.finish") : I18n.tr("intro.next");
		int nw = Math.max(70, c.textWidth(nextLabel) + 24);
		int nx = px + pw - PAD - nw;
		Paint.button(c, nx, y, nw, h, nextLabel, true, inside(mx, my, nx, y, nw, h));
		hits.add(nx, y, nw, h, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				next();
			}
		});
		if (step > 0) {
			String backLabel = I18n.tr("intro.back");
			int bw = c.textWidth(backLabel) + 20;
			int bx = nx - 6 - bw;
			Paint.button(c, bx, y, bw, h, backLabel, false, inside(mx, my, bx, y, bw, h));
			hits.add(bx, y, bw, h, new Runnable() {
				@Override
				public void run() {
					host.playClick();
					back();
				}
			});
		}
		String counter = I18n.tr("intro.stepOf", step + 1, STEPS);
		int cw = c.textWidth(counter);
		int cx = sx + sw + 10;
		if (cx + cw < nx - 70) c.text(counter, cx, y + 5, t.textDim, false);
	}

	// --- Schritt 1: Sprache und Aussehen ---

	private void look(Canvas c, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		c.text(I18n.tr("intro.language"), x, y, t.text, false);
		int cols = w >= 360 ? 4 : 2;
		int gap = 4;
		int bw = (w - gap * (cols - 1)) / cols;
		int bh = 16;
		int ry = y + 11;
		String current = I18n.code();
		for (int i = 0; i < LANGUAGES.length; i++) {
			final String code = LANGUAGES[i][0];
			String label = LANGUAGES[i][1] + (I18n.BETA.contains(code) ? " β" : "");
			int bx = x + (i % cols) * (bw + gap);
			int by = ry + (i / cols) * (bh + gap);
			choice(c, bx, by, bw, bh, label, code.equals(current), mx, my, new Runnable() {
				@Override
				public void run() {
					chooseLook(null, null, code);
				}
			});
		}
		ry += ((LANGUAGES.length + cols - 1) / cols) * (bh + gap) + 6;
		c.text(I18n.tr("intro.theme"), x, ry, t.text, false);
		ry += 11;
		int tw = Math.min(110, (w - gap * 2) / 3);
		for (int i = 0; i < THEMES.length; i++) {
			final String theme = THEMES[i];
			choice(c, x + i * (tw + gap), ry, tw, bh, I18n.tr("intro.theme." + theme), theme.equals(t.themeName), mx, my, new Runnable() {
				@Override
				public void run() {
					chooseLook(theme, null, null);
				}
			});
		}
		int ax = x + THEMES.length * (tw + gap) + 10;
		int size = 16;
		if (ax + ACCENTS.length * (size + 4) > x + w) {
			ry += bh + gap + 4;
			ax = x;
		}
		for (int i = 0; i < ACCENTS.length; i++) {
			final String accent = ACCENTS[i];
			int sx = ax + i * (size + 4);
			boolean sel = accent.equals(t.accentName);
			boolean hover = inside(mx, my, sx, ry, size, size);
			int color = Theme.of("dark", accent, null).accent;
			if (sel) Redstone.glow(c, sx, ry, size, size, color, 0.9f);
			Redstone.block(c, sx, ry, size, size, sel ? 0xFFFFFFFF : (hover ? t.textDim : t.border));
			c.fill(sx + 2, ry + 2, sx + size - 2, ry + size - 2, color);
			hits.add(sx, ry, size, size, new Runnable() {
				@Override
				public void run() {
					host.playClick();
					chooseLook(null, accent, null);
				}
			});
		}
		ry += bh + 10;
		if (ry + 20 < y + h) {
			ClientSync sync = ClientSync.get();
			String note = sync != null && sync.enabled() ? I18n.tr("intro.look.synced") : I18n.tr("intro.look.local");
			Paint.paragraph(c, note, x, ry, w, 10, t.textDim);
		}
	}

	private void chooseLook(String theme, String accent, String language) {
		Theme cur = Theme.get();
		String th = theme != null ? theme : cur.themeName;
		String ac = accent != null ? accent : cur.accentName;
		String la = language != null ? language : I18n.code();
		ClientSync sync = ClientSync.get();
		if (sync != null) {
			sync.chooseLook(th, ac, la, System.currentTimeMillis());
		} else {
			modules.clientState.chooseLook(th, ac, la, System.currentTimeMillis());
			ClientSync.applyLook(th, ac, la);
		}
	}

	private void choice(Canvas c, int x, int y, int w, int h, String label, boolean selected, int mx, int my,
			final Runnable action) {
		Theme t = Theme.get();
		boolean hover = inside(mx, my, x, y, w, h);
		if (selected) {
			Redstone.lampButton(c, x, y, w, h, c.clip(label, w - 8), 1f, 0f);
		} else {
			Redstone.stone(c, x, y, w, h, hover ? t.surfaceHover : t.surfaceHigh, hover ? ColorMath.lerp(t.border, t.textDim, 0.5f) : t.border);
			String s = c.clip(label, w - 6);
			c.text(s, x + (w - c.textWidth(s)) / 2, y + (h - 8) / 2, t.text, false);
		}
		hits.add(x, y, w, h, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				action.run();
			}
		});
	}

	// --- Schritt 2: Leistung ---

	private void perf(Canvas c, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		final FpsModeChooser chooser = FpsModeChooser.get();
		String mode = chooser.current(modules);
		int gap = 6;
		int cw = (w - gap) / 2;
		int ch = 40;
		if (chooser.available()) {
			modeCard(c, x, y, cw, ch, FpsModeChooser.PRETTY, "sparkle", FpsModeChooser.PRETTY.equals(mode), mx, my, chooser);
			modeCard(c, x + cw + gap, y, cw, ch, FpsModeChooser.MAX, "bolt", FpsModeChooser.MAX.equals(mode), mx, my, chooser);
			y += ch + 8;
		}
		Performance perf = Performance.current();
		final GameOptions game = perf == null ? null : perf.game();
		int rowH = 20;
		// Bildraten-Grenze: „Unbegrenzt“ mit einem Klick (wo die Version es kann), sonst ein Hinweis.
		final GameOptions.Opt maxFps = maxFpsOption();
		int limit = game != null && maxFps != null ? game.get(maxFps) : GameOptions.NONE;
		if (limit != GameOptions.NONE) {
			boolean unlimited = limit >= 260;
			String label = I18n.tr("intro.perf.frameLimit", unlimited ? I18n.tr("intro.perf.unlimited") : String.valueOf(limit));
			Paint.textClipped(c, label, x, y + 6, w - 110, t.text, false);
			if (!unlimited) {
				String fix = I18n.tr("intro.perf.setUnlimited");
				int fw = c.textWidth(fix) + 16;
				int fx = x + w - fw;
				Paint.button(c, fx, y + 1, fw, 16, fix, true, inside(mx, my, fx, y + 1, fw, 16));
				hits.add(fx, y + 1, fw, 16, new Runnable() {
					@Override
					public void run() {
						host.playClick();
						game.set(maxFps, 260);
						game.save();
					}
				});
			} else {
				dev.theredstonee.trsclient.core.ui.Icons.draw(c, "check", x + w - 10, y + 5, 1, t.dustOn);
			}
			y += rowH;
		} else {
			y = Paint.paragraph(c, I18n.tr("intro.perf.frameLimitHint"), x, y + 2, w, 10, t.textDim) + 6;
		}
		// VSync
		final int vsync = game != null ? game.get(GameOptions.Opt.VSYNC) : GameOptions.NONE;
		if (vsync != GameOptions.NONE) {
			toggleRow(c, x, y, w, I18n.tr("intro.perf.vsync"), vsync == 1, mx, my, new Runnable() {
				@Override
				public void run() {
					game.set(GameOptions.Opt.VSYNC, vsync == 1 ? 0 : 1);
					game.save();
				}
			});
			y += rowH;
			if (vsync == 1 && y + 10 < y + h) {
				Paint.textClipped(c, I18n.tr("intro.perf.vsyncHint"), x + 8, y - 4, w - 8, t.dustOn, false);
				y += 8;
			}
		}
		// Dynamische FPS
		final Module dyn = modules.dynamicFps;
		if (host.supports(dyn)) {
			toggleRow(c, x, y, w, dyn.name(), dyn.isEnabled(), mx, my, new Runnable() {
				@Override
				public void run() {
					dyn.toggle();
				}
			});
			y += rowH;
			Paint.paragraph(c, dyn.description(), x + 8, y - 3, w - 8, 10, t.textDim);
		}
	}

	/** „Max. Bildrate“ gibt es erst mit dem Leistungs-Update im Options-Katalog – per Name, ohne feste Abhängigkeit. */
	static GameOptions.Opt maxFpsOption() {
		try {
			return GameOptions.Opt.valueOf("MAX_FPS");
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private void modeCard(Canvas c, int x, int y, int w, int h, final String mode, String icon, boolean selected, int mx, int my,
			final FpsModeChooser chooser) {
		Theme t = Theme.get();
		boolean hover = inside(mx, my, x, y, w, h);
		if (selected) Redstone.glow(c, x, y, w, h, t.glow, 0.8f);
		Redstone.stone(c, x, y, w, h, selected ? ColorMath.lerp(t.surfaceHover, t.accent, 0.12f) : (hover ? t.surfaceHover : t.surface),
				selected ? ColorMath.lerp(t.border, t.accent, 0.85f) : t.border);
		Redstone.iconWell(c, x + 6, y + (h - 14) / 2, 1, icon, selected ? t.dustOn : t.textDim, selected ? 1f : 0f);
		Paint.textClipped(c, I18n.tr("intro.perf.mode." + mode), x + 26, y + 7, w - 32, t.text, false);
		List<String> lines = Paint.wrap(c, I18n.tr("intro.perf.mode." + mode + ".desc"), w - 32);
		for (int i = 0; i < lines.size() && i < 2; i++) c.text(lines.get(i), x + 26, y + 18 + i * 10, t.textDim, false);
		hits.add(x, y, w, h, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				chooser.apply(modules, mode);
				if (!mode.equals(modules.clientState.fpsMode())) modules.clientState.setFpsMode(mode);
			}
		});
	}

	private void toggleRow(Canvas c, int x, int y, int w, String label, boolean on, int mx, int my, final Runnable toggle) {
		Theme t = Theme.get();
		Paint.textClipped(c, label, x, y + 6, w - 34, t.text, false);
		int tx = x + w - 24;
		boolean hover = inside(mx, my, tx - 4, y + 2, 30, 16);
		Paint.toggle(c, tx, y + 4, 24, 12, on ? 1f : 0f, hover);
		hits.add(tx - 4, y + 2, 30, 16, new Runnable() {
			@Override
			public void run() {
				host.playClick();
				toggle.run();
			}
		});
	}

	// --- Schritt 3: Tasten ---

	private void keys(Canvas c, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		if (trsKeys.isEmpty()) {
			Paint.paragraph(c, I18n.tr("intro.keys.unavailable"), x, y, w, 10, t.textDim);
			return;
		}
		int rowH = Math.max(18, Math.min(26, h / Math.max(1, trsKeys.size())));
		int capW = Math.min(90, Math.max(60, w / 5));
		for (int i = 0; i < trsKeys.size(); i++) {
			final KeyBind b = trsKeys.get(i);
			int ry = y + i * rowH;
			if (ry + 16 > y + h) break;
			List<KeyBind> conflicts = b.conflicts(allKeys);
			int labelW = w - capW - 30;
			Paint.textClipped(c, b.label, x, ry + 2, labelW, t.text, false);
			String status;
			int statusColor;
			if (!b.bound()) {
				status = I18n.tr("intro.keys.unbound");
				statusColor = t.textDim;
			} else if (conflicts.isEmpty()) {
				status = I18n.tr("intro.keys.free");
				statusColor = t.textDim;
			} else {
				StringBuilder sb = new StringBuilder();
				for (int k = 0; k < conflicts.size() && k < 2; k++) {
					if (k > 0) sb.append(", ");
					sb.append(conflicts.get(k).label);
				}
				if (conflicts.size() > 2) sb.append(" …");
				status = I18n.tr("intro.keys.conflict", sb.toString());
				statusColor = t.on;
			}
			if (rowH >= 22) Paint.textClipped(c, status, x + 6, ry + 12, labelW - 6, statusColor, false);
			else if (!conflicts.isEmpty()) Redstone.pip(c, x + labelW - 8, ry + 4, 5, 1f);
			int kx = x + w - capW - 22;
			boolean cap = capturing == b;
			boolean hover = inside(mx, my, kx, ry, capW, 15);
			if (cap) {
				Redstone.lamp(c, kx, ry, capW, 15, 1f, 0f);
				Paint.textCentered(c, c.clip(I18n.tr("settings.pressKey"), capW - 4), kx + capW / 2, ry + 4, t.lampTextLit, false);
			} else {
				Redstone.keycap(c, kx, ry, capW, b.bound() ? host.keyLabel(b.key()) : "—", hover);
				if (!conflicts.isEmpty()) Redstone.glow(c, kx, ry, capW, 15, t.lampGlow, 0.6f);
			}
			hits.add(kx, ry, capW, 15, new Runnable() {
				@Override
				public void run() {
					host.playClick();
					capturing = capturing == b ? null : b;
				}
			});
			int rx = x + w - 16;
			boolean rHover = inside(mx, my, rx, ry, 16, 16);
			Paint.iconButton(c, rx, ry, 16, "reset", rHover, false);
			hits.add(rx, ry, 16, 16, new Runnable() {
				@Override
				public void run() {
					host.playClick();
					b.set(b.defaultKey);
					capturing = null;
				}
			});
		}
	}

	// --- Eingaben ---

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (capturing != null) {
			if (key == UiKey.ESCAPE) {
				capturing = null;
				return true;
			}
			if (key == UiKey.BACKSPACE || key == UiKey.DELETE) {
				capturing.set(KeySetting.NONE);
				capturing = null;
				return true;
			}
			String name = host.keyNameOf(rawKey);
			if (name != null) capturing.set(name);
			capturing = null;
			return true;
		}
		if (key == UiKey.ESCAPE) {
			skip();
			return true;
		}
		if (key == UiKey.ENTER) {
			host.playClick();
			next();
			return true;
		}
		if (key == UiKey.LEFT && step > 0) {
			back();
			return true;
		}
		if (key == UiKey.RIGHT && step < STEPS - 1) {
			next();
			return true;
		}
		return false;
	}

	@Override
	public boolean pausesGame() {
		return true;
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
