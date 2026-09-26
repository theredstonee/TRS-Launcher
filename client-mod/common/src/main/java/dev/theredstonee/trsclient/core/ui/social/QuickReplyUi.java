package dev.theredstonee.trsclient.core.ui.social;

import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.social.SocialOverlay;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;

/**
 * Schnellantwort: kleines Eingabefeld über dem laufenden Spiel (vorläufige Fassung – wird ersetzt).
 */
public final class QuickReplyUi extends UiScreen {
	private final SocialHost host;
	private final TrsOnline online;
	private final SocialOverlay.QuickAction action;

	public QuickReplyUi(SocialHost host, TrsOnline online, SocialOverlay.QuickAction action) {
		this.host = host;
		this.online = online;
		this.action = action;
	}

	@Override
	protected void draw(Canvas c, int width, int height, int mouseX, int mouseY, float dt) {
	}

	@Override
	protected void onClosed() {
		host.closeScreen();
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (key == UiKey.ESCAPE) {
			requestClose();
			return true;
		}
		return false;
	}

	@Override
	public boolean overlay() {
		return true;
	}

	/** Selbsttest: Text eintippen. */
	public void testType(String text) {
	}
}
