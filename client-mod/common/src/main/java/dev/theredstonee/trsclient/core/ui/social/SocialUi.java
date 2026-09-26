package dev.theredstonee.trsclient.core.ui.social;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.social.SocialOverlay;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.core.ui.menus.WindowUi;

/**
 * Sozial-Bildschirm (vorläufige Fassung – wird ersetzt): Reiter Chat / Freunde.
 */
public final class SocialUi extends WindowUi {
	private final SocialHost host;
	private final TrsOnline online;

	public SocialUi(SocialHost host, TrsOnline online) {
		this.host = host;
		this.online = online;
	}

	/**
	 * Bildschirm für die Schnelltaste: Antwort/Einladung → {@link QuickReplyUi}, sonst der Sozial-Bildschirm auf dem
	 * Reiter Freunde → Anfragen.
	 */
	public static UiScreen forAction(SocialHost host, TrsOnline online, SocialOverlay.QuickAction action) {
		if (action != null && action.kind != SocialOverlay.QuickAction.Kind.REQUESTS) return new QuickReplyUi(host, online, action);
		SocialUi ui = new SocialUi(host, online);
		ui.showTab(1);
		ui.showFriendsTab(1);
		return ui;
	}

	@Override
	protected String title() {
		return I18n.tr("social.title");
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
	protected void content(Canvas c, int x, int y, int w, int h, int mouseX, int mouseY, float dt) {
	}

	// --- Selbsttest ---

	/** Reiter wählen: 0 = Chat, 1 = Freunde. */
	public void showTab(int index) {
	}

	/** Unterreiter der Freunde: 0 = Liste, 1 = Anfragen, 2 = Blockiert. */
	public void showFriendsTab(int index) {
	}

	/** Die n-te Unterhaltung der Liste öffnen; false = gibt es nicht. */
	public boolean openConversation(int index) {
		return false;
	}

	/** Liste geladen (oder ein Hinweis statt Liste)? */
	public boolean ready() {
		return true;
	}

	/** Bildauswahl öffnen/schließen. */
	public void testPicker(boolean open) {
	}

	/** Kontextmenü der letzten Nachricht öffnen. */
	public void testContextMenu() {
	}

	/** Melde-Dialog zur letzten fremden Nachricht öffnen. */
	public void testReport() {
	}

	/** Erstes Bild groß anzeigen. */
	public void testLightbox() {
	}

	/** Dialog „Gruppe erstellen“ öffnen. */
	public void testGroupDialog() {
	}

	/** Alle Dialoge/Menüs schließen. */
	public void testCloseOverlays() {
	}
}
