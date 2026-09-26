package dev.theredstonee.trsclient.core.ui.social;

import dev.theredstonee.trsclient.core.account.FaceCache;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.Friends;
import dev.theredstonee.trsclient.core.online.FriendsView;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.social.Chat;
import dev.theredstonee.trsclient.core.social.SanctionError;
import dev.theredstonee.trsclient.core.social.Social;
import dev.theredstonee.trsclient.core.social.SocialOverlay;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.core.ui.friends.FriendsPanel;
import dev.theredstonee.trsclient.core.ui.menus.WindowUi;

import java.util.List;

/**
 * Sozial-Bildschirm im Spiel, im Redstone-Stil: Reiter <b>Chat</b> (Unterhaltungen links, Unterhaltung rechts) und
 * <b>Freunde</b> (Liste, Anfragen samt Umhang-Angeboten, Blockiert). Alles aktualisiert sich live über den
 * Konto-Stream (API.md §19); Aktionen laufen über {@link Social}.
 */
public final class SocialUi extends WindowUi implements SocialContext {
	/** Unter dieser Breite nur eine Spalte (Liste oder Unterhaltung). */
	static final int TWO_PANES_MIN_W = 330;

	private final SocialHost host;
	private final TrsOnline online;
	private final FaceCache faces;
	private final Kit kit;
	private final ConversationList list;
	private final ConversationView view;
	private final FriendsPanel friendsPanel;
	private int tab;
	private String open;
	private Dialog dialog;
	private PopupMenu popup;
	private int screenW;
	private int screenH;

