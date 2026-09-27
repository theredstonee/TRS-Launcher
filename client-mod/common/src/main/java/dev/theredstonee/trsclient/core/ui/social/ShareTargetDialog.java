package dev.theredstonee.trsclient.core.ui.social;

import dev.theredstonee.trsclient.core.account.FaceCache;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.FriendsView;
import dev.theredstonee.trsclient.core.social.Chat;
import dev.theredstonee.trsclient.core.social.Social;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Faces;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wohin mit der Wegpunkt-Karte? Unterhaltungen (mit Schreibrecht) und Freunde ohne DM (die DM wird dafür geöffnet).
 * Ein Klick sendet die Karte und öffnet die Unterhaltung.
 */
public final class ShareTargetDialog extends Dialog {
	private final SocialContext ctx;
	private final Social social;
	private final FaceCache faces;
	private final Chat.Waypoint card;
	private int scroll;
	private int maxScroll;
	private boolean busy;

	ShareTargetDialog(SocialContext ctx, Social social, Chat.Waypoint card) {
		this.ctx = ctx;
		this.social = social;
		this.faces = ctx.faces();
		this.card = card;
	}

	/** Die Karte, die verschickt werden soll. */
	public Chat.Waypoint card() {
		return card;
	}

	@Override
	protected int[] size(int screenW, int screenH) {
		return new int[]{Math.min(280, screenW - 20), Math.min(250, screenH - 16)};
	}

	@Override
	protected String title() {
		return I18n.tr("waypoint.share.title");
	}

	/** Ziele: beschreibbare Unterhaltungen (neueste zuerst), dann Freunde ohne DM (alphabetisch). */
	List<Object> targets() {
		List<Object> out = new ArrayList<Object>();
		for (Chat.Conversation c : social.store().sorted()) if (c.canWrite) out.add(c);
		List<FriendsView.Friend> rest = new ArrayList<FriendsView.Friend>();
		FriendsView friends = ctx.friends();
		if (friends != null) {
			for (FriendsView.Friend f : friends.friends) if (social.store().dmWith(f.uuid) == null) rest.add(f);
		}
		Collections.sort(rest, (a, b) -> a.name.compareToIgnoreCase(b.name));
		out.addAll(rest);
		return out;
	}

	@Override
	protected void body(Canvas c, Kit kit, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		// Karte oben (ohne Knöpfe)
		WaypointCard.draw(c, kit, card, x, y, w, mx, my, false, null);
		int cy = y + ChatLayout.INVITE_H + 6;
		Paint.textClipped(c, I18n.tr(busy ? "waypoint.share.sending" : "waypoint.share.pick"), x, cy, w, t.textDim, false);
		cy += 12;
		int listH = y + h - cy;
		Redstone.well(c, x, cy, w, listH, t.border);
		c.scissor(x + 2, cy + 2, x + w - 2, cy + listH - 2);
		kit.hits.clip(x + 2, cy + 2, w - 4, listH - 4);
		int ry = cy + 3 - scroll;
		List<Object> list = targets();
		if (list.isEmpty()) Paint.textClipped(c, I18n.tr("waypoint.share.noTargets"), x + 5, ry + 3, w - 10, t.textDim, false);
		for (Object o : list) {
			final boolean isConv = o instanceof Chat.Conversation;
			final Chat.Conversation conv = isConv ? (Chat.Conversation) o : null;
			final FriendsView.Friend friend = isConv ? null : (FriendsView.Friend) o;
			String label = isConv ? conv.title() : friend.name;
			String face = isConv ? conv.faceUuid() : friend.uuid;
			boolean hover = Kit.inside(mx, my, x + 2, ry - 1, w - 8, 16);
			if (hover) c.fill(x + 2, ry - 1, x + w - 6, ry + 15, ColorMath.withAlpha(t.accent, 0x40));
			if (isConv && conv.group) Icons.draw(c, "friends", x + 5, ry + 3, 1, t.accent);
			else if (face != null) Faces.draw(c, faces.face(face, null), face, label, x + 5, ry + 3, 1, false);
			Paint.textClipped(c, label, x + 17, ry + 3, w - 60, t.text, false);
			if (!isConv) Paint.textRight(c, I18n.tr("waypoint.share.newDm"), x + w - 10, ry + 3, t.textDim, false);
			if (!busy) {
				kit.area(x + 2, ry - 1, w - 8, 16, () -> {
					if (isConv) send(conv.id);
					else openAndSend(friend.uuid);
				});
			}
			ry += 17;
		}
		kit.hits.noClip();
		c.noScissor();
		int content = ry + scroll - (cy + 3);
		maxScroll = Math.max(0, content - (listH - 6));
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		Kit.scrollbar(c, x + w - 4, cy + 2, listH - 4, scroll, maxScroll, content);
	}

	private void send(String conversationId) {
		if (social.send(conversationId, "", null, null, new Chat.Invite(card))) {
			social.hint("waypoint.share.sent", new Object[]{card.name}, false);
			close();
			ctx.open(conversationId);
		}
	}

	private void openAndSend(String uuid) {
		busy = true;
		social.openDm(uuid, (value, error) -> {
			busy = false;
			if (value != null) send(value.id);
		});
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(amount) * 17));
		return true;
	}
}
