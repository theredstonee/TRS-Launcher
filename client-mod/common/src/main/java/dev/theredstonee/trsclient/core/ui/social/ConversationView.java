package dev.theredstonee.trsclient.core.ui.social;

import dev.theredstonee.trsclient.core.clips.ClipLibrary;
import dev.theredstonee.trsclient.core.clips.Thumbnails;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.FriendsView;
import dev.theredstonee.trsclient.core.social.Chat;
import dev.theredstonee.trsclient.core.social.ChatStore;
import dev.theredstonee.trsclient.core.social.SafeText;
import dev.theredstonee.trsclient.core.social.Sanction;
import dev.theredstonee.trsclient.core.social.SanctionText;
import dev.theredstonee.trsclient.core.social.Social;
import dev.theredstonee.trsclient.core.social.Times;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Faces;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Rechte Seite des Chat-Reiters: Kopfzeile (Gesicht, Name, Status, Menü), Nachrichten (Blasen, Tagestrenner,
 * Antworten, bearbeitet/gelöscht, Reaktionen, Bilder, Einladungskarten, Lesebestätigungen), „tippt …“ und das
 * Eingabefeld mit Bildern aus den Bildschirmfotos (bis 10) und „Server einladen“.
 */
final class ConversationView {
	static final int HEAD_H = 22;
	static final int MAX_COMPOSER_LINES = 4;

	private final SocialContext ctx;
	String conversationId;
	final ChatInput composer = new ChatInput(SafeText.MAX_MESSAGE);
	private Chat.Message replyTo;
	private Chat.Message editing;
	private final List<Path> images = new ArrayList<Path>();
	private Chat.Invite invite;
	/** Abstand vom unteren Ende (0 = ganz unten, neue Nachrichten bleiben sichtbar). */
	private int scroll;
	private int maxScroll;
	private final int[] area = new int[4];
	private ChatLayout.Result layout;
	private int layoutKey;
	private int layoutWidth;
	private String layoutConv;
	private String lastNewest;
	/** Bildauswahl. */
	boolean pickerOpen;
	private final List<Path> pickerSelection = new ArrayList<Path>();
	private int pickerScroll;
	private int pickerMax;
	private ThreadPoolExecutor pickerWorker;
	private ClipLibrary library;
	private Thumbnails thumbs;
	private long lastTyping;

	ConversationView(SocialContext ctx) {
		this.ctx = ctx;
	}

	/** Andere Unterhaltung: Eingaben zurücksetzen (Text bleibt je Unterhaltung nicht erhalten). */
	void show(String id) {
		if (id == null ? conversationId == null : id.equals(conversationId)) return;
		Social s = ctx.social();
		if (s != null && conversationId != null && !composer.isEmpty()) s.typing(conversationId, false);
		conversationId = id;
		composer.clear();
		composer.setFocused(id != null);
		replyTo = null;
		editing = null;
		images.clear();
		invite = null;
		scroll = 0;
		layout = null;
		lastNewest = null;
		pickerOpen = false;
	}

	/** Beim Schließen des Bildschirms: Bild-Threads und Texturen freigeben. */
	void release() {
		if (thumbs != null) thumbs.releaseAll();
		if (pickerWorker != null) pickerWorker.shutdownNow();
		thumbs = null;
		pickerWorker = null;
		library = null;
	}

	private Chat.Conversation conversation() {
		Social s = ctx.social();
		return s == null || conversationId == null ? null : s.store().get(conversationId);
	}

	// --- Zeichnen ---

	void draw(Canvas c, int x, int y, int w, int h, int mx, int my, boolean backButton) {
		Theme t = Theme.get();
		Social s = ctx.social();
		Chat.Conversation conv = conversation();
		if (s == null || conv == null) {
			Redstone.well(c, x, y, w, h, t.border);
			List<String> lines = Paint.wrap(c, I18n.tr(conversationId == null ? "social.pickConversation" : "social.loading"),
					w - 20);
			int ty = y + h / 2 - lines.size() * 5;
			for (String l : lines) {
				Paint.textCentered(c, l, x + w / 2, ty, t.textDim, false);
				ty += 10;
			}
			return;
		}
		if (thumbs != null) thumbs.frame();
		s.images().frame();
		header(c, conv, x, y, w, mx, my, backButton);
		int cy = y + HEAD_H;
		Chat.Moderation mod = s.moderation();
		long now = System.currentTimeMillis();
		if (mod.active(now)) {
			Sanction mute = s.chatMute(now);
			String text;
			if (mute != null) {
				// Moderation v2: Ende (relativ + Datum) und Grund; Klick zeigt „Meine Strafen“ (Einspruch).
				text = I18n.tr("sanction.muteBanner", SanctionText.end(mute, now)) + " – " + SanctionText.reason(mute);
				ctx.kit().area(x, cy, w, 12, ctx::showSanctions);
			} else {
				text = mod.until == 0 ? I18n.tr("social.moderation.mutedOpen") : I18n.tr("social.moderation.mutedUntil",
						Times.dateTime(mod.until));
				if (mod.reason != null) text += " – " + mod.reason;
			}
			c.fill(x, cy, x + w, cy + 12, ColorMath.withAlpha(t.dustOn, 60));
			Icons.draw(c, "lock", x + 3, cy + 2, 1, t.dustOn);
			Paint.textClipped(c, text, x + 14, cy + 2, w - 16, t.text, false);
			cy += 13;
		}
		int composerH = composerHeight(c, conv, w, mod.active(now));
		int areaH = y + h - composerH - cy;
		area[0] = x;
		area[1] = cy;
		area[2] = w;
		area[3] = areaH;
		if (pickerOpen) picker(c, x, cy, w, areaH, mx, my);
		else messages(c, s, conv, x, cy, w, areaH, mx, my, now);
		composer(c, s, conv, x, y + h - composerH, w, composerH, mx, my, mod.active(now));
	}

