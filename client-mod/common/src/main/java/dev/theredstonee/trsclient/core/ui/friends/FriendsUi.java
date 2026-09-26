package dev.theredstonee.trsclient.core.ui.friends;

import dev.theredstonee.trsclient.core.account.FaceCache;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.CapeShare;
import dev.theredstonee.trsclient.core.online.Friends;
import dev.theredstonee.trsclient.core.online.FriendsView;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Faces;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.menu.NewBadge;
import dev.theredstonee.trsclient.core.ui.menus.WindowUi;

import java.util.List;

/**
 * Freunde im Spiel, im Redstone-Stil: Liste mit Minecraft-Gesicht, Online-Status (Lampe) und was gespielt wird,
 * Anfragen (annehmen/ablehnen/zurückziehen) samt Umhang-Angeboten von Freunden (annehmen/ablehnen), Freund per Name
 * hinzufügen, entfernen und blockieren (mit Rückfrage), Blockierte freigeben. Die Daten kommen aus {@link Friends}
 * (API-Thread, höflicher Takt).
 */
public final class FriendsUi extends WindowUi {
	private static final int ROW_H = 24;
	private static final int SECTION_H = 13;
	/** So lange steht die Meldung einer Aktion. */
	private static final long MESSAGE_MS = 6_000L;

	enum Tab {
		FRIENDS, REQUESTS, BLOCKED
	}

	private final FriendsHost host;
	private final TrsOnline online;
	private final FaceCache faces;
	private final TextInput input = new TextInput(36);
	private Tab tab = Tab.FRIENDS;
	private int scroll;
	private int maxScroll;
	private final int[] listRect = new int[4];
	private final int[] inputRect = new int[4];
	/** Rückfrage läuft für diese UUID (Entfernen/Blockieren). */
	private String confirmUuid;
	private Friends.Action confirmAction;
	/** Seit wann der Reiter „Anfragen“ sichtbar ist (Angebote gelten danach als gesehen). */
	private long requestsShownSince;

	public FriendsUi(FriendsHost host, TrsOnline online) {
		this.host = host;
		this.online = online;
		this.faces = FaceCache.shared(host.userAgent());
		I18n.refresh();
		if (online != null) {
			online.friends().want(Friends.Interest.FOREGROUND, false);
			online.friends().refresh();
		}
	}

	/** Für den Selbsttest: Reiter wählen. */
	public void showTab(int index) {
		tab = Tab.values()[Math.max(0, Math.min(Tab.values().length - 1, index))];
		scroll = 0;
		confirmUuid = null;
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
		iconButton(c, x, y, 16, "reset", false, mx, my, new Runnable() {
			@Override
			public void run() {
				online.friends().refresh();
			}
		});
		return x - 4;
	}