	public SocialUi(SocialHost host, TrsOnline online) {
		this.host = host;
		this.online = online;
		I18n.refresh();
		this.faces = FaceCache.shared(host.userAgent());
		this.kit = new Kit(hits, new Runnable() {
			@Override
			public void run() {
				SocialUi.this.host.playClick();
			}
		});
		this.list = new ConversationList(this);
		this.view = new ConversationView(this);
		this.friendsPanel = new FriendsPanel(online, faces, new FriendsPanel.Listener() {
			@Override
			public void friendMenu(FriendsView.Friend friend, int x, int y) {
				SocialUi.this.friendMenu(friend, x, y);
			}
		});
		if (online != null) online.friends().want(Friends.Interest.FOREGROUND, false);
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

	/** Mit geöffneter Unterhaltung starten. */
	public static SocialUi at(SocialHost host, TrsOnline online, String conversationId) {
		SocialUi ui = new SocialUi(host, online);
		ui.open(conversationId);
		return ui;
	}

	// --- SocialContext ---

	@Override
	public Social social() {
		return online == null ? null : online.social();
	}

	@Override
	public TrsOnline online() {
		return online;
	}

	@Override
	public SocialHost host() {
		return host;
	}

	@Override
	public FaceCache faces() {
		return faces;
	}

	@Override
	public Kit kit() {
		return kit;
	}

	@Override
	public void dialog(Dialog d) {
		popup = null;
		dialog = d;
	}

	@Override
	public void popup(PopupMenu m) {
		popup = m == null || m.isEmpty() ? null : m;
	}

	@Override
	public void open(String conversationId) {
		open = conversationId;
		view.show(conversationId);
		if (conversationId != null) tab = 0;
	}

	@Override
	public FriendsView friends() {
		return online == null ? null : online.friends().snapshot().view;
	}

	@Override
	public void showSanctions() {
		Social s = social();
		if (s != null) dialog(new SanctionDialogs.MySanctions(s, this::dialog));
	}

	@Override
	public int screenWidth() {
		return screenW;
	}

	@Override
	public int screenHeight() {
		return screenH;
	}

	// --- Fenster ---

	@Override
	protected int[] size(int width, int height) {
		return new int[]{Math.min(width - 8, 720), Math.min(height - 8, 420)};
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
		Social s = social();
		if (s != null) s.screenClosed();
		view.release();
		host.closeScreen();
	}

	@Override
	protected int headerExtras(Canvas c, int right, int y, int mx, int my) {
		Social s = social();
		if (s == null || !s.signedIn()) return right;
		// Live-Lampe: leuchtet, solange der Echtzeit-Stream steht.
		Redstone.pip(c, right - 8, y + 5, 6, s.live() ? 1f : 0.15f);
		return right - 12;
	}

	@Override
	protected void content(Canvas c, int x, int y, int w, int h, int mx, int my, float dt) {
		Theme t = Theme.get();
		screenW = window[0] * 2 + window[2];
		screenH = window[1] * 2 + window[3];
		Social s = social();
		long now = System.currentTimeMillis();
		if (s != null) {
			if (open != null && s.store().listLoaded() && s.store().get(open) == null && s.signedIn()) open(null);
			s.screenOpen(tab == 0 ? open : null, now);
		}
		// Reiter + Aktionen
		int unread = s == null ? 0 : s.store().unreadTotal();
		Friends.Snapshot fs = online == null ? null : online.friends().snapshot();
		int incoming = fs == null ? 0 : fs.incoming();
		int tabW = Math.min(90, Math.max(50, c.textWidth(I18n.tr("social.tab.friends")) + 30));
		kit.tab(c, x, y, tabW, 16, I18n.tr("social.tab.chat"), tab == 0, mx, my, () -> tab = 0);
		if (unread > 0) Kit.badge(c, x + tabW - 1, y - 2, unread);
		kit.tab(c, x + tabW + 4, y, tabW, 16, I18n.tr("social.tab.friends"), tab == 1, mx, my, () -> tab = 1);
		if (incoming > 0) Kit.badge(c, x + tabW * 2 + 3, y - 2, incoming);
		int ix = x + w - 16;
		if (s != null && s.signedIn()) {
			kit.icon(c, ix, y, 16, "gear", false, mx, my, () -> dialog(new Dialogs.Settings(social())));
			ix -= 18;
			if (s.sanctions().supported()) {
				// Meine Strafen (leuchtet, solange eine aktiv ist).
				kit.icon(c, ix, y, 16, "shield", s.sanctions().activeCount(now) > 0, mx, my, this::showSanctions);
				ix -= 18;
			}
			if (tab == 0) {
				kit.icon(c, ix, y, 16, "search", list.searchOpen, mx, my, () -> {
					list.searchOpen = !list.searchOpen;
					list.search.setFocused(list.searchOpen);
					if (!list.searchOpen) list.search.clear();
				});
				ix -= 18;
			}
			kit.icon(c, ix, y, 16, "friends", false, mx, my, this::createGroup);
			ix -= 18;
			kit.icon(c, ix, y, 16, "plus", false, mx, my, () -> {
				tab = 1;
				friendsPanel.showTab(0);
			});
			ix -= 4;
		}
		// Meldung einer Aktion zwischen Reitern und Symbolen.
		Social.Notice n = s == null ? null : s.notice(now);
		int noteX = x + tabW * 2 + 12;
		if (n != null && ix - noteX > 30) {
			Paint.textClipped(c, I18n.tr(n.key, n.args), noteX, y + 4, ix - noteX - 4, n.error ? t.dustOn : t.text, false);
		}
		int cy = y + 20;
		int ch = h - 20;
		// Strafen (Moderation v2): Banner solange aktiv, Hinweis bei gesperrter Aktion, Fläche bei Kontosperre.
		boolean bannedView = online != null && online.status() == TrsOnline.Status.BANNED;
		if (!bannedView && s != null && s.signedIn()) {
			int banner = SanctionDialogs.banner(c, kit, s, x, cy, w, mx, my, this::dialog);
			cy += banner;
			ch -= banner;
			SanctionError hit = s.takeBlocked();
			if (hit != null && dialog == null) dialog(new SanctionDialogs.Blocked(s, hit, this::dialog));
		}
		if (bannedView) {
			SanctionDialogs.bannedPanel(c, kit, s, online, x, cy, w, ch, mx, my, this::dialog);
		} else if (tab == 1) {
			friendsPanel.draw(c, kit, x, cy, w, ch, mx, my);
		} else {
			String status = FriendsPanel.statusText(online);
			if (status != null || s == null) {
				Redstone.well(c, x, cy, w, ch, t.border);
				List<String> lines = Paint.wrap(c, status == null ? I18n.tr("friends.status.unavailable") : status, w - 20);
				int ty = cy + ch / 2 - lines.size() * 5;
				for (String l : lines) {
					Paint.textCentered(c, l, x + w / 2, ty, t.textDim, false);
					ty += 11;
				}
			} else if (w >= TWO_PANES_MIN_W) {
				int lw = Math.max(120, Math.min(190, (int) (w * 0.34f)));
				list.draw(c, x, cy, lw, ch, mx, my, open);
				view.draw(c, x + lw + 4, cy, w - lw - 4, ch, mx, my, false);
			} else if (open != null) {
				view.draw(c, x, cy, w, ch, mx, my, true);
			} else {
				list.draw(c, x, cy, w, ch, mx, my, null);
			}
		}
		// Menüs und Dialoge ganz oben.
		if (popup != null) {
			popup.draw(c, kit, screenW, screenH, mx, my, () -> popup = null);
		}
		if (dialog != null) {
			if (dialog.closed()) dialog = null;
			else dialog.draw(c, kit, screenW, screenH, mx, my);
		}
	}

	// --- Menüs ---

	private void createGroup() {
		Social s = social();
		if (s == null) return;
		FriendsView fv = friends();
		dialog(new Dialogs.Group(s, faces, fv == null ? null : fv.friends, null, conv -> open(conv.id), this::dialog));
	}

	@Override
	public void conversationMenu(final Chat.Conversation conv, int x, int y) {
		final Social s = social();
		if (s == null) return;
		PopupMenu p = new PopupMenu(x, y);
		if (conv.group) {
			FriendsView fv = friends();
			p.add("friends", I18n.tr("social.menu.manageGroup"), () -> dialog(new Dialogs.Group(s, faces, fv == null ? null
					: fv.friends, conv.id, c -> open(c.id), this::dialog)));
		}
		p.add(conv.muted ? "bell" : "mute", I18n.tr(conv.muted ? "social.menu.unmute" : "social.menu.mute"),
				() -> s.mute(conv.id, !conv.muted));
		p.add("mail", I18n.tr("social.action.markUnread"), () -> s.markUnread(conv.id, null));
		p.separator();
		if (conv.group) {
			p.danger("flag", I18n.tr("social.menu.reportGroup"), () -> dialog(new Dialogs.Report(s, "group", conv.id, null,
					I18n.tr("social.report.group", conv.title()))));
			p.danger("leave", I18n.tr("social.group.leave"), () -> dialog(new Dialogs.Confirm(I18n.tr("social.group.leaveTitle"),
					I18n.tr("social.group.leaveText", conv.title()), null, I18n.tr("social.group.leave"), true,
					() -> s.leaveGroup(conv.id))));
		} else if (conv.peer != null) {
			final Chat.User peer = conv.peer;
			if (conv.canWrite) {
				p.danger("close", I18n.tr("social.menu.removeFriend"), () -> confirmFriend(peer.uuid, peer.name, Friends.Action.REMOVE));
			}
			p.danger("lock", I18n.tr("social.menu.block"), () -> confirmFriend(peer.uuid, peer.name, Friends.Action.BLOCK));
			p.danger("flag", I18n.tr("social.menu.reportPlayer"), () -> dialog(new Dialogs.Report(s, "player", peer.uuid, conv.id,
					I18n.tr("social.report.player", peer.name))));
		}
		popup(p);
	}

	private void confirmFriend(final String uuid, final String name, final Friends.Action action) {
		boolean block = action == Friends.Action.BLOCK;
		dialog(new Dialogs.Confirm(I18n.tr(block ? "social.menu.block" : "social.menu.removeFriend"),
				I18n.tr(block ? "social.confirm.block" : "social.confirm.remove", name), null,
				I18n.tr(block ? "social.menu.block" : "social.menu.removeFriend"), true, () -> {
			if (online != null) online.friends().act(action, uuid, name);
		}));
	}

	/** Menü eines Freundes (Reiter Freunde, ≡). */
	void friendMenu(final FriendsView.Friend f, int x, int y) {
		final Social s = social();
		PopupMenu p = new PopupMenu(x, y);
		p.add("chat", I18n.tr("social.menu.message"), () -> {
			tab = 0;
			list.openDm(f.uuid);
		});
		p.separator();
		p.danger("close", I18n.tr("social.menu.removeFriend"), () -> confirmFriend(f.uuid, f.name, Friends.Action.REMOVE));
		p.danger("lock", I18n.tr("social.menu.block"), () -> confirmFriend(f.uuid, f.name, Friends.Action.BLOCK));
		if (s != null) {
			p.danger("flag", I18n.tr("social.menu.reportPlayer"), () -> {
				Chat.Conversation dm = s.store().dmWith(f.uuid);
				dialog(new Dialogs.Report(s, "player", f.uuid, dm == null ? null : dm.id, I18n.tr("social.report.player", f.name)));
			});
		}
		popup(p);
	}

	// --- Eingaben ---

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (dialog != null) {
			dialog.mouseClicked(mouseX, mouseY);
			super.mouseClicked(mouseX, mouseY, button);
			return true;
		}
		if (popup != null) {
			if (!popup.contains(mouseX, mouseY)) {
				popup = null;
				return true;
			}
			return super.mouseClicked(mouseX, mouseY, button);
		}
		friendsPanel.mouseClicked(mouseX, mouseY);
		boolean hit = super.mouseClicked(mouseX, mouseY, button);
		// Klick ins Leere nimmt der Suche den Fokus (das Eingabefeld behält ihn – es ist der Standard).
		if (!hit) list.search.setFocused(false);
		return hit;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (dialog != null) return dialog.mouseScrolled(mouseX, mouseY, amount);
		if (popup != null) return true;
		if (tab == 1) return friendsPanel.mouseScrolled(mouseX, mouseY, amount);
		return view.mouseScrolled(mouseX, mouseY, amount) || list.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (dialog != null) {
			String paste = key == UiKey.PASTE ? host.paste() : null;
			dialog.keyPressed(key, paste);
			if (dialog.closed()) dialog = null;
			return true;
		}
		if (popup != null) {
			if (key == UiKey.ESCAPE) popup = null;
			return true;
		}
		if (tab == 1) {
			if (key == UiKey.ESCAPE && friendsPanel.escape()) return true;
			if (key != UiKey.ESCAPE) return friendsPanel.keyPressed(key, key == UiKey.PASTE ? host.paste() : null);
			requestClose();
			return true;
		}
		if (list.search.focused()) {
			if (key == UiKey.ESCAPE) {
				list.search.setFocused(false);
				return true;
			}
			if (key == UiKey.PASTE) {
				String p = host.paste();
				if (p != null) for (int i = 0; i < p.length(); i++) list.search.type(p.charAt(i));
				return true;
			}
			if (key == UiKey.ENTER) {
				List<Chat.Conversation> v = list.visible();
				if (!v.isEmpty()) open(v.get(0).id);
				list.search.setFocused(false);
				return true;
			}
			return list.search.key(key);
		}
		if (view.keyPressed(key, shift)) return true;
		if (key == UiKey.ESCAPE) {
			if (open != null && screenW > 0 && window[2] - PAD * 2 < TWO_PANES_MIN_W) {
				open(null);
				return true;
			}
			requestClose();
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(char ch) {
		if (dialog != null) {
			dialog.charTyped(ch);
			return true;
		}
		if (popup != null) return true;
		if (tab == 1) return friendsPanel.charTyped(ch);
		if (list.search.focused()) return list.search.type(ch);
		return view.charTyped(ch);
	}

	// --- Selbsttest ---

	/** Reiter wählen: 0 = Chat, 1 = Freunde. */
	public void showTab(int index) {
		tab = index <= 0 ? 0 : 1;
	}

	/** Unterreiter der Freunde: 0 = Liste, 1 = Anfragen, 2 = Blockiert. */
	public void showFriendsTab(int index) {
		friendsPanel.showTab(index);
	}

	/** Die n-te Unterhaltung der Liste öffnen; false = gibt es nicht. */
	public boolean openConversation(int index) {
		List<Chat.Conversation> v = list.visible();
		if (index < 0 || index >= v.size()) return false;
		open(v.get(index).id);
		return true;
	}

	/**
	 * Die Unterhaltung mit diesem Titel öffnen (sonst die erste);
	 * false = keine da.
	 */
	public boolean openConversationNamed(String title) {
		List<Chat.Conversation> v = list.visible();
		for (Chat.Conversation c : v) {
			if (c.title().equalsIgnoreCase(title)) {
				open(c.id);
				return true;
			}
		}
		return openConversation(0);
	}

	/** Liste geladen (oder ein Hinweis statt Liste)? */
	public boolean ready() {
		Social s = social();
		if (FriendsPanel.statusText(online) != null) return online == null || online.status() != TrsOnline.Status.CONNECTING;
		return s != null && s.store().listLoaded();
	}

	/** Bildauswahl öffnen/schließen. */
	public void testPicker(boolean openPicker) {
		if (view.pickerOpen != openPicker) view.togglePicker();
	}

	/** Kontextmenü der letzten fremden Nachricht öffnen. */
	public void testContextMenu() {
		Chat.Message m = view.last(true);
		if (m == null) m = view.last(false);
		int[] a = view.area();
		if (m != null) view.messageMenu(m, a[0] + a[2] / 3, a[1] + a[3] / 3);
	}

	/** Melde-Dialog zur letzten fremden Nachricht öffnen. */
	public void testReport() {
		Social s = social();
		Chat.Message m = view.last(true);
		if (s == null || m == null) return;
		dialog(new Dialogs.Report(s, "message", m.id, m.conversationId,
				I18n.tr("social.report.message", m.sender == null ? "?" : m.sender.name)));
	}

	/** Erstes Bild groß anzeigen. */
	public void testLightbox() {
		Social s = social();
		Chat.Attachment a = view.firstImage();
		if (s != null && a != null) dialog(new Dialogs.Lightbox(s, a, null));
	}

	/** Dialog „Gruppe erstellen“ öffnen. */
	public void testGroupDialog() {
		createGroup();
	}

	/** „Meine Strafen“ öffnen. */
	public void testSanctions() {
		showSanctions();
	}

	/** Einspruch zur ersten möglichen Strafe mit diesem Text öffnen; false = keine. */
	public boolean testAppeal(String text) {
		if (!(dialog instanceof SanctionDialogs.MySanctions)) showSanctions();
		if (!(dialog instanceof SanctionDialogs.MySanctions) || !((SanctionDialogs.MySanctions) dialog).testAppeal()) return false;
		if (dialog instanceof SanctionDialogs.Appeal) ((SanctionDialogs.Appeal) dialog).testText(text);
		return true;
	}

	/** Alle Dialoge/Menüs schließen. */
	public void testCloseOverlays() {
		dialog = null;
		popup = null;
	}
}