	private void header(Canvas c, final Chat.Conversation conv, int x, int y, int w, final int mx, final int my, boolean back) {
		Theme t = Theme.get();
		Kit kit = ctx.kit();
		c.fill(x, y, x + w, y + HEAD_H - 2, t.surfaceHigh);
		c.fill(x, y + HEAD_H - 2, x + w, y + HEAD_H - 1, t.border);
		int fx = x + 3;
		if (back) {
			kit.icon(c, fx, y + 3, 14, "back", false, mx, my, () -> ctx.open(null));
			fx += 17;
		}
		String faceId = conv.faceUuid();
		Faces.draw(c, ctx.faces().face(faceId, null), faceId, conv.title(), fx, y + 2, 2, true);
		int tx = fx + 21;
		int right = x + w - 18;
		final int menuX = x + w - 17;
		kit.icon(c, menuX, y + 3, 14, "menu", false, mx, my, () -> ctx.conversationMenu(conv, menuX, y + 18));
		if (conv.muted) {
			Icons.draw(c, "mute", right - 10, y + 6, 1, t.textDim);
			right -= 12;
		}
		Paint.textClipped(c, conv.title(), tx, y + 2, right - tx, t.text, false);
		Paint.textClipped(c, subtitle(conv), tx, y + 11, right - tx, t.textDim, false);
	}

	private String subtitle(Chat.Conversation conv) {
		Social s = ctx.social();
		List<String> typers = s == null ? new ArrayList<String>() : s.store().typers(conv.id, System.currentTimeMillis());
		if (!typers.isEmpty()) return typingText(conv, typers);
		if (conv.group) return I18n.tr("social.group.members", conv.members.size());
		FriendsView view = ctx.friends();
		if (view != null && conv.peer != null) {
			for (FriendsView.Friend f : view.friends) {
				if (f.uuid.equals(conv.peer.uuid)) return dev.theredstonee.trsclient.core.ui.friends.FriendsPanel.presence(f);
			}
		}
		if (!conv.canWrite) return I18n.tr("social.readOnly.notFriends");
		return "";
	}

	private static String typingText(Chat.Conversation conv, List<String> typers) {
		List<String> names = new ArrayList<String>();
		for (String u : typers) {
			String n = null;
			if (conv.peer != null && conv.peer.uuid.equals(u)) n = conv.peer.name;
			for (Chat.Member m : conv.members) if (m.uuid.equals(u)) n = m.name;
			names.add(n == null ? "?" : n);
		}
		if (names.size() == 1) return I18n.tr("social.typing.one", names.get(0));
		if (names.size() == 2) return I18n.tr("social.typing.two", names.get(0), names.get(1));
		return I18n.tr("social.typing.many", names.size());
	}

	// --- Nachrichten ---

	private void messages(Canvas c, final Social s, final Chat.Conversation conv, int x, int y, int w, int h, int mx, int my,
			long now) {
		Theme t = Theme.get();
		final Kit kit = ctx.kit();
		ChatStore.Thread thread = s.store().peek(conv.id);
		int innerW = w - 8;
		if (thread == null || !thread.loaded) {
			Paint.textCentered(c, I18n.tr("social.loading"), x + w / 2, y + h / 2 - 4, t.textDim, false);
			return;
		}
		// Anordnung nur neu, wenn sich etwas geändert hat.
		int key = s.generation() * 31 + (int) (now / 30_000L);
		if (layout == null || key != layoutKey || innerW != layoutWidth || !conv.id.equals(layoutConv)) {
			final Canvas mc = c;
			ChatLayout.Result next = ChatLayout.layout(new ChatLayout.Measure() {
				@Override
				public int width(String str) {
					return mc.textWidth(str);
				}
			}, thread.messages(), s.self(), conv, innerW, now, thread.hasMore);
			// Wer hochgescrollt hat, bleibt an seiner Stelle, wenn unten Neues dazukommt.
			String newest = thread.messages().isEmpty() ? null : thread.messages().get(thread.messages().size() - 1).id;
			if (layout != null && scroll > 0 && conv.id.equals(layoutConv) && newest != null && !newest.equals(lastNewest)) {
				scroll += next.height - layout.height;
			}
			lastNewest = newest;
			layout = next;
			layoutKey = key;
			layoutWidth = innerW;
			layoutConv = conv.id;
		}
		maxScroll = Math.max(0, layout.height - h + 4);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		if (thread.hasMore && scroll >= maxScroll - 30) s.loadOlder(conv.id);
		int baseY = y + h - 2 - layout.height + scroll;
		if (layout.height < h) baseY = y + h - 2 - layout.height;
		int ox = x + 4;
		c.scissor(x, y, x + w, y + h);
		kit.hits.clip(x, y, w, h);
		for (ChatLayout.Item it : layout.items) {
			int iy = baseY + it.y;
			if (iy + it.h < y || iy > y + h) continue;
			switch (it.kind) {
				case OLDER:
					Paint.textCentered(c, I18n.tr(thread.loadingOlder ? "social.loadingOlder" : "social.olderHint"), ox + innerW / 2,
							iy + 4, t.textDim, false);
					break;
				case DAY: {
					String label = it.label;
					int lw = c.textWidth(label);
					int cx = ox + innerW / 2;
					c.fill(ox + 8, iy + 8, cx - lw / 2 - 6, iy + 9, t.border);
					c.fill(cx + lw / 2 + 6, iy + 8, ox + innerW - 8, iy + 9, t.border);
					Paint.textCentered(c, label, cx, iy + 4, t.textDim, false);
					break;
				}
				case SYSTEM: {
					int ly = iy + 2;
					for (String l : it.lines) {
						Paint.textCentered(c, l, ox + innerW / 2, ly, ColorMath.lerp(t.textDim, t.accent, 0.3f), false);
						ly += ChatLayout.LINE_H;
					}
					break;
				}
				default:
					message(c, s, conv, it, ox, baseY, innerW, mx, my, y, y + h);
			}
		}
		kit.hits.noClip();
		c.noScissor();
		Kit.scrollbar(c, x + w - 3, y, h, maxScroll - scroll, maxScroll, layout.height);
		// Neue Nachrichten unten, während man oben liest: Knopf „nach unten“.
		if (scroll > 40) {
			int bx = x + w - 22;
			int by = y + h - 20;
			kit.icon(c, bx, by, 16, "next", true, mx, my, () -> scroll = 0);
		}
	}

