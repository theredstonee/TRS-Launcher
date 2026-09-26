package dev.theredstonee.trsclient.core.ui.hosting;

import dev.theredstonee.trsclient.core.account.FaceCache;
import dev.theredstonee.trsclient.core.hosting.Hosting;
import dev.theredstonee.trsclient.core.hosting.Rooms;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.menus.WindowUi;
import dev.theredstonee.trsclient.core.ui.social.Kit;
import dev.theredstonee.trsclient.core.ui.social.SocialHost;

/**
 * „Mit Code beitreten“ (Mehrspieler-Menü) und Ziel der Einladungs-Schnelltaste: Codefeld + „Beitreten“, darunter die
 * Welten der Freunde. Zeigt den Stand des Beitritts (Anfrage wartet, verbindet direkt/über Relay) mit Abbrechen.
 */
public final class JoinUi extends WindowUi {
	private final SocialHost host;
	private final Kit kit;
	private final TextInput code = new TextInput(9);
	private final WorldsPanel worlds;
	private String codeError;

	public JoinUi(SocialHost host) {
		this.host = host;
		I18n.refresh();
		this.kit = new Kit(hits, new Runnable() {
			@Override
			public void run() {
				JoinUi.this.host.playClick();
			}
		});
		this.worlds = new WorldsPanel(FaceCache.shared(host.userAgent()));
		code.setFocused(true);
		Hosting h = Hosting.current();
		if (h != null) h.refreshFriendsRooms(true);
	}

	/** Einladung annehmen (Toast/Schnelltaste): sofort beitreten und den Stand zeigen. */
	public static JoinUi forRoom(SocialHost host, String roomId) {
		JoinUi ui = new JoinUi(host);
		ui.code.setFocused(false);
		Hosting h = Hosting.current();
		if (h != null && Rooms.validRoomId(roomId)) h.joinRoom(roomId);
		return ui;
	}

	@Override
	protected String title() {
		return I18n.tr("hosting.join.title");
	}

	@Override
	protected void playClick() {
		host.playClick();
	}

	@Override
	protected void onClosed() {
		host.closeScreen();
	}

	@Override
	protected int[] size(int width, int height) {
		return new int[] { Math.min(width - 12, 380), Math.min(height - 12, 300) };
	}

	@Override
	protected void content(Canvas c, int x, int y, int w, int h, int mx, int my, float dt) {
		Theme t = Theme.get();
		Hosting hosting = Hosting.current();
		Paint.textClipped(c, I18n.tr("hosting.join.codeLabel"), x, y + 2, w, t.textDim, false);
		int iy = y + 13;
		int bw = 80;
		kit.input(c, code, x, iy, w - bw - 6, 18, I18n.tr("hosting.join.codeHint"), null);
		boolean valid = Rooms.normalizeCode(code.text()) != null;
		kit.button(c, x + w - bw, iy, bw, 18, I18n.tr("hosting.join.button"), true, valid && hosting != null, mx, my,
				new Runnable() {
					@Override
					public void run() {
						submit();
					}
				});
		int ny = iy + 22;
		long now = System.currentTimeMillis();
		Hosting.Notice n = hosting == null ? null : hosting.notice(now);
		String line = codeError != null ? I18n.tr(codeError) : n != null ? n.text() : null;
		if (line != null) {
			Paint.textClipped(c, line, x, ny, w, codeError != null || (n != null && n.error) ? t.dustOn : t.text, false);
		}
		int ly = ny + 14;
		Paint.textClipped(c, I18n.tr("hosting.worlds.title"), x, ly, w, t.text, false);
		worlds.draw(c, kit, x, ly + 12, w, y + h - ly - 12, mx, my);
	}

	private void submit() {
		Hosting hosting = Hosting.current();
		codeError = null;
		if (hosting == null) return;
		if (!hosting.joinCode(code.text())) codeError = "hosting.error.invalid_code";
		else code.setFocused(false);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		boolean hit = super.mouseClicked(mouseX, mouseY, button);
		if (!hit) code.setFocused(false);
		return hit;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		return worlds.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (code.focused()) {
			if (key == UiKey.ESCAPE) {
				code.setFocused(false);
				return true;
			}
			if (key == UiKey.ENTER) {
				submit();
				return true;
			}
			if (key == UiKey.PASTE) {
				String p = host.paste();
				if (p != null) for (int i = 0; i < p.length() && i < 16; i++) code.type(p.charAt(i));
				codeError = null;
				return true;
			}
			codeError = null;
			return code.key(key);
		}
		return super.keyPressed(rawKey, key, shift);
	}

	@Override
	public boolean charTyped(char ch) {
		if (!code.focused()) return false;
		codeError = null;
		return code.type(Character.toUpperCase(ch));
	}

	/** Selbsttest: Code eintippen und beitreten. */
	public void testCode(String value) {
		code.setText(value);
		submit();
	}
}
