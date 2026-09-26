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
import dev.theredstonee.trsclient.core.ui.social.Kit;

import java.util.List;

/**
 * Freunde im Redstone-Stil als Fläche (für den Sozial-Bildschirm und den alten Freunde-Bildschirm): Liste mit
 * Gesicht, Online-Lampe und was gespielt wird; Anfragen (annehmen/ablehnen/zurückziehen) samt Umhang-Angeboten;
 * Blockierte. Breit: drei Spalten nebeneinander, schmal: Unterreiter. Daten aus {@link Friends}.
 */
public final class FriendsPanel {
	private static final int ROW_H = 24;
	private static final int SECTION_H = 13;
	private static final long MESSAGE_MS = 6_000L;
	/** Ab dieser Breite drei Spalten. */
	static final int COLUMNS_MIN_W = 440;

	/** Rückmeldungen an den Sozial-Bildschirm (null = eigenständig: Symbole direkt in der Zeile). */
	public interface Listener {
		/** Menü eines Freundes öffnen (≡) an Bildschirmposition. */
		void friendMenu(FriendsView.Friend friend, int x, int y);
	}

	public enum Tab {
		FRIENDS, REQUESTS, BLOCKED
	}

	private final TrsOnline online;
	private final FaceCache faces;
	private final Listener listener;
	private final TextInput input = new TextInput(36);
	private Tab tab = Tab.FRIENDS;
	private final int[] scroll = new int[3];
	private final int[] maxScroll = new int[3];
	private final int[][] listRects = new int[3][4];
	private final int[] inputRect = new int[4];
	private String confirmUuid;
	private Friends.Action confirmAction;
	private long requestsShownSince;
	private boolean columns;

	public FriendsPanel(TrsOnline online, FaceCache faces, Listener listener) {
		this.online = online;
		this.faces = faces;
		this.listener = listener;
		if (online != null) {
			online.friends().want(Friends.Interest.FOREGROUND, false);
			online.friends().refresh();
		}
	}

	public void showTab(int index) {
		tab = Tab.values()[Math.max(0, Math.min(Tab.values().length - 1, index))];
		scroll[0] = scroll[1] = scroll[2] = 0;
		confirmUuid = null;
	}

	public Tab tab() {
		return tab;
	}