	private void message(Canvas c, final Social s, final Chat.Conversation conv, final ChatLayout.Item it, int ox, int baseY,
			int innerW, final int mx, final int my, int clipTop, int clipBottom) {
		Theme t = Theme.get();
		final Kit kit = ctx.kit();
		final Chat.Message m = it.message;
		int top = baseY + it.y;
		int bx = ox + it.bubbleX;
		int by = baseY + it.bubbleY;
		boolean visibleMouse = my >= clipTop && my < clipBottom;
		if (it.header) {
			String time = Times.time(m.createdAt);
			if (it.own) {
				Paint.textRight(c, time, bx + it.bubbleW, top + 1, t.textDim, false);
			} else {
				String name = m.sender == null ? "?" : m.sender.name;
				Paint.textClipped(c, name, bx, top + 1, innerW / 2, t.text, false);
				c.text(time, bx + Math.min(innerW / 2, c.textWidth(name)) + 5, top + 1, t.textDim, false);
			}
		}
		if (it.face && m.sender != null) {
			Faces.draw(c, ctx.faces().face(m.sender.uuid, null), m.sender.uuid, m.sender.name, ox + 1, top + 1, 2, true);
		}
		int fill;
		int edge;
		if (it.placeholder) {
			fill = ColorMath.withAlpha(t.surface, 140);
			edge = t.border;
		} else if (it.own) {
			fill = ColorMath.lerp(t.surface, t.accent, m.pending || m.failed != null ? 0.25f : 0.5f);
			edge = ColorMath.lerp(t.accent, 0xFF000000, 0.3f);
		} else {
			fill = t.surfaceHigh;
			edge = t.border;
		}
		Redstone.stone(c, bx, by, it.bubbleW, it.bubbleH, fill, m.failed != null ? t.dustOn : edge);
		boolean hover = visibleMouse && Kit.inside(mx, my, bx, by, it.bubbleW, it.bubbleH);
		int inner = bx + ChatLayout.PAD;
		// Antwort-Vorschau
		if (it.replyY >= 0 && m.reply != null) {
			int ry = baseY + it.replyY;
			c.fill(inner, ry, inner + 2, ry + 9, t.dustOn);
			Paint.textClipped(c, ChatLayout.replyText(m.reply), inner + 5, ry + 1, it.bubbleW - ChatLayout.PAD * 2 - 5,
					ColorMath.lerp(t.textDim, t.text, 0.3f), false);
		}
		// Text (mit Links)
		if (it.textY >= 0) {
			int ty = baseY + it.textY;
			int color = it.placeholder ? t.textDim : t.text;
			for (int i = 0; i < it.lines.size(); i++) {
				String line = it.lines.get(i);
				c.text(line, inner, ty + 1, color, false);
				for (final ChatLayout.Link l : it.links) {
					if (l.line != i) continue;
					int lx = inner + c.textWidth(line.substring(0, l.start));
					String part = line.substring(l.start, l.end);
					int lw = c.textWidth(part);
					c.text(part, lx, ty + 1, ColorMath.lerp(t.accent, 0xFFFFFFFF, 0.35f), false);
					c.fill(lx, ty + 9, lx + lw, ty + 10, t.accent);
					kit.area(lx, ty, lw, 10, () -> openLink(l.url));
				}
				ty += ChatLayout.LINE_H;
			}
		}
		// Bilder
		if (it.imagesY >= 0) {
			int iy = baseY + it.imagesY;
			for (int i = 0; i < m.attachments.size(); i++) {
				final Chat.Attachment a = m.attachments.get(i);
				int col = i % it.imageCols;
				int row = i / it.imageCols;
				int cx = inner + col * (it.cellW + 2);
				int cyy = iy + row * (it.cellH + 2);
				image(c, s, a, cx, cyy, it.cellW, it.cellH, it.imageCols > 1);
				kit.quiet(cx, cyy, it.cellW, it.cellH, () -> ctx.dialog(new Dialogs.Lightbox(s, a, m.from(s.self()) ? null
						: () -> reportImage(m, a))));
				kit.right(cx, cyy, it.cellW, it.cellH, () -> {
					PopupMenu p = new PopupMenu(mx, my);
					p.add("zoom", I18n.tr("social.image.open"), () -> ctx.dialog(new Dialogs.Lightbox(s, a, null)));
					if (!m.from(s.self())) p.separator().danger("flag", I18n.tr("social.image.report"), () -> reportImage(m, a));
					ctx.popup(p);
				});
			}
		}
		// Einladung
		if (it.inviteY >= 0 && m.invite != null) {
			invite(c, s, m.invite, inner, baseY + it.inviteY, Math.min(ChatLayout.INVITE_W, it.bubbleW - ChatLayout.PAD * 2), mx, my);
		}
		// Reaktionen
		if (it.reactionsY >= 0) {
			int rx = it.own ? bx + it.bubbleW : bx;
			int ry = baseY + it.reactionsY;
			int perRow = Math.max(1, (it.bubbleW + 2) / (ChatLayout.CHIP_W + 2));
			int n = 0;
			for (final Chat.Reaction r : m.reactions) {
				int col = n % perRow;
				int row = n / perRow;
				int cx = it.own ? rx - (col + 1) * (ChatLayout.CHIP_W + 2) + 2 : rx + col * (ChatLayout.CHIP_W + 2);
				int cyy = ry + row * (ChatLayout.CHIP_H + 2);
				boolean mine = r.by(s.self());
				Redstone.block(c, cx, cyy, ChatLayout.CHIP_W, ChatLayout.CHIP_H, mine ? ColorMath.withAlpha(t.accent, 120) : t.surface);
				Icons.draw(c, Reactions.icon(r.emoji), cx + 2, cyy + 2, 1, Reactions.color(r.emoji));
				c.text(String.valueOf(Math.min(99, r.count)), cx + 12, cyy + 2, t.text, false);
				if (conv.canWrite) kit.area(cx, cyy, ChatLayout.CHIP_W, ChatLayout.CHIP_H, () -> s.react(m, r.emoji));
				n++;
			}
		}
		// Fußzeile
		if (it.footerY >= 0 && it.footer != null) {
			int fy = baseY + it.footerY;
			int color = it.footerError ? t.dustOn : t.textDim;
			String footer = it.footer;
			Social.Upload up = m.pending ? s.upload(m.nonce) : null;
			if (up != null) footer = I18n.tr("social.msg.uploading", up.done, up.total);
			if (it.own) Paint.textRight(c, c.clip(footer, innerW - 10), bx + it.bubbleW, fy, color, false);
			else Paint.textClipped(c, footer, bx, fy, innerW - it.bubbleX, color, false);
			if (m.failed != null) {
				kit.area(bx, fy, it.bubbleW, 10, () -> {
					PopupMenu p = new PopupMenu(mx, my);
					p.add("reset", I18n.tr("social.msg.retry"), () -> s.retry(m));
					p.danger("trash", I18n.tr("social.msg.discard"), () -> s.discard(m));
					ctx.popup(p);
				});
			}
		}
		// Aktionen bei Mausberührung + Rechtsklick
		if (!m.pending && m.failed == null && !m.deleted) {
			kit.right(bx, by, it.bubbleW, it.bubbleH, () -> messageMenu(m, mx, my));
			if (hover) {
				int ax = it.own ? bx - 28 : bx + it.bubbleW + 2;
				final int ay = by;
				if (m.hasContent() && conv.canWrite) {
					kit.icon(c, ax, ay, 12, "smile", false, mx, my, () -> reactionMenu(m, mx, my));
				}
				kit.icon(c, ax + 14, ay, 12, "menu", false, mx, my, () -> messageMenu(m, mx, my));
			}
		}
	}

