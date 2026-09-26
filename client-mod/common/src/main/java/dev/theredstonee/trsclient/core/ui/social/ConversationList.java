package dev.theredstonee.trsclient.core.ui.social;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.FriendsView;
import dev.theredstonee.trsclient.core.social.Chat;
import dev.theredstonee.trsclient.core.social.ChatStore;
import dev.theredstonee.trsclient.core.social.Social;
import dev.theredstonee.trsclient.core.social.Times;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Faces;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.Theme;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Linke Spalte des Chat-Reiters: Unterhaltungen (neueste zuerst) mit Gesicht, Vorschau, Zeit, Ungelesen-Lampe und
 * Stumm-Symbol; darunter Freunde ohne Unterhaltung („Nachricht schreiben“). Suche filtert beides.
 */
final class ConversationList {
	static final int ROW_H = 26;

	private final SocialContext ctx;
	final TextInput search = new TextInput(32);
	boolean searchOpen;
	private int scroll;
	private int maxScroll;
	private final int[] rect = new int[4];

	ConversationList(SocialContext ctx) {
		this.ctx = ctx;
	}

	/** Die Unterhaltungen in Anzeigereihenfolge (gefiltert). */
	List<Chat.Conversation> visible() {
		Social s = ctx.social();
		List<Chat.Conversation> out = new ArrayList<Chat.Conversation>();
		if (s == null) return out;
		String q = query();
		for (Chat.Conversation c : s.store().sorted()) {
			if (q == null || c.title().toLowerCase(Locale.ROOT).contains(q) || memberMatch(c, q)) out.add(c);
		}
		return out;
	}

	private static boolean memberMatch(Chat.Conversation c, String q) {
		if (!c.group) return false;
		for (Chat.Member m : c.members) if (m.name.toLowerCase(Locale.ROOT).contains(q)) return true;
		return false;
	}

	private String query() {
		if (!searchOpen) return null;
		String q = search.text().trim().toLowerCase(Locale.ROOT);
		return q.isEmpty() ? null : q;
	}

	/** Freunde ohne DM (für „Nachricht schreiben“). */
	private List<FriendsView.Friend> friendsWithoutChat() {
		List<FriendsView.Friend> out = new ArrayList<FriendsView.Friend>();
		FriendsView view = ctx.friends();
		Social s = ctx.social();
		if (view == null || s == null) return out;
		Set<String> withDm = new HashSet<String>();
		for (Chat.Conversation c : s.store().sorted()) if (!c.group && c.peer != null) withDm.add(c.peer.uuid);
		String q = query();
		for (FriendsView.Friend f : view.friends) {
			if (withDm.contains(f.uuid)) continue;
			if (q != null && !f.name.toLowerCase(Locale.ROOT).contains(q)) continue;
			out.add(f);
		}
		return out;
	}

	void draw(Canvas c, int x, int y, int w, int h, int mx, int my, String selected) {
		Theme t = Theme.get();
		final Kit kit = ctx.kit();
		final Social s = ctx.social();
		int cy = y;
		if (searchOpen) {
			kit.input(c, search, x, cy, w, 16, I18n.tr("social.searchHint"), null);
			cy += 19;
		}
		rect[0] = x;
		rect[1] = cy;
		rect[2] = w;
		rect[3] = y + h - cy;
		int lh = y + h - cy;
		Redstone.well(c, x, cy, w, lh, t.border);
		if (s == null) return;
		ChatStore store = s.store();
		if (!store.listLoaded()) {
			Paint.textCentered(c, c.clip(I18n.tr("social.loading"), w - 6), x + w / 2, cy + 10, t.textDim, false);
			return;
		}
		int lx = x + 2;
		int lw = w - 8;
		int top = cy + 2;
		int bottom = cy + lh - 2;
		c.scissor(lx, top, lx + lw + 4, bottom);
		kit.hits.clip(lx, top, lw + 4, bottom - top);
		int ry = top - scroll;
		long now = System.currentTimeMillis();
		List<Chat.Conversation> list = visible();
		for (final Chat.Conversation conv : list) {
			if (ry + ROW_H >= top && ry <= bottom) row(c, kit, conv, lx, ry, lw, mx, my, conv.id.equals(selected), now, top, bottom);
			ry += ROW_H;
		}
		List<FriendsView.Friend> extra = friendsWithoutChat();
		if (!extra.isEmpty()) {
			if (!list.isEmpty()) {
				ry += 2;
				Paint.textClipped(c, I18n.tr("social.list.friends"), lx + 3, ry + 1, lw - 6, t.textDim, false);
				ry += 12;
			}
			for (final FriendsView.Friend f : extra) {
				if (ry + ROW_H >= top && ry <= bottom) friendRow(c, kit, f, lx, ry, lw, mx, my, top, bottom);
				ry += ROW_H;
			}
		}
		if (list.isEmpty() && extra.isEmpty()) {
			List<String> lines = Paint.wrap(c, I18n.tr(query() != null ? "social.list.noMatch" : "social.list.empty"), lw - 8);
			int ty = top + 8;
			for (String l : lines) {
				Paint.textCentered(c, l, lx + lw / 2, ty, t.textDim, false);
				ty += 10;
			}
		}
		kit.hits.noClip();
		c.noScissor();
		int content = ry + scroll - top;
		maxScroll = Math.max(0, content - (bottom - top));
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		Kit.scrollbar(c, x + w - 4, top, bottom - top, scroll, maxScroll, content);
	}

