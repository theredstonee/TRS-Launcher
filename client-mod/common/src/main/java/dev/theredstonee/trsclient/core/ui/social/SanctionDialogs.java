package dev.theredstonee.trsclient.core.ui.social;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.social.Sanction;
import dev.theredstonee.trsclient.core.social.SanctionError;
import dev.theredstonee.trsclient.core.social.SanctionText;
import dev.theredstonee.trsclient.core.social.Sanctions;
import dev.theredstonee.trsclient.core.social.Social;
import dev.theredstonee.trsclient.core.social.Times;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;

import java.util.List;

/**
 * Strafen im Sozial-Bildschirm (Moderation v2, API.md §22): „Meine Strafen“, Einspruch, Hinweis bei einem gesperrten
 * Vorgang, Banner und die Fläche für ein gesperrtes Konto.
 */
public final class SanctionDialogs {
	private SanctionDialogs() {
	}

	/** Öffnet einen Dialog (ersetzt den offenen). */
	public interface Opener {
		void open(Dialog d);
	}

	static String icon(String kind) {
		if ("warn".equals(kind)) return "flag";
		if ("chat_mute".equals(kind)) return "mute";
		if ("social_ban".equals(kind)) return "friends";
		if ("upload_ban".equals(kind)) return "cape";
		if ("hosting_ban".equals(kind)) return "globe";
		return "lock";
	}

	/** Farbe einer Strafe: Verwarnung gelb (Lampe), sonst rot (Redstone). */
	static int tint(Sanction s) {
		Theme t = Theme.get();
		return "warn".equals(s.kind) ? t.lampOn : t.dustOn;
	}

	/**
	 * Angaben einer Strafe untereinander (Art mit Symbol, Zeitraum, Grund, Wirkung, Einspruch). Rückgabe: Höhe.
	 * {@code full} = auch Wirkung und Antwort des Teams.
	 */
	static int details(Canvas c, Sanction s, int x, int y, int w, long now, boolean full) {
		Theme t = Theme.get();
		boolean active = s.active(now);
		int cy = y;
		Icons.draw(c, icon(s.kind), x, cy, 1, active ? tint(s) : t.textDim);
		String status = I18n.tr(active ? "sanction.status.active" : s.lifted() ? "sanction.status.lifted" : "sanction.status.expired");
		int sw = c.textWidth(status);
		Paint.textClipped(c, SanctionText.kind(s.kind), x + 12, cy, w - sw - 20, active ? t.text : t.textDim, false);
		c.text(status, x + w - sw, cy, active ? tint(s) : t.textDim, false);
		cy += 11;
		cy += Dialogs.paragraph(c, SanctionText.period(s, now), x + 12, cy, w - 12, active ? t.text : t.textDim);
		if (s.startsAt > 0) {
			cy += Dialogs.paragraph(c, I18n.tr("sanction.since", Times.dateTime(s.startsAt)), x + 12, cy, w - 12, t.textDim);
		}
		cy += Dialogs.paragraph(c, I18n.tr("sanction.reasonLabel", SanctionText.reason(s)), x + 12, cy, w - 12, t.textDim);
		if (full && active) {
			cy += Dialogs.paragraph(c, SanctionText.effect(s.kind), x + 12, cy, w - 12, ColorMath.lerp(t.textDim, t.accent, 0.3f));
		}
		if (s.appeal != null) {
			int color = s.appeal.open() ? t.lampOn : "upheld".equals(s.appeal.status) ? t.textDim : t.on;
			cy += Dialogs.paragraph(c, SanctionText.appealStatus(s.appeal), x + 12, cy, w - 12, color);
			if (full && s.appeal.response != null) {
				cy += Dialogs.paragraph(c, I18n.tr("sanction.appeal.response", s.appeal.response), x + 12, cy, w - 12, t.text);
			}
		}
		return cy - y;
	}

	// --- Meine Strafen ---

	/** Liste der eigenen Strafen (aktiv + vergangen) mit „Einspruch einlegen“. */
	public static final class MySanctions extends Dialog {
		private final Social social;
		private final Opener opener;
		private int scroll;
		private int maxScroll;
		private final int[] listRect = new int[4];

		public MySanctions(Social social, Opener opener) {
			this.social = social;
			this.opener = opener;
			social.loadSanctions();
		}

		@Override
		protected int[] size(int screenW, int screenH) {
			return new int[]{Math.min(380, screenW - 20), Math.min(300, screenH - 16)};
		}