	/** Bild (Vorschau) in ein Rechteck; {@code cover} = mittig beschneiden. */
	private void image(Canvas c, Social s, Chat.Attachment a, int x, int y, int w, int h, boolean cover) {
		Theme t = Theme.get();
		TextureRef tex = s.attachment(a, false);
		c.fill(x, y, x + w, y + h, t.deep);
		if (tex == null || !c.images()) {
			boolean failed = s.images().failed("t:" + a.id);
			Icons.draw(c, failed ? "close" : "image", x + w / 2 - 4, y + h / 2 - 4, 1, t.textDim);
			return;
		}
		float u = 0;
		float v = 0;
		int tw = tex.width;
		int th = tex.height;
		if (cover) {
			float target = w / (float) h;
			float src = tw / (float) th;
			if (src > target) {
				int nw = Math.max(1, Math.round(th * target));
				u = (tw - nw) / 2f;
				tw = nw;
			} else {
				int nh = Math.max(1, Math.round(tw / target));
				v = (th - nh) / 2f;
				th = nh;
			}
		}
		c.push();
		c.translate(x, y);
		c.scale(w / (float) tw, h / (float) th);
		c.image(tex, u, v, tw, th, 0xFFFFFFFF);
		c.pop();
	}

	private void invite(Canvas c, Social s, final Chat.Invite inv, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		Kit kit = ctx.kit();
		Redstone.stone(c, x, y, w, ChatLayout.INVITE_H, t.deep, ColorMath.lerp(t.border, t.lampOn, 0.4f));
		long now = System.currentTimeMillis();
		Chat.ServerStatus st = s.serverStatus(inv.address, now);
		TextureRef icon = s.serverIcon(st);
		int ix = x + 4;
		int iy = y + 4;
		if (icon != null && c.images()) {
			c.push();
			c.translate(ix, iy);
			c.scale(28f / icon.width, 28f / icon.height);
			c.image(icon, 0, 0, icon.width, icon.height, 0xFFFFFFFF);
			c.pop();
		} else {
			Redstone.block(c, ix, iy, 28, 28, t.bevelDark);
			Icons.draw(c, "globe", ix + 6, iy + 6, 2, st != null && st.online ? t.lampOn : t.textDim);
		}
		int bw = Math.min(58, Math.max(40, c.textWidth(I18n.tr("social.invite.join")) + 12));
		int bx = x + w - bw - 4;
		int tx = ix + 33;
		String label = inv.name != null ? inv.name : inv.address;
		Paint.textClipped(c, label, tx, y + 5, bx - tx - 3, t.text, false);
		String line2;
		if (st == null) line2 = I18n.tr("social.invite.checking");
		else if (st.online) line2 = I18n.tr("social.invite.players", st.players, st.max);
		else if ("private_address".equals(st.reason)) line2 = inv.name != null ? inv.address : I18n.tr("social.invite.private");
		else line2 = I18n.tr("social.invite.offline");
		Paint.textClipped(c, line2, tx, y + 15, bx - tx - 3, st != null && st.online ? t.lampOn : t.textDim, false);
		if (inv.name != null && st != null && st.online) Paint.textClipped(c, inv.address, tx, y + 25, bx - tx - 3, t.textDim, false);
		kit.button(c, bx, y + (ChatLayout.INVITE_H - 16) / 2, bw, 16, I18n.tr("social.invite.join"), true, true, mx, my,
				() -> join(inv));
	}

