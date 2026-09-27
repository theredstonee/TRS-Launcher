package dev.theredstonee.trsclient.core.circuit;

import com.google.gson.JsonObject;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;

import java.util.List;

/**
 * Unterseiten der Schaltungs-Bibliothek: „Eigene Schaltung einreichen“ (Bereich markieren → Vorschau, Name,
 * Kategorie, Beschreibung, Sprache → an die TRS API) und „Meine Einreichungen“ (Status wartend/angenommen/abgelehnt
 * samt Grund). Braucht die TRS-Anmeldung.
 */
final class CircuitSubmitPage {
	static final int DESC_MAX = 300;

	private final Runnable click;
	private final Runnable closeMenu;
	private final TextInput name = new TextInput(Circuit.MAX_NAME);
	private final TextInput desc = new TextInput(DESC_MAX);
	private final IsoPreview preview = new IsoPreview();
	private Circuit.Category category = Circuit.Category.BASICS;
	private String lang;
	private int scroll;
	private int maxScroll;
	private final int[] rect = new int[4];
	private double dragX = Double.NaN;
	private CircuitCapture.Result shownCapture;

	CircuitSubmitPage(Runnable click, Runnable closeMenu) {
		this.click = click;
		this.closeMenu = closeMenu;
	}

	void reset() {
		name.setFocused(false);
		desc.setFocused(false);
		scroll = 0;
		if (lang == null) lang = I18n.code().startsWith("de") ? "de" : "en";
	}

	boolean back() {
		if (name.focused() || desc.focused()) {
			name.setFocused(false);
			desc.setFocused(false);
			return true;
		}
		return false;
	}

	// --- Einreichen ---

	void drawSubmit(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my, float dt) {
		Theme t = Theme.get();
		Circuits ctl = Circuits.get();
		CircuitSubmissions api = ctl.submissions();
		CircuitCapture.Result cap = ctl.capture();
		if (cap != shownCapture) {
			shownCapture = cap;
			preview.reset();
		}
		rect[0] = x;
		rect[1] = y;
		rect[2] = w;
		rect[3] = h;
		c.scissor(x, y, x + w, y + h);
		hits.clip(x, y, w, h);
		int yy = y - scroll;
		int lh = 10;
		boolean loggedIn = api != null && api.loggedIn();
		if (!loggedIn) yy = Paint.paragraph(c, I18n.tr("circuits.submit.login"), x, yy, w - 6, lh, 0xFFF0C050);
		if (cap == null || cap.parsed == null) {
			yy = Paint.paragraph(c, I18n.tr("circuits.submit.intro"), x, yy, w - 6, lh, t.text);
			if (cap != null && cap.error != null) {
				String err = "unsupported".equals(cap.error)
						? I18n.tr("circuits.capture.unsupported", join(cap.unsupported))
						: I18n.tr("circuits.capture." + cap.error);
				yy = Paint.paragraph(c, err, x, yy + 2, w - 6, lh, 0xFFFF8080);
			}
			yy += 4;
			yy = button(c, hits, I18n.tr("circuits.submit.select"), x, yy, w - 6, true, ctl.inWorld(), mx, my, new Runnable() {
				@Override
				public void run() {
					Circuits.get().startSelecting();
					closeMenu.run();
				}
			});
			if (!ctl.inWorld()) yy = Paint.paragraph(c, I18n.tr("circuits.noWorld"), x, yy, w - 6, lh, 0xFFF0C050);
			finish(c, hits, yy, y, h);
			return;
		}
		final Circuit parsed = cap.parsed;
		// Vorschau
		int ph = Math.min(90, Math.max(60, h / 3));
		Redstone.well(c, x, yy, w - 6, ph, t.border);
		// Scissor nie verschachteln (ab 1.20 ein Stapel, davor nicht): Seite beenden, Vorschau, Seite wieder an
		c.noScissor();
		c.scissor(x + 1, Math.max(yy + 1, y), x + w - 7, Math.max(Math.max(yy + 1, y), Math.min(yy + ph - 1, y + h)));
		preview.draw(c, parsed, false, x + 2, yy + 2, w - 10, ph - 4, dt);
		c.noScissor();
		c.scissor(x, y, x + w, y + h);
		hits.addDrag(x, Math.max(yy, y), w - 6, ph, new Hits.Drag() {
			@Override
			public void to(double mouseX, double mouseY) {
				if (!Double.isNaN(dragX)) preview.drag((float) ((mouseX - dragX) * 0.02));
				dragX = mouseX;
			}
		});
		yy += ph + 3;
		yy = Paint.paragraph(c, I18n.tr("circuits.submit.captured", parsed.sizeX, parsed.sizeY, parsed.sizeZ, parsed.blockCount()), x, yy,
				w - 6, lh, t.textDim);
		yy += 3;
		// Name
		c.text(I18n.tr("circuits.submit.name"), x, yy, t.textDim, false);
		yy += 10;
		input(c, hits, name, x, yy, w - 6, mx, my);
		yy += 20;
		// Kategorie
		c.text(I18n.tr("circuits.submit.category"), x, yy, t.textDim, false);
		yy += 10;
		CircuitTexts texts = CircuitTexts.get();
		int cx = x;
		for (final Circuit.Category cat : Circuit.Category.values()) {
			String label = texts.category(cat);
			int cw = c.textWidth(label) + 10;
			if (cx + cw > x + w - 6 && cx > x) {
				cx = x;
				yy += 16;
			}
			boolean sel = category == cat;
			boolean hover = inside(mx, my, cx, yy, cw, 14);
			Redstone.stone(c, cx, yy, cw, 14, sel ? ColorMath.lerp(t.surface, t.accent, 0.3f) : hover ? t.surfaceHover : t.surface,
					sel ? t.accent : t.border);
			c.text(label, cx + 5, yy + 3, sel ? t.text : t.textDim, false);
			hits.add(cx, yy, cw, 14, new Runnable() {
				@Override
				public void run() {
					click.run();
					category = cat;
				}
			});
			cx += cw + 3;
		}
		yy += 20;
		// Beschreibung + Sprache
		c.text(I18n.tr("circuits.submit.description"), x, yy, t.textDim, false);
		String langLabel = "de".equals(lang) ? "DE" : "EN";
		int lw = c.textWidth(langLabel) + 12;
		int lx = x + w - 6 - lw;
		boolean lh2 = inside(mx, my, lx, yy - 2, lw, 12);
		Redstone.stone(c, lx, yy - 2, lw, 12, lh2 ? t.surfaceHover : t.surface, t.accent);
		c.text(langLabel, lx + 6, yy, t.text, false);
		hits.add(lx, yy - 2, lw, 12, new Runnable() {
			@Override
			public void run() {
				click.run();
				lang = "de".equals(lang) ? "en" : "de";
			}
		});
		yy += 11;
		input(c, hits, desc, x, yy, w - 6, mx, my);
		yy += 21;
		// Knöpfe
		boolean busy = api != null && api.busy();
		boolean canSend = loggedIn && !busy && name.text().trim().length() >= 3;
		yy = button(c, hits, I18n.tr(busy ? "circuits.submit.sending" : "circuits.submit.send"), x, yy, w - 6, true, canSend, mx, my,
				new Runnable() {
					@Override
					public void run() {
						send(parsed);
					}
				});
		yy = button(c, hits, I18n.tr("circuits.submit.reselect"), x, yy, w - 6, false, ctl.inWorld(), mx, my, new Runnable() {
			@Override
			public void run() {
				Circuits.get().clearCapture();
				Circuits.get().startSelecting();
				closeMenu.run();
			}
		});
		CircuitSubmissions.Result r = api == null ? null : api.lastResult();
		if (r != null) {
			String msg = r.ok ? I18n.tr("circuits.submit.ok", r.id == null ? "?" : r.id) : errorText(r.error);
			yy = Paint.paragraph(c, msg, x, yy, w - 6, lh, r.ok ? 0xFF7CD88C : 0xFFFF8080);
		}
		yy = Paint.paragraph(c, I18n.tr("circuits.submit.rules"), x, yy + 2, w - 6, lh, t.textDim);
		finish(c, hits, yy, y, h);
	}