		@Override
		protected String title() {
			return I18n.tr("sanction.title");
		}

		@Override
		protected void body(Canvas c, Kit kit, int x, int y, int w, int h, int mx, int my) {
			Theme t = Theme.get();
			long now = System.currentTimeMillis();
			Sanctions all = social.sanctions();
			int footer = 22;
			int listH = h - footer;
			listRect[0] = x;
			listRect[1] = y;
			listRect[2] = w;
			listRect[3] = listH;
			Redstone.well(c, x, y, w, listH, t.border);
			c.scissor(x + 2, y + 2, x + w - 2, y + listH - 2);
			kit.hits.clip(x + 2, y + 2, w - 4, listH - 4);
			int ry = y + 5 - scroll;
			int cw = w - 14;
			List<Sanction> active = all.active();
			List<Sanction> past = all.past();
			if (!all.loaded() && active.isEmpty() && past.isEmpty()) {
				String msg = social.sanctionsError() != null ? I18n.tr(social.sanctionsError()) : I18n.tr("sanction.loading");
				ry += Dialogs.paragraph(c, msg, x + 6, ry, cw, t.textDim);
			} else if (active.isEmpty() && past.isEmpty()) {
				ry += Dialogs.paragraph(c, I18n.tr("sanction.none"), x + 6, ry, cw, t.textDim);
			} else {
				ry = section(c, kit, I18n.tr("sanction.active", active.size()), active, x + 6, ry, cw, now, mx, my);
				ry += 4;
				ry = section(c, kit, I18n.tr("sanction.past", past.size()), past, x + 6, ry, cw, now, mx, my);
			}
			kit.hits.noClip();
			c.noScissor();
			int content = ry + scroll - (y + 5);
			maxScroll = Math.max(0, content - (listH - 8));
			scroll = Math.max(0, Math.min(scroll, maxScroll));
			Kit.scrollbar(c, x + w - 4, y + 2, listH - 4, scroll, maxScroll, content);
			// Fuß: Stand + Neu laden
			int by = y + h - 18;
			String state = social.sanctionsLoading() ? I18n.tr("sanction.loading")
					: social.sanctionsError() != null ? I18n.tr(social.sanctionsError()) : I18n.tr("sanction.privacy");
			Paint.textClipped(c, state, x, by + 5, w - 110, social.sanctionsError() != null ? t.dustOn : t.textDim, false);
			int bw = Math.min(96, w / 3);
			kit.button(c, x + w - bw, by, bw, 18, I18n.tr("sanction.refresh"), false, !social.sanctionsLoading(), mx, my,
					new Runnable() {
						@Override
						public void run() {
							social.loadSanctions();
						}
					});
		}

		private int section(Canvas c, Kit kit, String label, List<Sanction> list, int x, int y, int w, long now, int mx, int my) {
			Theme t = Theme.get();
			if (list.isEmpty()) return y;
			Paint.textClipped(c, label, x, y, w, t.textDim, false);
			int ry = y + 12;
			for (final Sanction s : list) {
				int top = ry;
				int h = details(c, s, x + 4, ry + 4, w - 8, now, true);
				ry += h + 6;
				if (s.canAppeal(now) && social.sanctionAccess(now)) {
					int bw = Math.min(130, c.textWidth(I18n.tr("sanction.appeal.button")) + 24);
					kit.button(c, x + w - bw - 4, ry, bw, 16, I18n.tr("sanction.appeal.button"), true, true, mx, my, new Runnable() {
						@Override
						public void run() {
							opener.open(new Appeal(social, s, MySanctions.this, opener));
						}
					});
					ry += 20;
				}
				Paint.outline(c, x, top, w, ry - top, ColorMath.withAlpha(s.active(now) ? tint(s) : t.border, s.active(now) ? 140 : 255));
				ry += 4;
			}
			return ry;
		}