	/** „Beitreten“: in einer Welt erst nachfragen, dann verbinden. */
	void join(final Chat.Invite inv) {
		final SocialHost host = ctx.host();
		String current = host.currentServer();
		if (current != null && current.equalsIgnoreCase(inv.address)) {
			ctx.dialog(new Dialogs.Confirm(I18n.tr("social.invite.joinTitle"), I18n.tr("social.invite.already"), null,
					I18n.tr("social.ok"), false, () -> {
			}));
			return;
		}
		final String label = inv.name != null ? inv.name : inv.address;
		if (host.inWorld()) {
			ctx.dialog(new Dialogs.Confirm(I18n.tr("social.invite.joinTitle"), I18n.tr(current != null
					? "social.invite.leaveServer" : "social.invite.leaveWorld", label), inv.address, I18n.tr("social.invite.join"),
					false, () -> host.joinServer(inv.address, label)));
		} else {
			host.joinServer(inv.address, label);
		}
	}

	private void openLink(final String url) {
		ctx.dialog(new Dialogs.Confirm(I18n.tr("social.link.title"), I18n.tr("social.link.text"), url, I18n.tr("social.link.open"),
				false, () -> {
			if (!dev.theredstonee.trsclient.core.util.Links.open(url)) ctx.host().copy(url);
		}));
	}

	private void reportImage(Chat.Message m, Chat.Attachment a) {
		String who = m.sender == null ? "?" : m.sender.name;
		ctx.dialog(new Dialogs.Report(ctx.social(), "image", a.id, m.conversationId, I18n.tr("social.report.image", who)));
	}

	void reactionMenu(final Chat.Message m, int x, int y) {
		final Social s = ctx.social();
		List<String> own = new ArrayList<String>();
		for (Chat.Reaction r : m.reactions) if (r.by(s.self())) own.add(r.emoji);
		ctx.popup(new PopupMenu(x, y).reactions(emoji -> s.react(m, emoji), own));
	}

	/** Kontextmenü einer Nachricht. */
	void messageMenu(final Chat.Message m, int x, int y) {
		final Social s = ctx.social();
		Chat.Conversation conv = conversation();
		if (s == null || conv == null || m.system) return;
		boolean own = m.from(s.self());
		PopupMenu p = new PopupMenu(x, y);
		if (m.hasContent() && conv.canWrite) {
			List<String> mine = new ArrayList<String>();
			for (Chat.Reaction r : m.reactions) if (r.by(s.self())) mine.add(r.emoji);
			p.reactions(emoji -> s.react(m, emoji), mine);
			p.add("reply", I18n.tr("social.action.reply"), () -> {
				replyTo = m;
				editing = null;
				composer.setFocused(true);
			});
		}
		if (m.hasContent() && m.text != null && !m.text.isEmpty()) {
			p.add("copy", I18n.tr("social.action.copy"), () -> ctx.host().copy(m.text));
		}
		if (own && m.hasContent() && m.text != null && conv.canWrite) {
			p.add("pencil", I18n.tr("social.action.edit"), () -> {
				editing = m;
				replyTo = null;
				composer.setText(m.text);
				composer.setFocused(true);
			});
		}
		if (!own) p.add("mail", I18n.tr("social.action.markUnread"), () -> s.markUnread(m.conversationId, m));
		p.separator();
		if ((own || conv.ownedBy(s.self())) && !m.deleted) {
			p.danger("trash", I18n.tr("social.action.delete"), () -> ctx.dialog(new Dialogs.Confirm(I18n.tr("social.delete.title"),
					I18n.tr(own ? "social.delete.own" : "social.delete.other"), null, I18n.tr("social.action.delete"), true,
					() -> s.delete(m))));
		}
		if (!own && m.hasContent()) {
			String who = m.sender == null ? "?" : m.sender.name;
			p.danger("flag", I18n.tr("social.action.report"), () -> ctx.dialog(new Dialogs.Report(s, "message", m.id,
					m.conversationId, I18n.tr("social.report.message", who))));
		}
		ctx.popup(p);
	}