	@Override
	protected void content(Canvas c, int x, int y, int w, int h, int mx, int my, float dt) {
		Theme t = Theme.get();
		Friends.Snapshot s = online == null ? null : online.friends().snapshot();
		if (online != null) online.friends().want(Friends.Interest.FOREGROUND, tab == Tab.BLOCKED);
		FriendsView view = s == null ? null : s.view;

		// Reiter
		int tabW = Math.min(110, (w - 8) / 3);
		int onlineCount = view == null ? 0 : view.onlineCount();
		int total = view == null ? 0 : view.friends.size();
		int incoming = s == null ? 0 : s.incoming();
		String[] labels = {
				I18n.tr("friends.tab.friends", onlineCount, total),
				incoming > 0 ? I18n.tr("friends.tab.requestsCount", incoming) : I18n.tr("friends.tab.requests"),
				I18n.tr("friends.tab.blocked")};
		for (int i = 0; i < 3; i++) {
			final Tab target = Tab.values()[i];
			tab(c, x + i * (tabW + 4), y, tabW, 16, labels[i], tab == target, mx, my, new Runnable() {
				@Override
				public void run() {
					tab = target;
					scroll = 0;
					confirmUuid = null;
				}
			});
		}
		if (incoming > 0 && tab != Tab.REQUESTS) {
			Redstone.pip(c, x + tabW + 4 + tabW - 9, y + 4, 6, (float) (0.6 + 0.4 * Math.sin(System.currentTimeMillis() / 180.0)));
		}
		// Umhang-Angebote gelten als gesehen, wenn der Reiter „Anfragen“ eine Weile offen war („NEU“ fällt weg).
		if (tab == Tab.REQUESTS && s != null && s.unseenOffers > 0) {
			long shownFor = System.currentTimeMillis() - requestsShownSince;
			if (requestsShownSince == 0) requestsShownSince = System.currentTimeMillis();
			else if (shownFor > 2500) online.friends().markOffersSeen();
		} else if (tab != Tab.REQUESTS) {
			requestsShownSince = 0;
		}
		int cy = y + 22;

		String status = statusText();
		if (status != null) {
			Redstone.well(c, x, cy, w, Math.max(24, h - (cy - y)), t.border);
			List<String> lines = Paint.wrap(c, status, w - 20);
			int ty = cy + Math.max(8, (h - (cy - y)) / 2 - lines.size() * 5);
			for (String line : lines) {
				Paint.textCentered(c, line, x + w / 2, ty, t.textDim, false);
				ty += 11;
			}
			return;
		}

		// Eingabe: Freund hinzufügen bzw. blockieren
		boolean busy = s.busy != null;
		String actionLabel = tab == Tab.BLOCKED ? I18n.tr("friends.block") : I18n.tr("friends.add");
		int bw = Math.min(120, c.textWidth(actionLabel) + 28);
		int fw = w - bw - 4;
		inputRect[0] = x;
		inputRect[1] = cy;
		inputRect[2] = fw;
		inputRect[3] = 18;
		Redstone.well(c, x, cy, fw, 18, input.focused() ? t.accent : t.border);
		String shown = input.isEmpty() && !input.focused() ? I18n.tr("friends.addHint") : input.text();
		int textColor = input.isEmpty() && !input.focused() ? t.textDim : t.text;
		String clipped = c.clip(shown, fw - 12);
		c.text(clipped, x + 6, cy + 5, textColor, false);
		if (input.focused() && (System.currentTimeMillis() / 500) % 2 == 0) {
			int cursorX = x + 6 + c.textWidth(input.text().substring(0, Math.min(input.cursor(), input.text().length())));
			if (cursorX < x + fw - 4) c.fill(cursorX, cy + 4, cursorX + 1, cy + 14, t.text);
		}
		hits.add(x, cy, fw, 18, new Runnable() {
			@Override
			public void run() {
				input.setFocused(true);
			}
		});
		final boolean canSubmit = !busy && FriendsView.target(input.text()) != null;
		int bx = x + fw + 4;
		boolean hov = canSubmit && inside(mx, my, bx, cy, bw, 18);
		Redstone.button(c, bx, cy, bw, 18, "", canSubmit && tab != Tab.BLOCKED, hov);
		Icons.draw(c, tab == Tab.BLOCKED ? "lock" : "plus", bx + 6, cy + 5, 1, canSubmit ? (tab == Tab.BLOCKED ? t.text : t.lampTextLit) : t.textDim);
		Paint.textClipped(c, actionLabel, bx + 18, cy + 5, bw - 20,
				canSubmit ? (tab == Tab.BLOCKED ? t.text : Redstone.lampTextColor(1f)) : t.textDim, false);
		if (canSubmit) {
			hits.add(bx, cy, bw, 18, new Runnable() {
				@Override
				public void run() {
					host.playClick();
					submit();
				}
			});
		}
		cy += 22;

		// Meldung
		long now = System.currentTimeMillis();
		String note = null;
		int noteColor = t.textDim;
		if (s.message != null && now - s.messageAt < MESSAGE_MS) {
			note = I18n.tr(s.message, s.args);
			noteColor = s.messageError ? t.dustOn : t.text;
		} else if (s.error != null) {
			note = I18n.tr(s.error);
			noteColor = t.dustOn;
		} else if (busy || (s.loading && view == null)) {
			note = I18n.tr("friends.loading");
		}
		if (note != null) {
			Paint.textClipped(c, note, x, cy, w, noteColor, false);
			cy += 12;
		}

		int listH = y + h - cy;
		listRect[0] = x;
		listRect[1] = cy;
		listRect[2] = w;
		listRect[3] = listH;
		Redstone.well(c, x, cy, w, listH, t.border);
		int lx = x + 3;
		int lw = w - 10;
		int ly = cy + 3;
		int lh = listH - 6;
		if (view == null) {
			Paint.textCentered(c, c.clip(I18n.tr("friends.loading"), lw), x + w / 2, cy + 10, t.textDim, false);
			return;
		}
		c.scissor(lx, ly, lx + lw + 4, ly + lh);
		hits.clip(lx, ly, lw + 4, lh);
		int content;
		switch (tab) {
			case REQUESTS:
				content = requests(c, view, s, lx, ly - scroll, lw, ly, lh, mx, my);
				break;
			case BLOCKED:
				content = blocked(c, s, lx, ly - scroll, lw, ly, lh, mx, my);
				break;
			default:
				content = friends(c, view, s, lx, ly - scroll, lw, ly, lh, mx, my);
				break;
		}
		hits.noClip();
		c.noScissor();
		maxScroll = Math.max(0, content - lh);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		scrollbar(c, x + w - 4, ly, lh, scroll, maxScroll, content);
	}

