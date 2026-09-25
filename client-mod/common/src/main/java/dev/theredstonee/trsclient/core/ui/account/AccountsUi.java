package dev.theredstonee.trsclient.core.ui.account;

import dev.theredstonee.trsclient.core.account.AccountManager;
import dev.theredstonee.trsclient.core.account.FaceCache;
import dev.theredstonee.trsclient.core.account.GameAccount;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Anim;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.FadeCanvas;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.PixelFont;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;

import java.util.List;

/**
 * Der Kontobildschirm im Redstone-Stil: Liste mit Gesicht, Name und Herkunft, das aktive Konto mit
 * leuchtender Lampe; Klick wechselt (nur ohne Welt), Mülleimer entfernt im Spiel hinzugefügte Konten,
 * unten „Konto hinzufügen“ (mit Launcher: dort im Browser, ohne: hier im Browser oder per Code).
 * Versionsunabhängig – Minecraft kommt nur über {@link AccountsHost} und den {@link AccountManager} ins Spiel.
 */
public final class AccountsUi extends UiScreen {
	private static final int HEADER_H = 30;
	private static final int PAD = 10;
	private static final int ROW_H = 26;

	private final AccountsHost host;
	private final AccountManager manager;
	private final FaceCache faces;
	private int scroll;
	private int maxScroll;
	private final int[] listRect = new int[4];

	public AccountsUi(AccountsHost host, AccountManager manager) {
		this.host = host;
		this.manager = manager;
		this.faces = FaceCache.shared(host.userAgent());
		I18n.refresh();
		if (manager != null) manager.open();
	}