		@Override
		public boolean mouseScrolled(double mx, double my, double amount) {
			if (Kit.inside(mx, my, listRect[0], listRect[1], listRect[2], listRect[3])) {
				scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.round(amount * 14)));
			}
			return true;
		}

		/** Für den Selbsttest: Einspruch zur ersten Strafe, bei der er geht, öffnen; false = keine. */
		public boolean testAppeal() {
			long now = System.currentTimeMillis();
			for (Sanction s : social.sanctions().active()) {
				if (s.canAppeal(now)) {
					opener.open(new Appeal(social, s, this, opener));
					return true;
				}
			}
			return false;
		}
	}

	// --- Einspruch ---

	/** Einspruch gegen eine Strafe: mehrzeiliger Text (20–1000 Zeichen), Zähler, einmal je Strafe. */
	public static final class Appeal extends Dialog {
		static final int VISIBLE_LINES = 7;
		private final Social social;
		private final Sanction sanction;
		/** Dialog, zu dem Schließen zurückkehrt (oder null). */
		private final Dialog back;
		private final Opener opener;
		private final ChatInput input = new ChatInput(Sanctions.APPEAL_MAX + 50);
		private boolean sending;
		private String error;

		public Appeal(Social social, Sanction sanction, Dialog back, Opener opener) {
			this.social = social;
			this.sanction = sanction;
			this.back = back;
			this.opener = opener;
			input.setFocused(true);
		}

		@Override
		public void close() {
			super.close();
			if (back != null && opener != null && !back.closed()) opener.open(back);
		}

		@Override
		protected int[] size(int screenW, int screenH) {
			return new int[]{Math.min(340, screenW - 20), Math.min(250, screenH - 16)};
		}

		@Override
		protected String title() {
			return I18n.tr("sanction.appeal.title", SanctionText.kind(sanction.kind));
		}

		/** Für den Selbsttest. */
		public void testText(String text) {
			input.setText(text);
		}

		@Override
		protected void body(Canvas c, Kit kit, int x, int y, int w, int h, int mx, int my) {
			Theme t = Theme.get();
			long now = System.currentTimeMillis();
			int cy = y;
			cy += Dialogs.paragraph(c, I18n.tr("sanction.blocked", SanctionText.kind(sanction.kind), SanctionText.endShort(sanction, now))
					+ " · " + SanctionText.reason(sanction), x, cy, w, t.text);
			cy += 2;
			cy += Dialogs.paragraph(c, I18n.tr("sanction.appeal.intro"), x, cy, w, t.textDim);
			cy += 3;
			// Textfeld
			int fieldH = Math.max(30, Math.min(VISIBLE_LINES * ChatLayout.LINE_H + 8, y + h - 44 - cy));
			Redstone.well(c, x, cy, w, fieldH, input.focused() ? t.accent : t.border);
			final Canvas canvas = c;
			String text = input.text();
			List<int[]> lines = ChatLayout.wrap(new ChatLayout.Measure() {
				@Override
				public int width(String s) {
					return canvas.textWidth(s);
				}
			}, text, w - 12);
			int visible = Math.max(1, (fieldH - 8) / ChatLayout.LINE_H);
			int cursorLine = Math.max(0, lines.size() - 1);
			for (int i = 0; i < lines.size(); i++) {
				int[] r = lines.get(i);
				if (input.cursor() >= r[0] && input.cursor() <= r[1]) {
					cursorLine = i;
					break;
				}
			}
			int first = Math.max(0, Math.min(cursorLine - visible + 1, Math.max(0, lines.size() - visible)));
			if (cursorLine < first) first = cursorLine;
			int ty = cy + 5;
			boolean blink = input.focused() && (System.currentTimeMillis() / 500) % 2 == 0;
			if (text.isEmpty()) {
				Paint.textClipped(c, I18n.tr("sanction.appeal.hint"), x + 5, ty, w - 10, t.textDim, false);
				if (blink) c.fill(x + 5, ty - 1, x + 6, ty + 9, t.text);
			}
			for (int i = first; i < first + visible && i < lines.size(); i++) {
				int[] r = lines.get(i);
				c.text(text.substring(r[0], r[1]), x + 5, ty, t.text, false);
				if (blink && i == cursorLine && !text.isEmpty()) {
					int col = Math.max(r[0], Math.min(input.cursor(), r[1]));
					int cx = x + 5 + c.textWidth(text.substring(r[0], col));
					c.fill(cx, ty - 1, cx + 1, ty + 9, t.text);
				}
				ty += ChatLayout.LINE_H;
			}
			kit.quiet(x, cy, w, fieldH, new Runnable() {
				@Override
				public void run() {
					input.setFocused(true);
				}
			});
			cy += fieldH + 3;
			// Zähler + Hinweis
			int n = Sanctions.appealLength(text);
			String problem = Sanctions.appealProblem(text);
			String count = I18n.tr("sanction.appeal.count", n, Sanctions.APPEAL_MAX);
			Paint.textRight(c, count, x + w, cy, problem != null && n > 0 ? t.dustOn : t.textDim, false);
			String left = error != null ? I18n.tr(error)
					: "sanction.appeal.tooShort".equals(problem) ? I18n.tr(problem, Sanctions.APPEAL_MIN - n)
					: problem != null ? I18n.tr(problem, Sanctions.APPEAL_MAX) : I18n.tr("sanction.appeal.once");
			Paint.textClipped(c, left, x, cy, w - c.textWidth(count) - 8, error != null ? t.dustOn : t.textDim, false);
			// Knöpfe
			int bw = Math.min(110, (w - 6) / 2);
			int by = y + h - 18;
			boolean can = problem == null && !sending;
			kit.button(c, x + w - bw, by, bw, 18, I18n.tr(sending ? "sanction.appeal.sending" : "sanction.appeal.send"), true, can,
					mx, my, new Runnable() {
						@Override
						public void run() {
							send();
						}
					});
			kit.button(c, x + w - bw * 2 - 6, by, bw, 18, I18n.tr("social.cancel"), false, true, mx, my, new Runnable() {
				@Override
				public void run() {
					close();
				}
			});
		}

		void send() {
			if (sending || Sanctions.appealProblem(input.text()) != null) return;
			sending = true;
			error = null;
			social.appeal(sanction.id, input.text(), new Social.Done<Sanction>() {
				@Override
				public void done(Sanction value, String err) {
					sending = false;
					if (value != null) close();
					else error = err == null ? "sanction.error.generic" : err;
				}
			});
		}

		@Override
		public void mouseClicked(double mx, double my) {
			input.setFocused(false);
		}

		@Override
		public boolean keyPressed(UiKey key, String paste) {
			if (key == UiKey.ESCAPE) {
				if (input.focused() && !input.text().isEmpty()) {
					input.setFocused(false);
					return true;
				}
				close();
				return true;
			}
			if (!input.focused()) input.setFocused(true);
			error = null;
			if (key == UiKey.ENTER) {
				input.newline();
				return true;
			}
			return input.key(key, paste);
		}

		@Override
		public boolean charTyped(char ch) {
			if (!input.focused()) input.setFocused(true);
			error = null;
			input.type(ch);
			return true;
		}
	}

	// --- Gesperrter Vorgang ---

	/** Hinweis, wenn eine Strafe eine Aktion gesperrt hat: Art, Ende, Grund; Einspruch oder „Meine Strafen“. */
	public static final class Blocked extends Dialog {
		private final Social social;
		private final SanctionError error;
		private final Opener opener;

		public Blocked(Social social, SanctionError error, Opener opener) {
			this.social = social;
			this.error = error;
			this.opener = opener;
		}

		@Override
		protected int[] size(int screenW, int screenH) {
			return new int[]{Math.min(300, screenW - 20), Math.min(error.sanction != null ? 170 : 110, screenH - 16)};
		}

		@Override
		protected String title() {
			return I18n.tr("sanction.blockedTitle");
		}

		@Override
		protected void body(Canvas c, Kit kit, int x, int y, int w, int h, int mx, int my) {
			Theme t = Theme.get();
			long now = System.currentTimeMillis();
			int cy = y;
			// Aktueller Stand aus der Liste (Einspruch inzwischen eingelegt?), sonst aus dem Fehler.
			Sanction s = error.sanction == null ? null : social.sanctions().find(error.sanction.id);
			if (s == null) s = error.sanction;
			if (s != null) {
				cy += details(c, s, x, cy, w, now, true) + 4;
			} else {
				cy += Dialogs.paragraph(c, SanctionText.blocked(error, now), x, cy, w, t.text) + 2;
				cy += Dialogs.paragraph(c, SanctionText.effect(error.kind()), x, cy, w, t.textDim);
			}
			int by = y + h - 18;
			int bw = Math.min(100, (w - 12) / 3);
			kit.button(c, x + w - bw, by, bw, 18, I18n.tr("common.done"), false, true, mx, my, new Runnable() {
				@Override
				public void run() {
					close();
				}
			});
			final Sanction target = s;
			if (target != null && target.canAppeal(now) && social.sanctionAccess(now)) {
				kit.button(c, x + w - bw * 2 - 6, by, bw, 18, I18n.tr("sanction.appeal.button"), true, true, mx, my, new Runnable() {
					@Override
					public void run() {
						opener.open(new Appeal(social, target, null, opener));
					}
				});
			}
			kit.button(c, x, by, bw, 18, I18n.tr("sanction.title"), false, true, mx, my, new Runnable() {
				@Override
				public void run() {
					opener.open(new MySanctions(social, opener));
				}
			});
		}

		@Override
		public boolean keyPressed(UiKey key, String paste) {
			if (key == UiKey.ENTER) {
				close();
				return true;
			}
			return super.keyPressed(key, paste);
		}
	}

	// --- Banner und gesperrtes Konto ---

	/**
	 * Schmale Leiste, solange eine Strafe aktiv ist (Klick öffnet „Meine Strafen“). Rückgabe: belegte Höhe (0 = keine
	 * aktive Strafe).
	 */
	static int banner(Canvas c, Kit kit, final Social social, int x, int y, int w, int mx, int my, final Opener opener) {
		if (social == null) return 0;
		long now = System.currentTimeMillis();
		Sanction top = social.sanctions().mostSevere(now);
		if (top == null) return 0;
		Theme t = Theme.get();
		int color = tint(top);
		boolean hov = Kit.inside(mx, my, x, y, w, 12);
		c.fill(x, y, x + w, y + 12, ColorMath.withAlpha(color, hov ? 90 : 55));
		Icons.draw(c, "shield", x + 3, y + 2, 1, color);
		String more = I18n.tr("sanction.details");
		int mw = c.textWidth(more);
		Paint.textClipped(c, SanctionText.banner(top, social.sanctions().activeCount(now), now), x + 14, y + 2, w - mw - 22, t.text,
				false);
		c.text(more, x + w - mw - 4, y + 2, hov ? t.text : t.textDim, false);
		kit.area(x, y, w, 12, new Runnable() {
			@Override
			public void run() {
				opener.open(new MySanctions(social, opener));
			}
		});
		return 13;
	}

	/** Fläche statt Chat/Freunde, wenn die Anmeldung wegen einer Kontosperre abgelehnt wurde. */
	static void bannedPanel(Canvas c, Kit kit, final Social social, final TrsOnline online, int x, int y, int w, int h, int mx,
			int my, final Opener opener) {
		Theme t = Theme.get();
		long now = System.currentTimeMillis();
		Redstone.well(c, x, y, w, h, t.border);
		int pw = Math.min(w - 20, 320);
		int px = x + (w - pw) / 2;
		int cy = y + 12;
		Icons.draw(c, "lock", px, cy, 2, t.dustOn);
		cy += Math.max(18, Dialogs.paragraph(c, I18n.tr("sanction.banned.title"), px + 22, cy + 3, pw - 22, t.text) + 6);
		SanctionError err = social == null ? null : social.banned();
		Sanction s = err == null || err.sanction == null ? null : social.sanctions().find(err.sanction.id);
		if (s == null && err != null) s = err.sanction;
		if (s != null) {
			cy += details(c, s, px, cy, pw, now, false) + 6;
		} else {
			cy += Dialogs.paragraph(c, I18n.tr("friends.status.banned"), px, cy, pw, t.textDim) + 6;
		}
		boolean access = social != null && social.sanctionAccess(now);
		if (social != null && social.appealTokenExpired(now)) {
			cy += Dialogs.paragraph(c, I18n.tr("sanction.banned.tokenExpired"), px, cy, pw, t.textDim) + 4;
		}
		int bw = Math.min(110, (pw - 8) / 3);
		int by = Math.min(cy + 2, y + h - 22);
		int bx = px;
		final Sanction target = s;
		if (target != null && target.canAppeal(now) && access) {
			kit.button(c, bx, by, bw, 18, I18n.tr("sanction.appeal.button"), true, true, mx, my, new Runnable() {
				@Override
				public void run() {
					opener.open(new Appeal(social, target, null, opener));
				}
			});
			bx += bw + 4;
		}
		if (access) {
			kit.button(c, bx, by, bw, 18, I18n.tr("sanction.title"), false, true, mx, my, new Runnable() {
				@Override
				public void run() {
					opener.open(new MySanctions(social, opener));
				}
			});
			bx += bw + 4;
		}
		kit.button(c, bx, by, bw, 18, I18n.tr("sanction.banned.retry"), false, online != null, mx, my, new Runnable() {
			@Override
			public void run() {
				if (online != null) online.retryBannedLogin();
			}
		});
	}
}