	/** Hinweis statt Liste, wenn die TRS-Dienste nicht bereitstehen; null = alles gut. */
	private String statusText() {
		if (online == null) return I18n.tr("friends.status.unavailable");
		switch (online.status()) {
			case LAUNCHER_OFF:
				return I18n.tr("friends.status.launcherOff");
			case OFF:
				return I18n.tr("friends.status.moduleOff");
			case NO_ACCOUNT:
				return I18n.tr("friends.status.noAccount");
			case CONNECTING:
				return I18n.tr("friends.status.connecting");
			case RETRY:
				return I18n.tr("friends.status.retry");
			case BANNED:
				return I18n.tr("friends.status.banned");
			default:
				return null;
		}
	}

	private void submit() {
		if (online == null) return;
		String target = FriendsView.target(input.text());
		if (target == null) return;
		Friends.Action action = tab == Tab.BLOCKED ? Friends.Action.BLOCK : Friends.Action.REQUEST;
		if (online.friends().act(action, target, input.text().trim())) {
			input.clear();
			input.setFocused(false);
		}
	}

	// --- Reiter: Freunde ---

	private int friends(Canvas c, FriendsView view, Friends.Snapshot s, int x, int y, int w, int top, int h, int mx, int my) {
		Theme t = Theme.get();
		List<FriendsView.Friend> list = view.friends;
		if (list.isEmpty()) {
			Paint.textCentered(c, c.clip(I18n.tr("friends.empty"), w), x + w / 2, top + 8, t.textDim, false);
			return 20;
		}
		int ry = y;
		for (int i = 0; i < list.size(); i++, ry += ROW_H) {
			if (ry + ROW_H < top || ry > top + h) continue;
			final FriendsView.Friend f = list.get(i);
			boolean hover = inside(mx, my, x, ry, w, ROW_H - 2) && inside(mx, my, x, top, w, h);
			Redstone.stone(c, x, ry, w, ROW_H - 2, hover ? t.surfaceHover : t.surface, f.inGame() ? ColorMath.lerp(t.border, t.accent, 0.5f) : t.border);
			face(c, f.uuid, f.name, x + 4, ry + 3);
			float lit = f.inGame() ? 1f : f.online() ? 0.45f : 0f;
			Redstone.pip(c, x + 15, ry + 14, 6, lit);
			int tx = x + 26;
			int right = x + w - 4;
			if (f.uuid.equals(confirmUuid)) {
				right = confirm(c, f.uuid, f.name, x, ry, w, mx, my);
			} else {
				int ib = right - 14;
				iconButton(c, ib, ry + 4, 14, "lock", false, mx, my, new Runnable() {
					@Override
					public void run() {
						confirmUuid = f.uuid;
						confirmAction = Friends.Action.BLOCK;
					}
				});
				ib -= 17;
				iconButton(c, ib, ry + 4, 14, "trash", false, mx, my, new Runnable() {
					@Override
					public void run() {
						confirmUuid = f.uuid;
						confirmAction = Friends.Action.REMOVE;
					}
				});
				right = ib - 4;
			}
			Paint.textClipped(c, f.name, tx, ry + 3, right - tx, f.online() ? t.text : ColorMath.lerp(t.text, t.textDim, 0.4f), false);
			Paint.textClipped(c, presence(f), tx, ry + 13, right - tx, f.inGame() ? t.dustOn : t.textDim, false);
		}
		return list.size() * ROW_H;
	}

	/** „Wirklich …?“ mit Ja/Nein; Rückgabe: rechte Kante für den Namen. */
	private int confirm(Canvas c, final String uuid, final String name, int x, int ry, int w, int mx, int my) {
		Theme t = Theme.get();
		int right = x + w - 4;
		int no = right - 14;
		iconButton(c, no, ry + 4, 14, "close", false, mx, my, new Runnable() {
			@Override
			public void run() {
				confirmUuid = null;
			}
		});
		int yes = no - 17;
		final Friends.Action action = confirmAction;
		iconButton(c, yes, ry + 4, 14, "check", true, mx, my, new Runnable() {
			@Override
			public void run() {
				confirmUuid = null;
				if (online != null) online.friends().act(action, uuid, name);
			}
		});
		String q = I18n.tr(action == Friends.Action.BLOCK ? "friends.confirm.block" : "friends.confirm.remove");
		int qw = Math.min(c.textWidth(q), Math.max(0, (yes - 4 - (x + 26)) / 2));
		Paint.textRight(c, c.clip(q, qw), yes - 4, ry + 8, t.dustOn, false);
		return yes - 8 - qw;
	}