	@Override
	protected void onClosed() {
		host.closeScreen();
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	@Override
	protected void draw(Canvas raw, int width, int height, int mouseX, int mouseY, float dt) {
		Theme t = Theme.get();
		Canvas c = FadeCanvas.of(raw, alpha());
		c.fill(0, 0, width, height, t.scrim);

		int pw = Math.min(width - 16, Math.max(Math.min(300, width - 16), Math.min(400, Math.round(width * 0.6f))));
		int ph = Math.min(height - 16, Math.max(Math.min(230, height - 16), Math.min(340, Math.round(height * 0.8f))));
		int px = (width - pw) / 2;
		int py = (height - ph) / 2 + Math.round((1 - Anim.easeOut(open)) * 14);

		c.push();
		c.raise(300f);
		Redstone.window(c, px, py, pw, ph);
		header(c, px, py, pw, mouseX, mouseY);

		AccountManager.State s = manager == null ? null : manager.state();
		int x = px + PAD;
		int w = pw - PAD * 2;
		int y = py + HEADER_H + 6;
		if (s == null) {
			Paint.textClipped(c, I18n.tr("accounts.error.error"), x, y, w, t.dustOn, false);
			c.pop();
			return;
		}

		// Herkunft der Liste
		String modeKey = s.mode == AccountManager.Mode.LAUNCHER ? "accounts.mode.launcher"
				: s.mode == AccountManager.Mode.CONNECTING ? "accounts.mode.connecting" : "accounts.mode.local";
		Icons.draw(c, s.mode == AccountManager.Mode.LOCAL ? "home" : "signal", x + 1, y, 1,
				s.mode == AccountManager.Mode.LAUNCHER ? t.dustOn : t.textDim);
		Paint.textClipped(c, I18n.tr(modeKey), x + 13, y, w - 13, t.textDim, false);
		y += 13;

		int footerH = footerHeight(s);
		int listY = y;
		int listH = py + ph - PAD - footerH - listY;
		list(c, s, x, listY, w, Math.max(ROW_H, listH), mouseX, mouseY);
		footer(c, s, x, py + ph - PAD - footerH, w, footerH, mouseX, mouseY);
		c.pop();
	}

	private void header(Canvas c, int px, int py, int pw, int mx, int my) {
		Theme t = Theme.get();
		c.fill(px + 1, py + 2, px + pw - 1, py + HEADER_H, t.surfaceHigh);
		c.fill(px + 1, py + HEADER_H - 1, px + pw - 1, py + HEADER_H, t.border);
		int lx = px + PAD;
		int ly = py + (HEADER_H - PixelFont.HEIGHT * 2) / 2;
		List<int[]> rects = PixelFont.rects("TRS");
		int glow = ColorMath.withAlpha(t.glow, 40);
		for (int[] r : rects) c.fill(lx + r[0] * 2 - 1, ly + r[1] * 2 - 1, lx + r[2] * 2 + 1, ly + r[3] * 2 + 1, glow);
		int light = ColorMath.lerp(t.accent, 0xFFFFFFFF, 0.3f);
		for (int[] r : rects) c.fill(lx + r[0] * 2, ly + r[1] * 2, lx + r[2] * 2, ly + r[3] * 2, r[1] == 0 ? light : t.accent);
		int titleX = lx + PixelFont.width("TRS") * 2 + 5;
		int closeSize = 16;
		int closeX = px + pw - PAD - closeSize;
		Paint.textClipped(c, I18n.tr("accounts.title"), titleX, py + (HEADER_H - 8) / 2, closeX - titleX - 6, t.text, false);
		int closeY = py + (HEADER_H - closeSize) / 2;
		Paint.iconButton(c, closeX, closeY, closeSize, "close", inside(mx, my, closeX, closeY, closeSize, closeSize), false);
		hits.add(closeX, closeY, closeSize, closeSize, () -> {
			host.playClick();
			requestClose();
		});
	}

	private void list(Canvas c, AccountManager.State s, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		listRect[0] = x;
		listRect[1] = y;
		listRect[2] = w;
		listRect[3] = h;
		List<GameAccount> accounts = s.accounts;
		int content = accounts.size() * ROW_H;
		maxScroll = Math.max(0, content - h);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		if (accounts.isEmpty()) {
			String empty = s.task == AccountManager.Task.LOADING ? I18n.tr("accounts.loading") : I18n.tr("accounts.empty");
			Paint.textCentered(c, c.clip(empty, w), x + w / 2, y + 10, t.textDim, false);
			return;
		}
		boolean canSwitch = manager.canSwitch() && !s.busy();
		c.scissor(x - 2, y, x + w + 2, y + h);
		int ry = y - scroll;
		for (int i = 0; i < accounts.size(); i++, ry += ROW_H) {
			if (ry + ROW_H < y || ry > y + h) continue;
			final GameAccount a = accounts.get(i);
			boolean active = a.uuid.equals(s.current);
			boolean switching = s.task == AccountManager.Task.SWITCHING && a.uuid.equals(s.taskAccount);
			boolean rowHover = inside(mx, my, x, ry, w, ROW_H - 3) && inside(mx, my, x, y, w, h);
			int rh = ROW_H - 3;
			if (active) {
				Redstone.glow(c, x, ry, w, rh, t.glow, 0.35f);
				Redstone.stone(c, x, ry, w, rh, ColorMath.lerp(t.surface, t.accent, 0.1f), ColorMath.lerp(t.border, t.accent, 0.75f));
			} else {
				Redstone.stone(c, x, ry, w, rh, rowHover && canSwitch ? t.surfaceHover : t.surface, t.border);
			}
			// Gesicht in einer Mulde
			drawFace(c, a, x + 4, ry + 3, 2);
			int tx = x + 26;
			int right = x + w - 6;
			// Rechts: Lampe (aktiv) bzw. Mülleimer
			if (a.removable() && !active) {
				int bx = right - 15;
				boolean delHover = inside(mx, my, bx, ry + 3, 15, 15);
				Paint.iconButton(c, bx, ry + 3, 15, "trash", delHover, false);
				if (!s.busy()) {
					hits.add(bx, ry + 3, 15, 15, () -> {
						host.playClick();
						manager.remove(a.uuid);
					});
				}
				right = bx - 4;
			}
			String status;
			int statusColor;
			if (switching) {
				status = I18n.tr("accounts.switching");
				statusColor = t.dustOn;
			} else if (active) {
				status = I18n.tr("accounts.active");
				statusColor = t.text;
			} else if (a.locked) {
				status = I18n.tr("accounts.locked");
				statusColor = t.textDim;
			} else {
				status = rowHover && canSwitch ? I18n.tr("accounts.switch") : "";
				statusColor = t.dustOn;
			}
			if (active || switching) {
				Redstone.pip(c, right - 7, ry + 7, 7, active ? 1f : (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 120.0)));
				right -= 11;
			}
			if (!status.isEmpty()) {
				int sw = Math.min(c.textWidth(status), Math.max(0, (right - tx) / 2));
				Paint.textRight(c, c.clip(status, sw), right, ry + 8, statusColor, false);
				right -= sw + 6;
			}
			Paint.textClipped(c, a.name, tx, ry + 3, right - tx, active ? t.text : ColorMath.lerp(t.text, t.textDim, 0.3f), false);
			Paint.textClipped(c, sourceLabel(a), tx, ry + 13, right - tx, t.textDim, false);
			if (!active && canSwitch && inside(mx, my, x, y, w, h)) {
				int hitW = (a.removable() ? w - 24 : w);
				int top = Math.max(ry, y);
				int bottom = Math.min(ry + rh, y + h);
				if (bottom > top) {
					hits.add(x, top, hitW, bottom - top, () -> {
						host.playClick();
						manager.switchTo(a.uuid);
					});
				}
			}
		}
		c.noScissor();
		if (maxScroll > 0) {
			int barH = Math.max(12, h * h / Math.max(1, content));
			int barY = y + (h - barH) * scroll / Math.max(1, maxScroll);
			c.fill(x + w + 3, barY, x + w + 5, barY + barH, t.border);
		}
	}

