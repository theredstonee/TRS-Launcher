package dev.theredstonee.trsclient.core.ui.social;

import dev.theredstonee.trsclient.core.account.FaceCache;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.social.Chat;
import dev.theredstonee.trsclient.core.social.ChatStore;
import dev.theredstonee.trsclient.core.social.SafeText;
import dev.theredstonee.trsclient.core.social.Social;
import dev.theredstonee.trsclient.core.social.SocialOverlay;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.FadeCanvas;
import dev.theredstonee.trsclient.core.ui.Faces;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Schnellantwort über dem laufenden Spiel (Taste aus einem Toast): die letzten Nachrichten der Unterhaltung, ein
 * Eingabefeld (Enter sendet, Esc schließt) und bei Einladungen die Karte mit „Beitreten“. Kein Abdunkeln, das HUD
 * bleibt sichtbar, das Spiel pausiert nicht.
 */
public final class QuickReplyUi extends UiScreen {
	private static final int W = 300;

	private final SocialHost host;
	private final TrsOnline online;
	private final SocialOverlay.QuickAction action;
	private final ChatInput input = new ChatInput(SafeText.MAX_MESSAGE);
	private final Kit kit;
	private boolean confirmJoin;
	private boolean sent;

	public QuickReplyUi(SocialHost host, TrsOnline online, SocialOverlay.QuickAction action) {
		this.host = host;
		this.online = online;
		this.action = action;
		input.setFocused(true);
		I18n.refresh();
		this.kit = new Kit(hits, new Runnable() {
			@Override
			public void run() {
				QuickReplyUi.this.host.playClick();
			}
		});
	}

	private Social social() {
		return online == null ? null : online.social();
	}

	@Override
	protected void draw(Canvas raw, int width, int height, int mx, int my, float dt) {
		Theme t = Theme.get();
		Canvas c = FadeCanvas.of(raw, alpha());
		Social s = social();
		long now = System.currentTimeMillis();
		Chat.Conversation conv = s == null || action.conversationId == null ? null : s.store().get(action.conversationId);
		if (s != null) {
			s.screenOpen(action.conversationId, now);
			s.images().frame();
		}
		int w = Math.min(W, width - 16);
		List<Chat.Message> recent = recent(s);
		int lines = Math.max(1, recent.size());
		int inviteH = action.invite != null ? ChatLayout.INVITE_H + 6 : 0;
		int h = 20 + lines * 11 + 4 + inviteH + 22 + 12;
		int x = (width - w) / 2;
		int y = height - h - 40 + Math.round((1 - alpha()) * 12);
		c.push();
		c.raise(300f);
		Paint.shadow(c, x, y, w, h, 2, 0.6f);
		Redstone.stone(c, x, y, w, h, ColorMath.withAlpha(t.surface, 240), t.accent);
		// Kopf
		String title = conv != null ? conv.title() : action.title == null ? "?" : action.title;
		String faceId = conv != null ? conv.faceUuid() : null;
		if (faceId != null) {
			Faces.draw(c, FaceCache.shared(host.userAgent()).face(faceId, null), faceId, title, x + 5, y + 4, 1, true);
		} else {
			Icons.draw(c, "chat", x + 5, y + 5, 1, t.accent);
		}
		Paint.textClipped(c, I18n.tr("social.quickReply.to", title), x + 17, y + 5, w - 36, t.text, false);
		kit.icon(c, x + w - 16, y + 3, 12, "close", false, mx, my, this::requestClose);
		int cy = y + 18;
		// Letzte Nachrichten
		if (recent.isEmpty()) {
			Paint.textClipped(c, I18n.tr(conv == null ? "social.loading" : "social.quickReply.empty"), x + 6, cy, w - 12, t.textDim,
					false);
			cy += 11;
		} else {
			for (Chat.Message m : recent) {
				String who = m.sender == null ? "?" : m.from(s.self()) ? I18n.tr("social.quickReply.you") : m.sender.name;
				String body = m.deleted ? I18n.tr("social.msg.deletedText") : m.hidden ? I18n.tr("social.msg.hiddenText")
						: m.invite != null ? I18n.tr("social.preview.inviteTo", m.invite.name != null ? m.invite.name : m.invite.address)
						: m.preview() != null ? m.preview() : I18n.tr("social.preview.image");
				int nw = Math.min(w / 3, c.textWidth(who + ":"));
				Paint.textClipped(c, who + ":", x + 6, cy, nw, m.from(s.self()) ? t.accent : t.text, false);
				Paint.textClipped(c, body, x + 9 + nw, cy, w - 15 - nw, t.textDim, false);
				cy += 11;
			}
		}
		cy += 4;
		// Einladung
		if (action.invite != null) {
			inviteCard(c, s, x + 6, cy, w - 12, mx, my);
			cy += inviteH;
		}
		// Eingabe
		boolean canWrite = conv != null && conv.canWrite && s != null && !s.moderation().active(now);
		Redstone.well(c, x + 6, cy, w - 30, 18, canWrite ? t.accent : t.border);
		String text = input.text();
		if (!canWrite) {
			Paint.textClipped(c, I18n.tr(conv == null ? "social.loading" : "social.moderation.cantWrite"), x + 11, cy + 5, w - 40,
					t.textDim, false);
		} else if (text.isEmpty()) {
			Paint.textClipped(c, I18n.tr("social.composer.hint", title), x + 11, cy + 5, w - 40, t.textDim, false);
		} else {
			// Nur das Ende zeigen (einzeilig), Zeilenumbrüche als Leerzeichen.
			String shown = text.replace('\n', ' ');
			String visible = shown;
			while (c.textWidth(visible) > w - 44 && visible.length() > 1) visible = visible.substring(1);
			c.text(visible, x + 11, cy + 5, t.text, false);
		}
		if (canWrite && (System.currentTimeMillis() / 500) % 2 == 0) {
			String shown = text.replace('\n', ' ');
			int cx = x + 11 + Math.min(w - 44, c.textWidth(shown));
			c.fill(cx, cy + 4, cx + 1, cy + 14, t.text);
		}
		if (canWrite && !input.isEmpty()) kit.icon(c, x + w - 22, cy + 1, 16, "send", true, mx, my, this::send);
		else kit.iconDisabled(c, x + w - 22, cy + 1, 16, "send");
		cy += 21;
		Paint.textClipped(c, I18n.tr("social.quickReply.hint"), x + 6, cy, w - 12, ColorMath.withAlpha(t.textDim, 200), false);
		c.pop();
	}