	private void row(Canvas c, Kit kit, final Chat.Conversation conv, int x, int y, int w, int mx, int my, boolean selected,
			long now, int top, int bottom) {
		Theme t = Theme.get();
		boolean hover = Kit.inside(mx, my, x, y, w, ROW_H - 2) && my >= top && my < bottom;
		int bg = selected ? ColorMath.lerp(t.surface, t.accent, 0.28f) : hover ? t.surfaceHover : t.surface;
		Redstone.stone(c, x, y, w, ROW_H - 2, bg, selected ? t.accent : t.border);
		String faceId = conv.faceUuid();
		Faces.draw(c, ctx.faces().face(faceId, null), faceId, conv.title(), x + 4, y + 4, 2, true);
		if (conv.group) {
			Redstone.block(c, x + 13, y + 13, 10, 10, t.bevelDark);
			Icons.draw(c, "friends", x + 14, y + 14, 1, t.text);
		}
		int tx = x + 25;
		int right = x + w - 4;
		String when = conv.lastMessage == null ? "" : Times.shortLabel(conv.lastMessage.createdAt, now);
		int whenW = c.textWidth(when);
		Paint.textRight(c, when, right, y + 3, t.textDim, false);
		int badge = conv.muted ? 0 : conv.badge();
		int nameRight = right - whenW - 4;
		Paint.textClipped(c, conv.title(), tx, y + 3, nameRight - tx, conv.badge() > 0 ? t.text : ColorMath.lerp(t.text, t.textDim, 0.2f),
				false);
		int previewRight = right;
		if (badge > 0) previewRight -= Kit.badge(c, right, y + 13, badge) + 3;
		if (conv.muted) {
			Icons.draw(c, "mute", previewRight - 8, y + 14, 1, t.textDim);
			previewRight -= 11;
		}
		String preview = preview(conv);
		Paint.textClipped(c, preview, tx, y + 14, previewRight - tx, conv.badge() > 0 ? t.text : t.textDim, false);
		kit.quiet(x, y, w, ROW_H - 2, () -> ctx.open(conv.id));
		kit.right(x, y, w, ROW_H - 2, () -> ctx.conversationMenu(conv, mx, my));
	}

	/** Vorschau der letzten Nachricht („Du: …“ bei eigenen). */
	String preview(Chat.Conversation conv) {
		Social s = ctx.social();
		Chat.Message m = conv.lastMessage;
		if (s != null) {
			List<String> typers = s.store().typers(conv.id, System.currentTimeMillis());
			if (!typers.isEmpty()) return I18n.tr("social.typing.short");
		}
		if (!conv.canWrite && "not_friends".equals(conv.readOnlyReason) && m == null) return I18n.tr("social.readOnly.notFriends");
		if (m == null) return I18n.tr("social.list.clickToWrite");
		String body;
		if (m.system) body = ChatLayout.systemText(m);
		else if (m.deleted) body = I18n.tr("social.msg.deletedText");
		else if (m.hidden) body = I18n.tr("social.msg.hiddenText");
		else if (m.invite != null) body = I18n.tr("social.preview.inviteTo", m.invite.name != null ? m.invite.name : m.invite.address);
		else if (m.preview() != null) body = m.preview();
		else if (!m.attachments.isEmpty()) {
			body = I18n.tr(m.attachments.size() == 1 ? "social.preview.image" : "social.preview.images", m.attachments.size());
		} else body = "…";
		if (s != null && m.from(s.self()) && !m.system) return I18n.tr("social.preview.you", body);
		if (conv.group && m.sender != null && !m.system) return m.sender.name + ": " + body;
		return body;
	}

	private void friendRow(Canvas c, Kit kit, final FriendsView.Friend f, int x, int y, int w, int mx, int my, int top,
			int bottom) {
		Theme t = Theme.get();
		boolean hover = Kit.inside(mx, my, x, y, w, ROW_H - 2) && my >= top && my < bottom;
		Redstone.stone(c, x, y, w, ROW_H - 2, hover ? t.surfaceHover : ColorMath.withAlpha(t.surface, 160), t.border);
		Faces.draw(c, ctx.faces().face(f.uuid, null), f.uuid, f.name, x + 4, y + 4, 2, true);
		Redstone.pip(c, x + 15, y + 15, 6, f.inGame() ? 1f : f.online() ? 0.45f : 0f);
		Paint.textClipped(c, f.name, x + 25, y + 3, w - 30, t.text, false);
		Paint.textClipped(c, I18n.tr("social.list.clickToWrite"), x + 25, y + 14, w - 30, t.textDim, false);
		kit.quiet(x, y, w, ROW_H - 2, () -> openDm(f.uuid));
	}

	void openDm(String uuid) {
		Social s = ctx.social();
		if (s == null) return;
		s.openDm(uuid, (conv, error) -> {
			if (conv != null) ctx.open(conv.id);
		});
	}

	boolean mouseScrolled(double mx, double my, double amount) {
		if (!Kit.inside(mx, my, rect[0], rect[1], rect[2], rect[3])) return false;
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.round(amount * ROW_H)));
		return true;
	}
}
