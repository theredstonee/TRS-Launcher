package dev.theredstonee.trsclient.core.ui.menu;

import dev.theredstonee.trsclient.core.halloween.Halloween;
import dev.theredstonee.trsclient.core.halloween.HalloweenService;
import dev.theredstonee.trsclient.core.halloween.Pumpkins;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.BoolSetting;
import dev.theredstonee.trsclient.core.module.HalloweenModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.halloween.Sprites;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Seite „Halloween“ im TRS-Menü (nur während des Events): Kürbis-Suche n/13 mit dem Rätsel zum nächsten Kürbis,
 * „Süßes oder Saures“ (Eimer wackelt beim Anklopfen, dann Süßes oder Saures) und die Schalter Spuk-Treffer,
 * Geister-Spur, Jumpscares, Andere Spuk-Effekte, Grusel-Töne, Flackern &amp; Rauschen.
 */
public final class HalloweenPage {
	/** Was die Seite vom Menü braucht. */
	public interface Host {
		void click();

		void save();

		HalloweenModules modules();
	}

	/** Mondsilber, Violett (Entwurf: --sil2, --vio2, gedämpft #9a94b4 / #7d7799). */
	static final int SILVER = 0xFFF3F5FB;
	static final int VIOLET = 0xFFB9A2EA;
	static final int MUTED = 0xFF9A94B4;
	static final int FAINT = 0xFF7D7799;
	static final int CARD = 0xD91B1629;
	static final int CARD_EDGE = 0xFF3B3358;

	private final Host host;
	private final WingsChoiceCard wings = new WingsChoiceCard();
	private final Map<String, Float> anim = new HashMap<String, Float>();
	private int scroll;
	private int maxScroll;
	private final int[] area = new int[4];

	public HalloweenPage(Host host) {
		this.host = host;
	}

	/** Seite geöffnet: Stand der Suche neu holen. */
	public void opened() {
		scroll = 0;
		HalloweenService.get().refresh();
	}