	private void send(Circuit parsed) {
		Circuits ctl = Circuits.get();
		CircuitSubmissions api = ctl.submissions();
		CircuitCapture.Result cap = ctl.capture();
		if (api == null || cap == null || cap.circuit == null) return;
		String n = name.text().trim();
		String d = desc.text().trim();
		JsonObject circuit = new com.google.gson.JsonParser().parse(cap.circuit.toString()).getAsJsonObject();
		circuit.addProperty("id", CircuitCapture.slug(n));
		circuit.addProperty("category", category.id);
		JsonObject texts = new JsonObject();
		JsonObject lt = new JsonObject();
		lt.addProperty("name", n);
		if (!d.isEmpty()) lt.addProperty("desc", d);
		texts.add(lang, lt);
		circuit.add("texts", texts);
		api.submitAsync(circuit, n, category.id, d, lang);
	}

	static String errorText(String code) {
		if (code == null) return I18n.tr("circuits.error.unknown", "?");
		if ("circuit_duplicate".equals(code) || "rate_limited".equals(code) || "sanctioned".equals(code)
				|| "invalid_circuit".equals(code) || "unauthorized".equals(code) || "offline".equals(code)) {
			return I18n.tr("circuits.error." + code);
		}
		return I18n.tr("circuits.error.unknown", code);
	}

	// --- Meine Einreichungen ---