	// --- Eingabe ---

	private int composerHeight(Canvas c, Chat.Conversation conv, int w, boolean muted) {
		if (!conv.canWrite || muted) return 16;
		int h = 22;
		int lines = Math.max(1, Math.min(MAX_COMPOSER_LINES, wrapComposer(c, w).size()));
		h += (lines - 1) * ChatLayout.LINE_H;
		if (replyTo != null || editing != null) h += 12;
		if (!images.isEmpty() || invite != null) h += 24;
		return h;
	}

	private List<int[]> wrapComposer(final Canvas c, int w) {
		return ChatLayout.wrap(new ChatLayout.Measure() {
			@Override
			public int width(String s) {
				return c.textWidth(s);
			}
		}, composer.text(), Math.max(20, w - 64));
	}

	private void composer(Canvas c, final Social s, final Chat.Conversation conv, int x, int y, int w, int h, int mx, int my,
			boolean muted) {
		Theme t = Theme.get();
		final Kit kit = ctx.kit();
		if (!conv.canWrite || muted) {
			String text = muted ? I18n.tr("social.moderation.cantWrite") : "chat_muted".equals(conv.readOnlyReason)
					? I18n.tr("social.moderation.cantWrite") : I18n.tr("social.readOnly.notFriends");
			Redstone.well(c, x, y + 1, w, h - 1, t.border);
			Paint.textCentered(c, c.clip(text, w - 8), x + w / 2, y + 5, t.textDim, false);
			return;
		}
		int cy = y + 2;
		if (replyTo != null || editing != null) {
			String label = editing != null ? I18n.tr("social.composer.editing")
					: I18n.tr("social.composer.replyTo", replyTo.sender == null ? "?" : replyTo.sender.name,
					replyTo.preview() == null ? "…" : replyTo.preview());
			Icons.draw(c, editing != null ? "pencil" : "reply", x + 2, cy + 1, 1, t.accent);
			Paint.textClipped(c, label, x + 13, cy + 1, w - 30, t.textDim, false);
			kit.icon(c, x + w - 12, cy, 10, "close", false, mx, my, () -> {
				if (editing != null) composer.clear();
				replyTo = null;
				editing = null;
			});
			cy += 12;
		}
		if (!images.isEmpty() || invite != null) {
			int sx = x + 2;
			for (int i = 0; i < images.size(); i++) {
				final Path p = images.get(i);
				TextureRef tex = thumbs == null ? null : thumbs.get(p, modified(p));
				c.fill(sx, cy, sx + 36, cy + 20, t.deep);
				if (tex != null && c.images()) {
					c.push();
					c.translate(sx, cy);
					c.scale(36f / tex.width, 20f / tex.height);
					c.image(tex, 0, 0, tex.width, tex.height, 0xFFFFFFFF);
					c.pop();
				} else {
					Icons.draw(c, "image", sx + 14, cy + 6, 1, t.textDim);
				}
				kit.icon(c, sx + 26, cy, 10, "close", false, mx, my, () -> images.remove(p));
				sx += 38;
				if (sx > x + w - 120) break;
			}
			if (invite != null) {
				int iw = Math.min(140, x + w - sx - 2);
				if (iw > 40) {
					Redstone.stone(c, sx, cy, iw, 20, t.deep, ColorMath.lerp(t.border, t.lampOn, 0.4f));
					Icons.draw(c, "globe", sx + 3, cy + 6, 1, t.lampOn);
					Paint.textClipped(c, invite.address, sx + 14, cy + 6, iw - 28, t.text, false);
					kit.icon(c, sx + iw - 11, cy + 1, 10, "close", false, mx, my, () -> invite = null);
				}
			}
			cy += 24;
		}
		int fieldH = y + h - cy;
		// Bild- und Einladungs-Knopf
		int bx = x;
		kit.icon(c, bx, cy + 1, 16, "image", pickerOpen, mx, my, () -> togglePicker());
		bx += 18;
		final String server = ctx.host().currentServer();
		if (server != null && SafeText.serverAddress(server) != null) {
			kit.icon(c, bx, cy + 1, 16, "globe", invite != null, mx, my, () -> invite = invite == null
					? new Chat.Invite(SafeText.serverAddress(server), null) : null);
		} else {
			kit.iconDisabled(c, bx, cy + 1, 16, "globe");
		}
		bx += 18;
		int fx = bx + 1;
		int fw = x + w - fx - 20;
		Redstone.well(c, fx, cy, fw, fieldH, composer.focused() ? t.accent : t.border);
		List<int[]> lines = wrapComposer(c, w);
		String text = composer.text();
		int visibleLines = Math.min(MAX_COMPOSER_LINES, lines.size());
		int first = Math.max(0, lines.size() - visibleLines);
		// Die Zeile mit dem Cursor soll sichtbar sein.
		int cursorLine = lines.size() - 1;
		for (int i = 0; i < lines.size(); i++) {
			int[] r = lines.get(i);
			if (composer.cursor() >= r[0] && composer.cursor() <= r[1]) {
				cursorLine = i;
				break;
			}
		}
		if (cursorLine < first) first = cursorLine;
		if (cursorLine >= first + visibleLines) first = cursorLine - visibleLines + 1;
		int ty = cy + 5;
		if (text.isEmpty()) {
			String hint = I18n.tr("social.composer.hint", conv.title());
			Paint.textClipped(c, hint, fx + 5, ty, fw - 10, t.textDim, false);
		}
		for (int i = first; i < first + visibleLines && i < lines.size(); i++) {
			int[] r = lines.get(i);
			c.text(text.substring(r[0], r[1]), fx + 5, ty, t.text, false);
			if (composer.focused() && i == cursorLine && (System.currentTimeMillis() / 500) % 2 == 0) {
				int col = Math.max(r[0], Math.min(composer.cursor(), r[1]));
				int cx = fx + 5 + c.textWidth(text.substring(r[0], col));
				c.fill(cx, ty - 1, cx + 1, ty + 9, t.text);
			}
			ty += ChatLayout.LINE_H;
		}
		if (text.isEmpty() && composer.focused() && (System.currentTimeMillis() / 500) % 2 == 0) {
			c.fill(fx + 5, cy + 4, fx + 6, cy + 14, t.text);
		}
		kit.quiet(fx, cy, fw, fieldH, () -> composer.setFocused(true));
		int count = SafeText.length(text);
		if (count > SafeText.MAX_MESSAGE - 200) {
			Paint.textRight(c, count + "/" + SafeText.MAX_MESSAGE, fx + fw - 3, cy - 9,
					count > SafeText.MAX_MESSAGE ? t.dustOn : t.textDim, false);
		}
		boolean can = !composer.isEmpty() || !images.isEmpty() || invite != null;
		if (can) kit.icon(c, x + w - 18, cy + 1, 16, "send", true, mx, my, this::send);
		else kit.iconDisabled(c, x + w - 18, cy + 1, 16, "send");
	}