	public boolean mouseScrolled(double mx, double my, double amount) {
		if (maxScroll <= 0) return false;
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(amount) * 24));
		return true;
	}

	public void draw(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my, float dt) {
		HalloweenService service = HalloweenService.get();
		service.ensure();
		area[0] = x;
		area[1] = y;
		area[2] = w;
		area[3] = h;
		c.scissor(x, y, x + w, y + h);
		hits.clip(x, y, w, h);
		int top = y - scroll;
		int cy = top + 2;

		// Kopf: Titel, Stand der Suche, Süßes oder Saures heute
		c.text(I18n.tr("halloween.page.title"), x + 2, cy, SILVER, true);
		cy += 12;
		int found = service.foundCount();
		String trickState = I18n.tr(trickOpen(service) ? "halloween.page.trickOpen" : "halloween.page.trickDone");
		Paint.textClipped(c, I18n.tr("halloween.page.status", found, service.total()) + " · " + trickState, x + 2, cy, w - 4, MUTED, false);
		cy += 14;

		cy = huntCard(c, service, x, cy, w, found);
		cy += 6;
		// Alle 13 gefunden: eine der vier Halloween-Flügel wählen (endgültig).
		int wingsEnd = wings.draw(c, hits, x, cy, w, mx, my, new Runnable() {
			@Override
			public void run() {
				host.click();
			}
		});
		if (wingsEnd > cy) cy = wingsEnd + 6;
		cy = trickCard(c, hits, service, x, cy, w, mx, my, dt);
		cy += 6;
		cy = switches(c, hits, x, cy, w, mx, my, dt);
		cy += 4;
		cy = Paint.paragraph(c, I18n.tr("halloween.page.fairPlay"), x + 2, cy, w - 6, 10, FAINT) + 4;

		maxScroll = Math.max(0, cy + scroll - (y + h));
		scroll = Math.min(scroll, maxScroll);
		hits.noClip();
		c.noScissor();
	}

	private static boolean trickOpen(HalloweenService s) {
		HalloweenService.TrickPhase p = s.trickPhase();
		return p == HalloweenService.TrickPhase.IDLE || p == HalloweenService.TrickPhase.ERROR;
	}

	// --- Kürbis-Suche ---

	private int huntCard(Canvas c, HalloweenService service, int x, int y, int w, int found) {
		List<String> riddle = new ArrayList<String>();
		String hint = service.nextHint();
		boolean all = found >= service.total();
		String text = all ? I18n.tr("halloween.page.huntDone") : (hint != null ? I18n.tr("halloween.page.riddle", hint) : I18n.tr("halloween.page.huntLoading"));
		riddle.addAll(Paint.wrap(c, text, w - 16));
		int slots = Pumpkins.TOTAL;
		int slotW = Math.max(14, Math.min(20, (w - 16) / slots));
		int rowsH = slotW + 6;
		int h = 8 + 10 + 4 + rowsH + riddle.size() * 10 + 8;
		card(c, x, y, w, h);
		c.text(I18n.tr("halloween.page.hunt"), x + 8, y + 7, SILVER, false);
		Paint.textRight(c, found + "/" + service.total(), x + w - 8, y + 7, VIOLET, false);
		int sy = y + 22;
		int sx = x + (w - slots * slotW) / 2;
		for (int i = 0; i < slots; i++) {
			int px = sx + i * slotW + (slotW - 12) / 2;
			if (i < found) {
				Sprites.draw(c, Sprites.PUMPKIN, px, sy, 1, 1f);
			} else {
				Sprites.silhouette(c, Sprites.PUMPKIN, px, sy, 1, 0x803B3358);
			}
		}
		int ty = sy + rowsH;
		for (String line : riddle) {
			c.text(line, x + 8, ty, all ? SILVER : VIOLET, false);
			ty += 10;
		}
		return y + h;
	}

	// --- Süßes oder Saures ---

	private int trickCard(Canvas c, Hits hits, HalloweenService service, int x, int y, int w, int mx, int my, float dt) {
		long now = System.currentTimeMillis();
		HalloweenService.TrickPhase phase = service.trickPhase();
		HalloweenService.TrickOutcome outcome = phase == HalloweenService.TrickPhase.DONE ? service.trickResult()
				: (phase == HalloweenService.TrickPhase.USED ? service.todaysResult() : null);
		int bucket = 3;
		int bucketSize = 12 * bucket;
		int textX = x + 14 + bucketSize + 10;
		int textW = w - (textX - x) - 8;
		List<String> lines = new ArrayList<String>();
		String headline;
		int headColor = SILVER;
		switch (phase) {
			case KNOCKING:
				headline = I18n.tr("halloween.trick.knocking");
				break;
			case DONE:
			case USED:
				if (outcome != null && outcome.sweet) {
					headline = I18n.tr("halloween.trick.sweet") + " · " + kindLabel(outcome.kind);
					lines.add(outcome.name);
					lines.addAll(Paint.wrap(c, I18n.tr("halloween.trick.keep"), textW));
				} else if (outcome != null) {
					headline = I18n.tr("halloween.trick.sour");
					headColor = 0xFFC9424A;
					lines.addAll(Paint.wrap(c, I18n.tr("halloween.trick.sourText"), textW));
				} else {
					headline = I18n.tr("halloween.trick.used");
				}
				String next = nextLabel(service.trickNext());
				if (next != null) lines.add(I18n.tr("halloween.trick.next", next));
				break;
			case ERROR:
				headline = I18n.tr("halloween.trick.title");
				lines.addAll(Paint.wrap(c, I18n.tr(service.trickError() == null ? "halloween.error.network" : service.trickError()), textW));
				headColor = SILVER;
				break;
			default:
				headline = I18n.tr("halloween.trick.title");
				lines.addAll(Paint.wrap(c, I18n.tr("halloween.trick.text"), textW));
				break;
		}
		List<String> pool = Paint.wrap(c, I18n.tr("halloween.trick.pool"), w - 16);
		boolean canKnock = phase == HalloweenService.TrickPhase.IDLE || phase == HalloweenService.TrickPhase.ERROR;
		int contentH = Math.max(bucketSize + 8, 12 + lines.size() * 10 + (canKnock ? 22 : 4));
		int h = 8 + contentH + pool.size() * 10 + 8;
		card(c, x, y, w, h);

		// Eimer: wackelt beim Anklopfen; Saures: kippt und zeigt eine Sprechblase.
		int bx = x + 10;
		int by = y + 8;
		long age = service.knockAge(now);
		int shake = 0;
		if (phase == HalloweenService.TrickPhase.KNOCKING && age >= 0) shake = (int) Math.round(Math.sin(age / 18.0) * 2);
		boolean sour = outcome != null && !outcome.sweet && (phase == HalloweenService.TrickPhase.DONE || phase == HalloweenService.TrickPhase.USED);
		c.fill(bx - 2, by - 2, bx + bucketSize + 2, by + bucketSize + 2, 0x20E08A3C);
		if (sour) {
			c.push();
			c.translate(bx + bucketSize / 2f, by + bucketSize / 2f);
			c.rotate((float) Math.toRadians(-14));
			Sprites.draw(c, Sprites.PUMPKIN, -bucketSize / 2, -bucketSize / 2, bucket, 0.8f);
			c.pop();
			String bubble = I18n.tr("halloween.trick.bubble");
			int bw = c.textWidth(bubble) + 8;
			int bubX = bx + bucketSize - bw / 2;
			c.fill(bubX, by - 4, bubX + bw, by + 8, SILVER);
			c.text(bubble, bubX + 4, by - 2, 0xFF1A1426, false);
		} else {
			Sprites.draw(c, Sprites.PUMPKIN, bx + shake, by, bucket, 1f);
		}
		c.text(c.clip(headline, textW), textX, y + 9, headColor, false);
		int ly = y + 22;
		for (int i = 0; i < lines.size(); i++) {
			boolean prize = i == 0 && outcome != null && outcome.sweet;
			c.text(c.clip(lines.get(i), textW), textX, ly, prize ? VIOLET : MUTED, false);
			ly += 10;
		}
		if (canKnock) {
			String label = I18n.tr("halloween.trick.knock");
			int kw = c.textWidth(label) + 20;
			int kx = textX;
			int ky = ly + 2;
			boolean hover = mx >= kx && mx < kx + kw && my >= ky && my < ky + 17;
			Paint.button(c, kx, ky, kw, 17, label, true, hover);
			hits.add(kx, ky, kw, 17, new Runnable() {
				@Override
				public void run() {
					host.click();
					HalloweenService.get().trick();
				}
			});
		}
		int py = y + 8 + contentH;
		for (String line : pool) {
			c.text(line, x + 8, py, FAINT, false);
			py += 10;
		}
		return y + h;
	}

	private static String kindLabel(String kind) {
		if ("cape".equals(kind)) return I18n.tr("halloween.kind.cape");
		if ("emote".equals(kind)) return I18n.tr("halloween.kind.emote");
		if ("sticker".equals(kind)) return I18n.tr("halloween.kind.sticker");
		return I18n.tr("halloween.kind.other");
	}

	/** ISO-Zeit → lokale Anzeige „31.10. 00:00“ (Format der Sprache), sonst null. */
	static String nextLabel(String iso) {
		if (iso == null || iso.isEmpty() || iso.length() > 40) return null;
		try {
			java.time.Instant at = java.time.OffsetDateTime.parse(iso).toInstant();
			java.time.ZonedDateTime local = at.atZone(java.time.ZoneId.systemDefault());
			return java.time.format.DateTimeFormatter.ofPattern(I18n.tr("halloween.trick.nextFormat"), I18n.locale()).format(local);
		} catch (RuntimeException e) {
			return null;
		}
	}

	// --- Schalter ---

	private int switches(Canvas c, Hits hits, int x, int y, int w, int mx, int my, float dt) {
		final HalloweenModules m = host.modules();
		if (m == null) return y;
		int cols = w >= 330 ? 3 : (w >= 200 ? 2 : 1);
		int gap = 6;
		int tw = (w - (cols - 1) * gap) / cols;
		int th = 34;
		String[][] rows = {
				{"hits", "halloween.switch.hits", "halloween.switch.hits.desc"},
				{"trail", "halloween.switch.trail", "halloween.switch.trail.desc"},
				{"jumpscares", "halloween.switch.jumpscares", "halloween.switch.jumpscares.desc"},
				{"others", "halloween.switch.others", "halloween.switch.others.desc"},
				{"sounds", "halloween.switch.sounds", "halloween.switch.sounds.desc"},
				{"flicker", "halloween.switch.flicker", "halloween.switch.flicker.desc"},
		};
		for (int i = 0; i < rows.length; i++) {
			final String key = rows[i][0];
			int col = i % cols;
			int row = i / cols;
			int tx = x + col * (tw + gap);
			int ty = y + row * (th + gap);
			boolean on = value(m, key);
			float p = approach(key, on ? 1f : 0f, dt);
			card(c, tx, ty, tw, th);
			int sw = 22;
			int sx = tx + tw - sw - 6;
			boolean hover = mx >= tx && mx < tx + tw && my >= ty && my < ty + th;
			Paint.toggle(c, sx, ty + 6, sw, 11, p, hover);
			Paint.textClipped(c, I18n.tr(rows[i][1]), tx + 6, ty + 7, sx - tx - 10, on ? SILVER : 0xFFC9C2E2, false);
			Paint.textClipped(c, I18n.tr(rows[i][2]), tx + 6, ty + 20, tw - 12, FAINT, false);
			hits.add(tx, ty, tw, th, new Runnable() {
				@Override
				public void run() {
					host.click();
					toggle(m, key);
				}
			});
		}
		int rowsN = (rows.length + cols - 1) / cols;
		return y + rowsN * (th + gap) - gap;
	}

	private static boolean value(HalloweenModules m, String key) {
		if ("flicker".equals(key)) return m.flicker();
		BoolSetting s = setting(m, key);
		return s != null && s.get();
	}

	private static BoolSetting setting(HalloweenModules m, String key) {
		if ("hits".equals(key)) return m.hits;
		if ("trail".equals(key)) return m.trail;
		if ("jumpscares".equals(key)) return m.jumpscares;
		if ("others".equals(key)) return m.others;
		if ("sounds".equals(key)) return m.sounds;
		return null;
	}

	private void toggle(HalloweenModules m, String key) {
		if ("flicker".equals(key)) {
			m.local.setFlicker(!m.flicker());
			m.local.markFlickerHintShown();
		} else {
			BoolSetting s = setting(m, key);
			if (s == null) return;
			s.toggle();
			// Eigene Effekte: andere sehen sie nur, wenn sie hier an sind.
			if (s == m.hits || s == m.trail) HalloweenService.get().shareEffects(m.hits.get(), m.trail.get());
		}
		host.save();
	}

	private float approach(String key, float target, float dt) {
		Float f = anim.get(key);
		float v = f == null ? target : f;
		float step = dt / 0.12f;
		v = v < target ? Math.min(target, v + step) : Math.max(target, v - step);
		anim.put(key, v);
		return v;
	}

	/** Karte im Halloween-Stil (dunkel, Kante wie {@code .mod} im Entwurf). */
	static void card(Canvas c, int x, int y, int w, int h) {
		Theme t = Theme.get();
		if (!t.light) {
			c.fill(x, y, x + w, y + h, CARD);
			c.fill(x, y, x + w, y + 1, CARD_EDGE);
			c.fill(x, y + h - 1, x + w, y + h, CARD_EDGE);
			c.fill(x, y, x + 1, y + h, CARD_EDGE);
			c.fill(x + w - 1, y, x + w, y + h, CARD_EDGE);
			// Spinnweben-Ecke oben rechts (Entwurf: „cobwebs on cards“).
			int web = ColorMath.withAlpha(0xFFCFD5E3, 0x30);
			for (int i = 0; i < 6; i++) c.fill(x + w - 2 - i, y + 1 + i, x + w - 1 - i, y + 2 + i, web);
			c.fill(x + w - 7, y + 1, x + w - 1, y + 2, web);
			c.fill(x + w - 2, y + 1, x + w - 1, y + 7, web);
		} else {
			Redstone.stone(c, x, y, w, h, t.surface, t.border);
		}
	}

	/** Nur Halloween-Zeit (Leisten-Eintrag sichtbar)? */
	public static boolean available() {
		return Halloween.active();
	}
}