	void drawMine(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		CircuitSubmissions api = Circuits.get().submissions();
		rect[0] = x;
		rect[1] = y;
		rect[2] = w;
		rect[3] = h;
		c.scissor(x, y, x + w, y + h);
		hits.clip(x, y, w, h);
		int yy = y - scroll;
		int lh = 10;
		if (api == null || !api.loggedIn()) {
			yy = Paint.paragraph(c, I18n.tr("circuits.submit.login"), x, yy, w - 6, lh, 0xFFF0C050);
			finish(c, hits, yy, y, h);
			return;
		}
		yy = button(c, hits, I18n.tr(api.mineLoading() ? "circuits.mine.loading" : "circuits.mine.refresh"), x, yy, w - 6, false,
				!api.mineLoading(), mx, my, new Runnable() {
					@Override
					public void run() {
						Circuits.get().submissions().refreshMineAsync();
					}
				});
		List<CircuitSubmissions.Submission> list = api.mine();
		if (api.mineError() != null) yy = Paint.paragraph(c, errorText(api.mineError()), x, yy, w - 6, lh, 0xFFFF8080);
		if (list == null || list.isEmpty()) {
			if (list != null) yy = Paint.paragraph(c, I18n.tr("circuits.mine.empty"), x, yy, w - 6, lh, t.textDim);
			finish(c, hits, yy, y, h);
			return;
		}
		for (CircuitSubmissions.Submission s : list) {
			String status = s.status == null ? "pending" : s.status;
			int color = "approved".equals(status) ? 0xFF7CD88C : "rejected".equals(status) ? 0xFFFF8080 : 0xFFF0C050;
			int rowH = s.reason != null && "rejected".equals(status) ? 34 : 22;
			Redstone.stone(c, x, yy, w - 6, rowH - 2, t.surface, t.border);
			Redstone.pip(c, x + 5, yy + 6, 7, "approved".equals(status) ? 1f : 0f);
			Paint.textClipped(c, s.name == null ? s.id : s.name, x + 17, yy + 3, w - 100, t.text, false);
			Paint.textRight(c, I18n.tr("circuits.mine.status." + (status.matches("pending|approved|rejected") ? status : "pending")),
					x + w - 12, yy + 3, color, false);
			if (s.createdAt != null) Paint.textClipped(c, s.createdAt.length() > 10 ? s.createdAt.substring(0, 10) : s.createdAt, x + 17,
					yy + 11, w - 30, t.textDim, false);
			if (rowH > 22) Paint.textClipped(c, I18n.tr("circuits.mine.reason", s.reason), x + 17, yy + 21, w - 30, 0xFFFF9090, false);
			yy += rowH;
		}
		finish(c, hits, yy, y, h);
	}

	// --- Hilfen ---

	private void finish(Canvas c, Hits hits, int yy, int y, int h) {
		hits.noClip();
		c.noScissor();
		maxScroll = Math.max(0, yy + scroll - (y + h) + 4);
		scroll = Math.min(scroll, maxScroll);
	}

	private void input(Canvas c, Hits hits, final TextInput input, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		Redstone.well(c, x, y, w, 16, input.focused() ? t.accent : t.border);
		String text = input.text();
		while (c.textWidth(text) > w - 10 && text.length() > 1) text = text.substring(1);
		c.text(text, x + 4, y + 4, t.text, false);
		if (input.focused() && (System.currentTimeMillis() / 500) % 2 == 0) {
			int caret = Math.min(x + 4 + c.textWidth(text), x + w - 3);
			c.fill(caret, y + 4, caret + 1, y + 12, t.dustOn);
		}
		hits.add(x, y, w, 16, new Runnable() {
			@Override
			public void run() {
				name.setFocused(input == name);
				desc.setFocused(input == desc);
			}
		});
	}

	private int button(Canvas c, Hits hits, String label, int x, int y, int w, boolean primary, boolean enabled, int mx, int my,
			final Runnable action) {
		Theme t = Theme.get();
		if (enabled) {
			Paint.button(c, x, y, w, 17, c.clip(label, w - 10), primary, inside(mx, my, x, y, w, 17));
			hits.add(x, y, w, 17, new Runnable() {
				@Override
				public void run() {
					click.run();
					action.run();
				}
			});
		} else {
			Redstone.stone(c, x, y, w, 17, t.surface, t.border);
			Paint.textCentered(c, c.clip(label, w - 10), x + w / 2, y + 5, ColorMath.withAlpha(t.textDim, 140), false);
		}
		return y + 21;
	}

	private static String join(List<String> list) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < list.size() && i < 6; i++) {
			if (i > 0) b.append(", ");
			b.append(list.get(i));
		}
		if (list.size() > 6) b.append(", …");
		return b.toString();
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	boolean mouseScrolled(double mx, double my, double amount) {
		if (!inside(mx, my, rect[0], rect[1], rect[2], rect[3])) return false;
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(amount) * 18));
		return true;
	}

	void mouseReleased() {
		dragX = Double.NaN;
	}

	boolean keyPressed(UiKey key) {
		TextInput f = name.focused() ? name : desc.focused() ? desc : null;
		if (f == null) return false;
		if (key == UiKey.ESCAPE || key == UiKey.ENTER) {
			f.setFocused(false);
			return true;
		}
		if (key == UiKey.TAB) {
			name.setFocused(f != name);
			desc.setFocused(f == name);
			return true;
		}
		return f.key(key);
	}

	boolean charTyped(char ch) {
		if (name.focused()) return name.type(ch);
		if (desc.focused()) return desc.type(ch);
		return false;
	}
}