	static String sourceLabel(GameAccount a) {
		switch (a.source) {
			case LAUNCHER:
				return I18n.tr(a.launcherDefault ? "accounts.source.default" : "accounts.source.launcher");
			case LOCAL:
				return I18n.tr("accounts.source.local");
			default:
				return I18n.tr("accounts.source.startup");
		}
	}

	/** Gesicht mit Hut-Ebene; Platzhalter mit Anfangsbuchstaben, solange es lädt. */
	private void drawFace(Canvas c, GameAccount a, int x, int y, int px) {
		Theme t = Theme.get();
		int size = 8 * px;
		Redstone.block(c, x - 1, y - 1, size + 2, size + 2, t.bevelDark);
		int[] f = faces.face(a.uuid, a.skinUrl);
		if (f == null) {
			int base = 0xFF000000 | (a.uuid.hashCode() & 0x3F3F3F) | 0x202020;
			c.fill(x, y, x + size, y + size, base);
			String initial = a.name.isEmpty() ? "?" : a.name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
			c.text(initial, x + (size - c.textWidth(initial)) / 2 + 1, y + (size - 8) / 2 + 1, 0xFFFFFFFF, true);
			return;
		}
		for (int i = 0; i < 64; i++) {
			int fx = x + (i % 8) * px;
			int fy = y + (i / 8) * px;
			c.fill(fx, fy, fx + px, fy + px, f[i]);
			int hat = f[64 + i];
			if ((hat >>> 24) >= 0x80) c.fill(fx, fy, fx + px, fy + px, hat | 0xFF000000);
		}
	}

	private int footerHeight(AccountManager.State s) {
		int h = 22; // Knöpfe
		if (s.task == AccountManager.Task.ADDING_CODE && s.userCode != null) h += 34;
		if (s.message != null || !manager.canSwitch()) h += 12;
		return h;
	}