	/** Was der Freund gerade macht. */
	static String presence(FriendsView.Friend f) {
		if (!f.online()) return I18n.tr("friends.presence.offline");
		if (!f.inGame()) return I18n.tr("friends.presence.launcher");
		String game = f.version;
		if (game != null && f.loader != null && !"vanilla".equals(f.loader)) game = game + " " + loaderName(f.loader);
		if (game != null && f.server != null) return I18n.tr("friends.presence.playingServer", game, FriendsView.displayServer(f.server));
		if (game != null) return I18n.tr("friends.presence.playing", game);
		return I18n.tr("friends.presence.inGame");
	}

	static String loaderName(String loader) {
		switch (loader) {
			case "fabric":
				return "Fabric";
			case "quilt":
				return "Quilt";
			case "forge":
				return "Forge";
			case "neoforge":
				return "NeoForge";
			default:
				return "";
		}
	}

	// --- Reiter: Anfragen ---

	private int requests(Canvas c, FriendsView view, Friends.Snapshot s, int x, int y, int w, int top, int h, int mx, int my) {
		Theme t = Theme.get();
		int ry = y;
		List<CapeShare.Offer> offers = view.offers;
		if (view.incoming.isEmpty() && view.outgoing.isEmpty() && offers.isEmpty()) {
			Paint.textCentered(c, c.clip(I18n.tr("friends.requests.empty"), w), x + w / 2, top + 8, t.textDim, false);
			return 20;
		}
		if (!offers.isEmpty()) {
			Paint.textClipped(c, I18n.tr("friends.capeOffers.title", offers.size()), x + 2, ry + 2, w, t.textDim, false);
			ry += SECTION_H;
			for (final CapeShare.Offer o : offers) {
				if (ry + ROW_H >= top && ry <= top + h) {
					Redstone.stone(c, x, ry, w, ROW_H - 2, t.surface, ColorMath.lerp(t.border, t.dustOn, 0.5f));
					face(c, o.fromUuid, o.fromName, x + 4, ry + 3);
					int right = x + w - 4;
					int no = right - 14;
					iconButton(c, no, ry + 4, 14, "close", false, mx, my, new Runnable() {
						@Override
						public void run() {
							if (online != null) online.friends().declineOffer(o);
						}
					});
					int yes = no - 17;
					iconButton(c, yes, ry + 4, 14, "check", true, mx, my, new Runnable() {
						@Override
						public void run() {
							if (online != null) online.friends().acceptOffer(o);
						}
					});
					int textW = yes - 4 - x - 26;
					if (online != null && online.friends().unseen(o)) {
						int bw = NewBadge.width(c);
						NewBadge.draw(c, yes - 4 - bw, ry + 2);
						textW -= bw + 4;
					}
					Paint.textClipped(c, o.capeName, x + 26, ry + 3, textW, t.text, false);
					String from = o.reshared() ? I18n.tr("friends.capeOffers.fromVia", o.fromName, o.creatorName)
							: I18n.tr("friends.capeOffers.from", o.fromName);
					Paint.textClipped(c, from, x + 26, ry + 13, yes - 4 - x - 26, t.textDim, false);
				}
				ry += ROW_H;
			}
			if (!view.incoming.isEmpty() || !view.outgoing.isEmpty()) ry += 4;
		}
		if (!view.incoming.isEmpty()) {
			Paint.textClipped(c, I18n.tr("friends.requests.incoming", view.incoming.size()), x + 2, ry + 2, w, t.textDim, false);
			ry += SECTION_H;
			for (final FriendsView.User u : view.incoming) {
				if (ry + ROW_H >= top && ry <= top + h) {
					Redstone.stone(c, x, ry, w, ROW_H - 2, t.surface, ColorMath.lerp(t.border, t.accent, 0.5f));
					face(c, u.uuid, u.name, x + 4, ry + 3);
					int right = x + w - 4;
					int no = right - 14;
					iconButton(c, no, ry + 4, 14, "close", false, mx, my, new Runnable() {
						@Override
						public void run() {
							if (online != null) online.friends().act(Friends.Action.DECLINE, u.uuid, u.name);
						}
					});
					int yes = no - 17;
					iconButton(c, yes, ry + 4, 14, "check", true, mx, my, new Runnable() {
						@Override
						public void run() {
							if (online != null) online.friends().act(Friends.Action.ACCEPT, u.uuid, u.name);
						}
					});
					Paint.textClipped(c, u.name, x + 26, ry + 3, yes - 4 - x - 26, t.text, false);
					Paint.textClipped(c, I18n.tr("friends.requests.wants"), x + 26, ry + 13, yes - 4 - x - 26, t.textDim, false);
				}
				ry += ROW_H;
			}
			ry += 4;
		}
		if (!view.outgoing.isEmpty()) {
			Paint.textClipped(c, I18n.tr("friends.requests.outgoing", view.outgoing.size()), x + 2, ry + 2, w, t.textDim, false);
			ry += SECTION_H;
			for (final FriendsView.User u : view.outgoing) {
				if (ry + ROW_H >= top && ry <= top + h) {
					Redstone.stone(c, x, ry, w, ROW_H - 2, t.surface, t.border);
					face(c, u.uuid, u.name, x + 4, ry + 3);
					int right = x + w - 4;
					int cancel = right - 14;
					iconButton(c, cancel, ry + 4, 14, "close", false, mx, my, new Runnable() {
						@Override
						public void run() {
							if (online != null) online.friends().act(Friends.Action.CANCEL, u.uuid, u.name);
						}
					});
					Paint.textClipped(c, u.name, x + 26, ry + 3, cancel - 4 - x - 26, t.text, false);
					Paint.textClipped(c, I18n.tr("friends.requests.pending"), x + 26, ry + 13, cancel - 4 - x - 26, t.textDim, false);
				}
				ry += ROW_H;
			}
		}
		return ry - y;
	}