	/** Hinweis statt Liste, wenn die TRS-Dienste nicht bereitstehen; null = alles gut. */
	public static String statusText(TrsOnline online) {
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

	/** Zeichnet die Fläche. */
	public void draw(Canvas c, final Kit kit, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		Friends.Snapshot s = online == null ? null : online.friends().snapshot();
		columns = w >= COLUMNS_MIN_W;
		if (online != null) online.friends().want(Friends.Interest.FOREGROUND, columns || tab == Tab.BLOCKED);
		FriendsView view = s == null ? null : s.view;
		String status = statusText(online);
		if (status != null) {
			Redstone.well(c, x, y, w, h, t.border);
			List<String> lines = Paint.wrap(c, status, w - 20);
			int ty = y + Math.max(8, h / 2 - lines.size() * 5);
			for (String line : lines) {
				Paint.textCentered(c, line, x + w / 2, ty, t.textDim, false);
				ty += 11;
			}
			return;
		}
		int cy = y;
		int incoming = s.incoming();
		if (!columns) {
			int tabW = Math.min(110, (w - 8) / 3);
			int onlineCount = view == null ? 0 : view.onlineCount();
			int total = view == null ? 0 : view.friends.size();
			String[] labels = {
					I18n.tr("friends.tab.friends", onlineCount, total),
					incoming > 0 ? I18n.tr("friends.tab.requestsCount", incoming) : I18n.tr("friends.tab.requests"),
					I18n.tr("friends.tab.blocked")};
			for (int i = 0; i < 3; i++) {
				final Tab target = Tab.values()[i];
				kit.tab(c, x + i * (tabW + 4), cy, tabW, 16, labels[i], tab == target, mx, my, new Runnable() {
					@Override
					public void run() {
						showTab(target.ordinal());
					}
				});
			}
			if (incoming > 0 && tab != Tab.REQUESTS) {
				Redstone.pip(c, x + tabW + 4 + tabW - 9, y + 4, 6, (float) (0.6 + 0.4 * Math.sin(System.currentTimeMillis() / 180.0)));
			}
			cy += 20;
		}
		// Umhang-Angebote gelten als gesehen, wenn die Anfragen eine Weile sichtbar waren.
		boolean requestsVisible = columns || tab == Tab.REQUESTS;
		if (requestsVisible && s.unseenOffers > 0) {
			long now = System.currentTimeMillis();
			if (requestsShownSince == 0) requestsShownSince = now;
			else if (now - requestsShownSince > 2500) online.friends().markOffersSeen();
		} else if (!requestsVisible) {
			requestsShownSince = 0;
		}

		// Eingabe: hinzufügen (und blockieren)
		boolean busy = s.busy != null;
		boolean blockMode = !columns && tab == Tab.BLOCKED;
		String addLabel = I18n.tr("friends.add");
		String blockLabel = I18n.tr("friends.block");
		int addW = Math.min(110, c.textWidth(addLabel) + 28);
		int blockW = columns ? Math.min(110, c.textWidth(blockLabel) + 28) : 0;
		if (blockMode) addW = Math.min(110, c.textWidth(blockLabel) + 28);
		int fw = w - addW - 4 - (blockW > 0 ? blockW + 4 : 0);
		inputRect[0] = x;
		inputRect[1] = cy;
		inputRect[2] = fw;
		inputRect[3] = 18;
		kit.input(c, input, x, cy, fw, 18, I18n.tr("friends.addHint"), null);
		final boolean canSubmit = !busy && FriendsView.target(input.text()) != null;
		int bx = x + fw + 4;
		submitButton(c, kit, bx, cy, addW, blockMode, canSubmit, mx, my);
		if (blockW > 0) submitButton(c, kit, bx + addW + 4, cy, blockW, true, canSubmit, mx, my);
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
		if (columns) {
			int colW = (w - 8) / 3;
			String[] heads = {
					I18n.tr("friends.column.list", view == null ? 0 : view.onlineCount(), view == null ? 0 : view.friends.size()),
					I18n.tr("friends.column.requests", incoming),
					I18n.tr("friends.column.blocked")};
			for (int i = 0; i < 3; i++) {
				int colX = x + i * (colW + 4);
				Paint.textCentered(c, c.clip(heads[i], colW - 4), colX + colW / 2, cy + 2, t.text, false);
				list(c, kit, Tab.values()[i], i, view, s, colX, cy + 13, colW, listH - 13, mx, my);
			}
		} else {
			list(c, kit, tab, tab.ordinal(), view, s, x, cy, w, listH, mx, my);
		}
	}

	private void submitButton(Canvas c, final Kit kit, int bx, int cy, int bw, final boolean block, boolean canSubmit, int mx,
			int my) {
		Theme t = Theme.get();
		String label = block ? I18n.tr("friends.block") : I18n.tr("friends.add");
		boolean hov = canSubmit && Kit.inside(mx, my, bx, cy, bw, 18);
		Redstone.button(c, bx, cy, bw, 18, "", canSubmit && !block, hov);
		Icons.draw(c, block ? "lock" : "plus", bx + 6, cy + 5, 1, canSubmit ? (block ? t.text : t.lampTextLit) : t.textDim);
		Paint.textClipped(c, label, bx + 18, cy + 5, bw - 20,
				canSubmit ? (block ? t.text : Redstone.lampTextColor(1f)) : t.textDim, false);
		if (canSubmit) {
			kit.area(bx, cy, bw, 18, new Runnable() {
				@Override
				public void run() {
					submit(block);
				}
			});
		}
	}

	private void list(Canvas c, Kit kit, Tab which, int slot, FriendsView view, Friends.Snapshot s, int x, int y, int w, int h,
			int mx, int my) {
		Theme t = Theme.get();
		int[] r = listRects[slot];
		r[0] = x;
		r[1] = y;
		r[2] = w;
		r[3] = h;
		Redstone.well(c, x, y, w, h, t.border);
		int lx = x + 3;
		int lw = w - 10;
		int ly = y + 3;
		int lh = h - 6;
		if (view == null) {
			Paint.textCentered(c, c.clip(I18n.tr("friends.loading"), lw), x + w / 2, y + 10, t.textDim, false);
			return;
		}
		c.scissor(lx, ly, lx + lw + 4, ly + lh);
		kit.hits.clip(lx, ly, lw + 4, lh);
		int content;
		int top = ly - scroll[slot];
		switch (which) {
			case REQUESTS:
				content = requests(c, kit, view, lx, top, lw, ly, lh, mx, my);
				break;
			case BLOCKED:
				content = blocked(c, kit, s, lx, top, lw, ly, lh, mx, my);
				break;
			default:
				content = friends(c, kit, view, lx, top, lw, ly, lh, mx, my);
				break;
		}
		kit.hits.noClip();
		c.noScissor();
		maxScroll[slot] = Math.max(0, content - lh);
		scroll[slot] = Math.max(0, Math.min(scroll[slot], maxScroll[slot]));
		Kit.scrollbar(c, x + w - 4, ly, lh, scroll[slot], maxScroll[slot], content);
	}

	private void submit(boolean block) {
		if (online == null) return;
		String target = FriendsView.target(input.text());
		if (target == null) return;
		Friends.Action action = block ? Friends.Action.BLOCK : Friends.Action.REQUEST;
		if (online.friends().act(action, target, input.text().trim())) {
			input.clear();
			input.setFocused(false);
		}
	}

	// --- Freunde ---

	private int friends(Canvas c, Kit kit, FriendsView view, int x, int y, int w, int top, int h, int mx, int my) {
		Theme t = Theme.get();
		List<FriendsView.Friend> list = view.friends;
		if (list.isEmpty()) {
			List<String> lines = Paint.wrap(c, I18n.tr("friends.empty"), w - 8);
			int ty = top + 8;
			for (String l : lines) {
				Paint.textCentered(c, l, x + w / 2, ty, t.textDim, false);
				ty += 10;
			}
			return 20 + lines.size() * 10;
		}
		int ry = y;
		for (int i = 0; i < list.size(); i++, ry += ROW_H) {
			if (ry + ROW_H < top || ry > top + h) continue;
			final FriendsView.Friend f = list.get(i);
			boolean hover = Kit.inside(mx, my, x, ry, w, ROW_H - 2) && Kit.inside(mx, my, x, top, w, h);
			Redstone.stone(c, x, ry, w, ROW_H - 2, hover ? t.surfaceHover : t.surface,
					f.inGame() ? ColorMath.lerp(t.border, t.accent, 0.5f) : t.border);
			face(c, f.uuid, f.name, x + 4, ry + 3);
			float lit = f.inGame() ? 1f : f.online() ? 0.45f : 0f;
			Redstone.pip(c, x + 15, ry + 14, 6, lit);
			int tx = x + 26;
			int right = x + w - 4;
			if (f.uuid.equals(confirmUuid)) {
				right = confirm(c, kit, f.uuid, f.name, x, ry, w, mx, my);
			} else if (listener != null) {
				final int menuX = right - 14;
				final int menuY = ry + 4;
				kit.icon(c, menuX, menuY, 14, "menu", false, mx, my, new Runnable() {
					@Override
					public void run() {
						listener.friendMenu(f, menuX, menuY + 14);
					}
				});
				right = menuX - 4;
			} else {
				int ib = right - 14;
				kit.icon(c, ib, ry + 4, 14, "lock", false, mx, my, new Runnable() {
					@Override
					public void run() {
						ask(f.uuid, Friends.Action.BLOCK);
					}
				});
				ib -= 17;
				kit.icon(c, ib, ry + 4, 14, "trash", false, mx, my, new Runnable() {
					@Override
					public void run() {
						ask(f.uuid, Friends.Action.REMOVE);
					}
				});
				right = ib - 4;
			}
			Paint.textClipped(c, f.name, tx, ry + 3, right - tx, f.online() ? t.text : ColorMath.lerp(t.text, t.textDim, 0.4f), false);
			Paint.textClipped(c, presence(f), tx, ry + 13, right - tx, f.inGame() ? t.dustOn : t.textDim, false);
		}
		return list.size() * ROW_H;
	}

	/** Rückfrage für Entfernen/Blockieren in der Zeile (vom Menü des Sozial-Bildschirms aus aufrufbar). */
	public void ask(String uuid, Friends.Action action) {
		confirmUuid = uuid;
		confirmAction = action;
		tab = Tab.FRIENDS;
	}

	private int confirm(Canvas c, Kit kit, final String uuid, final String name, int x, int ry, int w, int mx, int my) {
		Theme t = Theme.get();
		int right = x + w - 4;
		int no = right - 14;
		kit.icon(c, no, ry + 4, 14, "close", false, mx, my, new Runnable() {
			@Override
			public void run() {
				confirmUuid = null;
			}
		});
		int yes = no - 17;
		final Friends.Action action = confirmAction;
		kit.icon(c, yes, ry + 4, 14, "check", true, mx, my, new Runnable() {
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
	public static String presence(FriendsView.Friend f) {
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

	// --- Anfragen ---

	private int requests(Canvas c, Kit kit, FriendsView view, int x, int y, int w, int top, int h, int mx, int my) {
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
					kit.icon(c, no, ry + 4, 14, "close", false, mx, my, new Runnable() {
						@Override
						public void run() {
							if (online != null) online.friends().declineOffer(o);
						}
					});
					int yes = no - 17;
					kit.icon(c, yes, ry + 4, 14, "check", true, mx, my, new Runnable() {
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
					kit.icon(c, no, ry + 4, 14, "close", false, mx, my, new Runnable() {
						@Override
						public void run() {
							if (online != null) online.friends().act(Friends.Action.DECLINE, u.uuid, u.name);
						}
					});
					int yes = no - 17;
					kit.icon(c, yes, ry + 4, 14, "check", true, mx, my, new Runnable() {
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
					kit.icon(c, cancel, ry + 4, 14, "close", false, mx, my, new Runnable() {
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

	// --- Blockiert ---

	private int blocked(Canvas c, Kit kit, Friends.Snapshot s, int x, int y, int w, int top, int h, int mx, int my) {
		Theme t = Theme.get();
		List<FriendsView.User> list = s.blocked;
		int ry = y;
		List<String> hint = Paint.wrap(c, I18n.tr("friends.blocked.hint"), w - 4);
		for (String l : hint) {
			Paint.textClipped(c, l, x + 2, ry + 2, w - 4, t.textDim, false);
			ry += 10;
		}
		ry += 5;
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
				kit.button(c, bx, ry + 3, bw, 16, label, false, true, mx, my, new Runnable() {
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

	/** Klick außerhalb des Feldes nimmt den Fokus. */
	public void mouseClicked(double mx, double my) {
		if (!Kit.inside(mx, my, inputRect[0], inputRect[1], inputRect[2], inputRect[3])) input.setFocused(false);
	}

	public boolean mouseScrolled(double mx, double my, double amount) {
		for (int i = 0; i < 3; i++) {
			int[] r = listRects[i];
			if ((columns || tab.ordinal() == i) && Kit.inside(mx, my, r[0], r[1], r[2], r[3])) {
				scroll[i] = Math.max(0, Math.min(maxScroll[i], scroll[i] - (int) Math.round(amount * ROW_H)));
				return true;
			}
		}
		return false;
	}

	/** Esc: erst Rückfrage, dann Eingabe; true = verbraucht. */
	public boolean escape() {
		if (confirmUuid != null) {
			confirmUuid = null;
			return true;
		}
		if (input.focused()) {
			input.setFocused(false);
			return true;
		}
		return false;
	}

	public boolean keyPressed(UiKey key, String paste) {
		if (!input.focused()) return false;
		if (key == UiKey.ENTER) {
			submit(!columns && tab == Tab.BLOCKED);
			return true;
		}
		if (key == UiKey.PASTE) {
			if (paste != null) for (int i = 0; i < paste.length(); i++) input.type(paste.charAt(i));
			return true;
		}
		return input.key(key);
	}

	public boolean charTyped(char ch) {
		if (!input.focused()) {
			if (!TextInput.allowed(ch) || ch == ' ') return false;
			input.setFocused(true);
		}
		return input.type(ch);
	}

	public boolean inputFocused() {
		return input.focused();
	}
}