	private static long modified(Path p) {
		try {
			return java.nio.file.Files.getLastModifiedTime(p).toMillis();
		} catch (java.io.IOException | RuntimeException e) {
			return 0;
		}
	}

	/** Senden bzw. Bearbeiten übernehmen. */
	void send() {
		Social s = ctx.social();
		Chat.Conversation conv = conversation();
		if (s == null || conv == null) return;
		String text = composer.text();
		if (editing != null) {
			if (!text.trim().isEmpty() || !editing.attachments.isEmpty() || editing.invite != null) s.edit(editing, text);
			editing = null;
			composer.clear();
			return;
		}
		if (text.trim().isEmpty() && images.isEmpty() && invite == null) return;
		if (s.send(conv.id, text.trim(), replyTo == null ? null : replyTo.id, new ArrayList<Path>(images), invite)) {
			composer.clear();
			replyTo = null;
			images.clear();
			invite = null;
			scroll = 0;
			pickerOpen = false;
		}
	}

	// --- Bildauswahl ---

	void togglePicker() {
		pickerOpen = !pickerOpen;
		if (pickerOpen) {
			ensureLibrary();
			library.refresh();
			pickerSelection.clear();
			pickerSelection.addAll(images);
			pickerScroll = 0;
		}
	}

	private void ensureLibrary() {
		if (library != null) return;
		Path game = ctx.host().gameDir();
		pickerWorker = new ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(256), r -> {
			Thread th = new Thread(r, "TRS-Chat-Auswahl");
			th.setDaemon(true);
			th.setPriority(Thread.MIN_PRIORITY + 1);
			return th;
		});
		pickerWorker.allowCoreThreadTimeOut(true);
		library = new ClipLibrary(game == null ? null : game.resolve("config"), game, pickerWorker);
		thumbs = new Thumbnails(pickerWorker, 224, 126, 60);
	}

	private List<ClipLibrary.Entry> screenshots() {
		List<ClipLibrary.Entry> out = new ArrayList<ClipLibrary.Entry>();
		if (library == null) return out;
		for (ClipLibrary.Entry e : library.listing().entries) {
			if (e.type == ClipLibrary.Type.SCREENSHOT && e.name.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) out.add(e);
		}
		return out;
	}

	private void picker(Canvas c, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		final Kit kit = ctx.kit();
		c.fill(x, y, x + w, y + 18, t.surfaceHigh);
		String title = I18n.tr("social.picker.title", pickerSelection.size(), Social.MAX_IMAGES);
		int doneW = Math.min(70, c.textWidth(I18n.tr("social.picker.done")) + 16);
		Paint.textClipped(c, title, x + 5, y + 5, w - doneW - 30, t.text, false);
		kit.button(c, x + w - doneW - 2, y + 1, doneW, 16, I18n.tr("social.picker.done"), true, true, mx, my, () -> {
			images.clear();
			images.addAll(pickerSelection);
			pickerOpen = false;
			composer.setFocused(true);
		});
		kit.icon(c, x + w - doneW - 20, y + 1, 16, "folder", false, mx, my, () -> {
			if (library != null && library.listing().screenshotsDir != null) {
				dev.theredstonee.trsclient.core.util.OpenPath.open(library.listing().screenshotsDir);
			}
		});
		int gy = y + 20;
		int gh = h - 20;
		Redstone.well(c, x, gy, w, gh, t.border);
		List<ClipLibrary.Entry> shots = screenshots();
		if (shots.isEmpty()) {
			String msg = library != null && library.scanning() ? I18n.tr("social.loading") : I18n.tr("social.picker.empty");
			List<String> lines = Paint.wrap(c, msg, w - 20);
			int ty = gy + gh / 2 - lines.size() * 5;
			for (String l : lines) {
				Paint.textCentered(c, l, x + w / 2, ty, t.textDim, false);
				ty += 10;
			}
			return;
		}
		int cols = w >= 360 ? 4 : w >= 220 ? 3 : 2;
		int cellW = (w - 8 - (cols - 1) * 4) / cols;
		int cellH = cellW * 9 / 16;
		int rows = (shots.size() + cols - 1) / cols;
		int content = rows * (cellH + 4);
		pickerMax = Math.max(0, content - (gh - 8));
		pickerScroll = Math.max(0, Math.min(pickerScroll, pickerMax));
		c.scissor(x + 2, gy + 2, x + w - 2, gy + gh - 2);
		kit.hits.clip(x + 2, gy + 2, w - 4, gh - 4);
		for (int i = 0; i < shots.size(); i++) {
			final ClipLibrary.Entry e = shots.get(i);
			int col = i % cols;
			int row = i / cols;
			int cx = x + 4 + col * (cellW + 4);
			int cyy = gy + 4 + row * (cellH + 4) - pickerScroll;
			if (cyy + cellH < gy || cyy > gy + gh) continue;
			TextureRef tex = thumbs.get(e.path, e.modified);
			c.fill(cx, cyy, cx + cellW, cyy + cellH, t.deep);
			if (tex != null && c.images()) {
				c.push();
				c.translate(cx, cyy);
				c.scale(cellW / (float) tex.width, cellH / (float) tex.height);
				c.image(tex, 0, 0, tex.width, tex.height, 0xFFFFFFFF);
				c.pop();
			} else {
				Icons.draw(c, "image", cx + cellW / 2 - 4, cyy + cellH / 2 - 4, 1, t.textDim);
			}
			int index = pickerSelection.indexOf(e.path);
			boolean hov = Kit.inside(mx, my, cx, cyy, cellW, cellH);
			if (index >= 0) {
				Paint.outline(c, cx, cyy, cellW, cellH, t.accent);
				Paint.outline(c, cx + 1, cyy + 1, cellW - 2, cellH - 2, t.accent);
				Kit.badge(c, cx + cellW - 2, cyy + 2, index + 1);
			} else if (hov) {
				Paint.outline(c, cx, cyy, cellW, cellH, t.text);
			}
			kit.area(cx, cyy, cellW, cellH, () -> {
				if (pickerSelection.contains(e.path)) pickerSelection.remove(e.path);
				else if (pickerSelection.size() < Social.MAX_IMAGES) pickerSelection.add(e.path);
			});
		}
		kit.hits.noClip();
		c.noScissor();
		Kit.scrollbar(c, x + w - 4, gy + 2, gh - 4, pickerScroll, pickerMax, content);
	}

	// --- Eingaben ---

	boolean mouseScrolled(double mx, double my, double amount) {
		if (!Kit.inside(mx, my, area[0], area[1], area[2], area[3])) return false;
		if (pickerOpen) {
			pickerScroll = Math.max(0, Math.min(pickerMax, pickerScroll - (int) Math.round(amount * 30)));
		} else {
			scroll = Math.max(0, Math.min(maxScroll, scroll + (int) Math.round(amount * 24)));
		}
		return true;
	}

	/** Taste (Eingabefeld hat Vorrang). */
	boolean keyPressed(UiKey key, boolean shift) {
		if (conversationId == null) return false;
		if (key == UiKey.ESCAPE) {
			if (pickerOpen) {
				pickerOpen = false;
				return true;
			}
			if (editing != null || replyTo != null) {
				if (editing != null) composer.clear();
				editing = null;
				replyTo = null;
				return true;
			}
			return false;
		}
		if (!composer.focused()) return false;
		if (key == UiKey.ENTER) {
			if (shift) composer.newline();
			else send();
			return true;
		}
		boolean done = composer.key(key, key == UiKey.PASTE ? ctx.host().paste() : null);
		if (done) typed();
		return done;
	}

	boolean charTyped(char ch) {
		if (conversationId == null) return false;
		Chat.Conversation conv = conversation();
		if (conv == null || !conv.canWrite) return false;
		composer.setFocused(true);
		boolean ok = composer.type(ch);
		if (ok) typed();
		return ok;
	}

	private void typed() {
		Social s = ctx.social();
		if (s == null || conversationId == null || editing != null) return;
		long now = System.currentTimeMillis();
		if (composer.isEmpty()) s.typing(conversationId, false);
		else if (now - lastTyping > 1000L) {
			lastTyping = now;
			s.typing(conversationId, true);
		}
	}

	/** Für den Selbsttest: die letzte Nachricht (optional nur fremde). */
	Chat.Message last(boolean foreign) {
		Social s = ctx.social();
		ChatStore.Thread th = s == null || conversationId == null ? null : s.store().peek(conversationId);
		if (th == null) return null;
		List<Chat.Message> list = th.messages();
		for (int i = list.size() - 1; i >= 0; i--) {
			Chat.Message m = list.get(i);
			if (m.system || m.deleted) continue;
			if (foreign && m.from(s.self())) continue;
			return m;
		}
		return null;
	}

	/** Für den Selbsttest: erstes Bild der Unterhaltung. */
	Chat.Attachment firstImage() {
		Social s = ctx.social();
		ChatStore.Thread th = s == null || conversationId == null ? null : s.store().peek(conversationId);
		if (th == null) return null;
		for (Chat.Message m : th.messages()) if (!m.attachments.isEmpty()) return m.attachments.get(0);
		return null;
	}

	/** Mitte des Nachrichtenbereichs (für Menüs im Selbsttest). */
	int[] area() {
		return area;
	}
}