	// --- Reiter: Blockiert ---

	private int blocked(Canvas c, Friends.Snapshot s, int x, int y, int w, int top, int h, int mx, int my) {
		Theme t = Theme.get();
		List<FriendsView.User> list = s.blocked;
		int ry = y;
		Paint.textClipped(c, I18n.tr("friends.blocked.hint"), x + 2, ry + 2, w - 4, t.textDim, false);
		ry += SECTION_H + 2;
		if (list == null) {
			Paint.textClipped(c, I18n.tr("friends.loading"), x + 2, ry, w, t.textDim, false);
			return ry - y + 12;
		}
		if (list.isEmpty()) {
			Paint.textClipped(c, I18n.tr("friends.blocked.empty"), x + 2, ry, w, t.textDim, false);
			return ry - y + 12;
		}
		for (final FriendsView.User u : list) {
			if (ry + ROW_H >= top && ry <= top + h) {
				Redstone.stone(c, x, ry, w, ROW_H - 2, t.surface, t.border);
				face(c, u.uuid, u.name, x + 4, ry + 3);
				String label = I18n.tr("friends.unblock");
				int bw = Math.min(w / 2, c.textWidth(label) + 14);
				int bx = x + w - 4 - bw;
				button(c, bx, ry + 3, bw, 16, label, false, true, mx, my, new Runnable() {
					@Override
					public void run() {
						if (online != null) online.friends().act(Friends.Action.UNBLOCK, u.uuid, u.name);
					}
				});
				Paint.textClipped(c, u.name, x + 26, ry + 7, bx - 4 - x - 26, t.text, false);
			}
			ry += ROW_H;
		}
		return ry - y;
	}

	private void face(Canvas c, String uuid, String name, int x, int y) {
		Faces.draw(c, faces.face(uuid, null), uuid, name, x, y, 2, true);
	}

	// --- Eingaben ---

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		boolean onInput = inside(mouseX, mouseY, inputRect[0], inputRect[1], inputRect[2], inputRect[3]);
		if (!onInput) input.setFocused(false);
		return super.mouseClicked(mouseX, mouseY, button);
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
			if (confirmUuid != null) {
				confirmUuid = null;
				return true;
			}
			if (input.focused()) {
				input.setFocused(false);
				return true;
			}
			requestClose();
			return true;
		}
		if (input.focused()) {
			if (key == UiKey.ENTER) {
				submit();
				return true;
			}
			return input.key(key);
		}
		return false;
	}

	@Override
	public boolean charTyped(char ch) {
		if (!input.focused()) {
			// Tippen startet die Eingabe (Name).
			if (!TextInput.allowed(ch) || ch == ' ') return false;
			input.setFocused(true);
		}
		return input.type(ch);
	}
}