	private void footer(Canvas c, AccountManager.State s, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		int cy = y;
		// Meldung / Hinweis
		String note = null;
		int noteColor = t.textDim;
		if (s.message != null) {
			note = I18n.tr(s.message, s.messageArgs);
			noteColor = s.error ? t.dustOn : t.text;
		} else if (!manager.canSwitch()) {
			note = I18n.tr("accounts.leaveWorld");
		}
		if (note != null) {
			Paint.textClipped(c, note, x, cy, w, noteColor, false);
			cy += 12;
		}
		// Anmeldecode
		if (s.task == AccountManager.Task.ADDING_CODE && s.userCode != null) {
			Redstone.well(c, x, cy, w, 30, t.accent);
			String code = s.userCode;
			c.push();
			c.translate(x + 8, cy + 6);
			c.scale(2f);
			c.text(c.clip(code, (w - 110) / 2), 0, 0, t.dustOn, false);
			c.pop();
			int bw = Math.min(90, w / 3);
			int bx = x + w - bw - 5;
			boolean hov = inside(mx, my, bx, cy + 7, bw, 16);
			Redstone.button(c, bx, cy + 7, bw, 16, I18n.tr("accounts.openPage"), false, hov);
			hits.add(bx, cy + 7, bw, 16, () -> {
				host.playClick();
				manager.openVerificationPage();
			});
			cy += 34;
		}
		// Knöpfe
		int by = y + h - 18;
		boolean adding = s.task == AccountManager.Task.ADDING_BROWSER || s.task == AccountManager.Task.ADDING_CODE
				|| s.task == AccountManager.Task.ADDING_LAUNCHER;
		if (adding) {
			int bw = Math.min(120, w / 2);
			boolean hov = inside(mx, my, x, by, bw, 18);
			Redstone.button(c, x, by, bw, 18, I18n.tr("common.cancel"), false, hov);
			hits.add(x, by, bw, 18, () -> {
				host.playClick();
				manager.cancel();
			});
			if (s.task == AccountManager.Task.ADDING_BROWSER) {
				String codeLabel = I18n.tr("accounts.addCode");
				int cw = Math.min(w - bw - 6, c.textWidth(codeLabel) + 16);
				int cx = x + w - cw;
				boolean chov = inside(mx, my, cx, by, cw, 18);
				Redstone.button(c, cx, by, cw, 18, codeLabel, false, chov);
				hits.add(cx, by, cw, 18, () -> {
					host.playClick();
					manager.addWithCode();
				});
			}
			return;
		}
		boolean enabled = !s.busy() && s.mode != AccountManager.Mode.CONNECTING;
		String addLabel = I18n.tr("accounts.add");
		int aw = Math.min(w, c.textWidth(addLabel) + 30);
		boolean hov = enabled && inside(mx, my, x, by, aw, 18);
		Redstone.button(c, x, by, aw, 18, "", enabled, hov);
		Icons.draw(c, "plus", x + 7, by + 5, 1, enabled ? t.lampTextLit : t.textDim);
		Paint.textClipped(c, addLabel, x + 19, by + 5, aw - 22, enabled ? Redstone.lampTextColor(1f) : t.textDim, false);
		if (enabled) {
			hits.add(x, by, aw, 18, () -> {
				host.playClick();
				manager.add();
			});
		}
		if (s.mode == AccountManager.Mode.LOCAL && enabled) {
			String codeLabel = I18n.tr("accounts.addCode");
			int cw = Math.min(w - aw - 6, c.textWidth(codeLabel) + 16);
			if (cw > 40) {
				int cx = x + w - cw;
				boolean chov = inside(mx, my, cx, by, cw, 18);
				Redstone.button(c, cx, by, cw, 18, codeLabel, false, chov);
				hits.add(cx, by, cw, 18, () -> {
					host.playClick();
					manager.addWithCode();
				});
			}
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!inside(mouseX, mouseY, listRect[0], listRect[1], listRect[2], listRect[3])) return false;
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.round(amount * ROW_H)));
		return true;
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (key == UiKey.ESCAPE) {
			AccountManager.State s = manager == null ? null : manager.state();
			if (s != null && s.busy() && s.task != AccountManager.Task.SWITCHING) {
				manager.cancel();
				return true;
			}
			requestClose();
			return true;
		}
		return false;
	}

	@Override
	public boolean pausesGame() {
		return true;
	}
}
