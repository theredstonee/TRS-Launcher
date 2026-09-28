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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Wohin mit der Wegpunkt-Karte bzw. dem Bildschirmfoto? Unterhaltungen (mit Schreibrecht) und Freunde ohne DM (die DM
 * wird dafür geöffnet). Karte: ein Klick sendet sie und öffnet die Unterhaltung. Bild: mehrere Ziele ankreuzen und
 * „Senden“ – das Bild geht über den normalen Bildversand des Chats (§18) an jedes Ziel.
 */
public final class ShareTargetDialog extends Dialog {
	private final SocialContext ctx;
	private final Social social;
	private final FaceCache faces;
	private final Chat.Waypoint card;
	/** Bild statt Karte (dann Mehrfachauswahl) oder null. */
	private final Path image;
	/** Ausgewählte Ziele: {@code c:<unterhaltung>} bzw. {@code f:<uuid>}. */
	private final Set<String> selected = new LinkedHashSet<String>();
	private int scroll;
	private int maxScroll;
	private boolean busy;

	ShareTargetDialog(SocialContext ctx, Social social, Chat.Waypoint card) {
		this.ctx = ctx;
		this.social = social;
		this.faces = ctx.faces();
		this.card = card;
		this.image = null;
	}

	/** Bildschirmfoto an Freunde/Unterhaltungen senden (Mehrfachauswahl). */
	ShareTargetDialog(SocialContext ctx, Social social, Path image) {
		this.ctx = ctx;
		this.social = social;
		this.faces = ctx.faces();
		this.card = null;
		this.image = image;
	}

	/** Das Bild, das verschickt werden soll (oder null bei einer Karte). */
	public Path image() {
		return image;
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
		return I18n.tr(image != null ? "screenshots.send.title" : "waypoint.share.title");
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
		int cy;
		int listBottom = y + h;
		if (image != null) {
			// Bild oben, „Senden“ unten
			int ph = 54;
			c.fill(x, y, x + w, y + ph, 0xFF0B0909);
			dev.theredstonee.trsclient.core.ui.TextureRef ref = social.localImage(image, 256);
			if (ref != null && c.images()) {
				float sc = Math.min((w - 4) / (float) ref.width, (ph - 4) / (float) ref.height);
				float dw = ref.width * sc, dh = ref.height * sc;
				dev.theredstonee.trsclient.core.ui.Affine.image(c, ref, x + (w - dw) / 2f, y + (ph - dh) / 2f, dw, dh, 0, 0,
						ref.width, ref.height, 0xFFFFFFFF);
			} else {
				Icons.draw(c, "image", x + w / 2 - 8, y + ph / 2 - 8, 2, t.textDim);
			}
			cy = y + ph + 5;
			Paint.textClipped(c, I18n.tr(busy ? "waypoint.share.sending" : "screenshots.send.pick"), x, cy, w, t.textDim, false);
			cy += 12;
			listBottom = y + h - 24;
			String label = selected.isEmpty() ? I18n.tr("screenshots.send.button")
					: I18n.tr("screenshots.send.buttonCount", selected.size());
			kit.button(c, x + w - 110, y + h - 19, 110, 18, label, true, !busy && !selected.isEmpty(), mx, my, () -> sendImage());
		} else {
			// Karte oben (ohne Knöpfe)
			WaypointCard.draw(c, kit, card, x, y, w, mx, my, false, null);
			cy = y + ChatLayout.INVITE_H + 6;
			Paint.textClipped(c, I18n.tr(busy ? "waypoint.share.sending" : "waypoint.share.pick"), x, cy, w, t.textDim, false);
			cy += 12;
		}
		int listH = listBottom - cy;
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
			final String key = isConv ? "c:" + conv.id : "f:" + friend.uuid;
			boolean checked = selected.contains(key);
			if (hover || checked) c.fill(x + 2, ry - 1, x + w - 6, ry + 15, ColorMath.withAlpha(t.accent, checked ? 0x60 : 0x40));
			int lx = x + 5;
			if (image != null) {
				Redstone.well(c, lx, ry + 2, 10, 10, checked ? t.accent : t.border);
				if (checked) Icons.draw(c, "check", lx + 1, ry + 3, 1, t.text);
				lx += 14;
			}
			if (isConv && conv.group) Icons.draw(c, "friends", lx, ry + 3, 1, t.accent);
			else if (face != null) Faces.draw(c, faces.face(face, null), face, label, lx, ry + 3, 1, false);
			Paint.textClipped(c, label, lx + 12, ry + 3, w - 60 - (lx - x - 5), t.text, false);
			if (!isConv) Paint.textRight(c, I18n.tr("waypoint.share.newDm"), x + w - 10, ry + 3, t.textDim, false);
			if (!busy && image != null) {
				kit.area(x + 2, ry - 1, w - 8, 16, () -> {
					if (!selected.remove(key)) selected.add(key);
				});
			} else if (!busy) {
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

	/** Bild an alle ausgewählten Ziele (fehlende DMs werden zuerst geöffnet). */
	private void sendImage() {
		if (image == null || selected.isEmpty() || busy) return;
		final List<Path> files = new ArrayList<Path>();
		files.add(image);
		final int total = selected.size();
		final int[] left = {total};
		final int[] ok = {0};
		busy = true;
		for (String key : new ArrayList<String>(selected)) {
			if (key.startsWith("c:")) {
				if (social.send(key.substring(2), "", null, files, null)) ok[0]++;
				imageDone(left, ok, total);
			} else {
				social.openDm(key.substring(2), (value, error) -> {
					if (value != null && social.send(value.id, "", null, files, null)) ok[0]++;
					imageDone(left, ok, total);
				});
			}
		}
	}

	private void imageDone(int[] left, int[] ok, int total) {
		if (--left[0] > 0) return;
		busy = false;
		if (ok[0] > 0) {
			social.hint("screenshots.send.sent", new Object[]{ok[0]}, false);
			close();
			String only = total == 1 ? selected.iterator().next() : null;
			if (only != null && only.startsWith("c:")) ctx.open(only.substring(2));
		} else {
			social.hint("social.error.generic", new Object[0], true);
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
