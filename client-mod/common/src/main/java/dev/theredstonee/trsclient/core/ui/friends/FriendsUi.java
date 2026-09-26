package dev.theredstonee.trsclient.core.ui.friends;

import dev.theredstonee.trsclient.core.account.FaceCache;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.menus.WindowUi;
import dev.theredstonee.trsclient.core.ui.social.Kit;

/**
 * Eigenständiger Freunde-Bildschirm (ohne Chat) – dieselbe Fläche wie der Reiter „Freunde“ im Sozial-Bildschirm
 * ({@link FriendsPanel}).
 */
public final class FriendsUi extends WindowUi {
	private final FriendsHost host;
	private final TrsOnline online;
	private final FriendsPanel panel;
	private final Kit kit;

	public FriendsUi(FriendsHost host, TrsOnline online) {
		this.host = host;
		this.online = online;
		I18n.refresh();
		this.panel = new FriendsPanel(online, FaceCache.shared(host.userAgent()), null);
		this.kit = new Kit(hits, new Runnable() {
			@Override
			public void run() {
				FriendsUi.this.host.playClick();
			}
		});
	}

	/** Für den Selbsttest: Reiter wählen. */
	public void showTab(int index) {
		panel.showTab(index);
	}

	@Override
	protected String title() {
		return I18n.tr("friends.title");
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
	protected int headerExtras(Canvas c, int right, int y, int mx, int my) {
		if (online == null || !online.online()) return right;
		int x = right - 16;
		kit.icon(c, x, y, 16, "reset", false, mx, my, new Runnable() {
			@Override
			public void run() {
				online.friends().refresh();
			}
		});
		return x - 4;
	}

	@Override
	protected void content(Canvas c, int x, int y, int w, int h, int mx, int my, float dt) {
		panel.draw(c, kit, x, y, w, h, mx, my);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		panel.mouseClicked(mouseX, mouseY);
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		return panel.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (key == UiKey.ESCAPE) {
			if (panel.escape()) return true;
			requestClose();
			return true;
		}
		return panel.keyPressed(key, null);
	}

	@Override
	public boolean charTyped(char ch) {
		return panel.charTyped(ch);
	}
}