	private List<Chat.Message> recent(Social s) {
		List<Chat.Message> out = new ArrayList<Chat.Message>();
		if (s == null || action.conversationId == null) return out;
		ChatStore.Thread th = s.store().peek(action.conversationId);
		if (th == null) return out;
		List<Chat.Message> all = th.messages();
		for (int i = all.size() - 1; i >= 0 && out.size() < 3; i--) {
			Chat.Message m = all.get(i);
			if (!m.system) out.add(0, m);
		}
		return out;
	}

	private void inviteCard(Canvas c, Social s, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		final Chat.Invite inv = action.invite;
		Redstone.stone(c, x, y, w, ChatLayout.INVITE_H, t.deep, ColorMath.lerp(t.border, t.lampOn, 0.4f));
		Chat.ServerStatus st = s == null ? null : s.serverStatus(inv.address, System.currentTimeMillis());
		TextureRef icon = s == null ? null : s.serverIcon(st);
		if (icon != null && c.images()) {
			c.push();
			c.translate(x + 4, y + 4);
			c.scale(28f / icon.width, 28f / icon.height);
			c.image(icon, 0, 0, icon.width, icon.height, 0xFFFFFFFF);
			c.pop();
		} else {
			Redstone.block(c, x + 4, y + 4, 28, 28, t.bevelDark);
			Icons.draw(c, "globe", x + 10, y + 10, 2, t.lampOn);
		}
		int bw = 60;
		int bx = x + w - bw - 4;
		Paint.textClipped(c, inv.name != null ? inv.name : inv.address, x + 37, y + 6, bx - x - 40, t.text, false);
		String line2 = st == null ? I18n.tr("social.invite.checking") : st.online ? I18n.tr("social.invite.players", st.players,
				st.max) : I18n.tr("social.invite.offline");
		Paint.textClipped(c, confirmJoin ? I18n.tr(host.currentServer() != null ? "social.quickReply.leaveServer"
				: "social.quickReply.leaveWorld") : line2, x + 37, y + 18, bx - x - 40, confirmJoin ? t.dustOn : t.textDim, false);
		String label = I18n.tr(confirmJoin ? "social.invite.joinNow" : "social.invite.join");
		kit.button(c, bx, y + 10, bw, 16, label, true, true, mx, my, () -> {
			if (host.inWorld() && !confirmJoin) {
				confirmJoin = true;
				return;
			}
			requestClose();
			host.joinServer(inv.address, inv.name != null ? inv.name : inv.address);
		});
	}

	private void send() {
		Social s = social();
		if (s == null || action.conversationId == null || input.isEmpty() || sent) return;
		if (s.send(action.conversationId, input.text().trim(), null, null, null)) {
			sent = true;
			input.clear();
			requestClose();
		}
	}

	@Override
	protected void onClosed() {
		Social s = social();
		if (s != null) {
			if (!input.isEmpty() && action.conversationId != null) s.typing(action.conversationId, false);
			s.screenClosed();
		}
		host.closeScreen();
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (key == UiKey.ESCAPE) {
			requestClose();
			return true;
		}
		if (key == UiKey.ENTER) {
			if (shift) input.newline();
			else send();
			return true;
		}
		boolean done = input.key(key, key == UiKey.PASTE ? host.paste() : null);
		if (done) typed();
		return done;
	}

	@Override
	public boolean charTyped(char ch) {
		boolean ok = input.type(ch);
		if (ok) typed();
		return ok;
	}

	private void typed() {
		Social s = social();
		if (s == null || action.conversationId == null) return;
		s.typing(action.conversationId, !input.isEmpty());
	}

	@Override
	public boolean overlay() {
		return true;
	}

	@Override
	public boolean pausesGame() {
		return false;
	}

	/** Selbsttest: Text eintippen. */
	public void testType(String text) {
		input.insert(text);
	}
}
