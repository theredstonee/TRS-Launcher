package dev.theredstonee.trsclient.core.social;

import dev.theredstonee.trsclient.core.account.FaceCache;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Faces;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;

import java.util.List;

/**
 * Einstieg der Versionsbäume für die Sozial-Benachrichtigungen: {@link #install} beim Start, {@link #render} im HUD
 * (und – wo es einen Haken gibt – über allen Bildschirmen), {@link #takeQuickAction} für die Schnelltaste.
 *
 * <p>Kosten: ohne Toasts ist {@link #active()} ein Feldzugriff; gezeichnet werden höchstens drei kleine Karten.
 */
public final class SocialOverlay {
	public static final int TOAST_W = 172;
	public static final int TOAST_H = 38;
	private static final int MARGIN = 4;
	private static final int GAP = 3;

	private static volatile SocialPlatform platform;
	private static volatile TrsModules modules;
	private static final Toasts.Settings SETTINGS = new Toasts.Settings();
	private static long lastFrame;

	private SocialOverlay() {
	}

	/** Beim Start des Mods (Loader). */
	public static void install(SocialPlatform p, TrsModules m) {
		platform = p;
		modules = m;
	}

	public static SocialPlatform platform() {
		return platform;
	}

	/** Social der laufenden Online-Verbindung oder null. */
	static Social social() {
		TrsOnline online = TrsOnline.current();
		return online == null ? null : online.social();
	}

	/** Einstellungen aus dem Modul „Sozial“ übernehmen (je Tick aus {@link Social#tick}). */
	static void applySettings(Toasts toasts) {
		TrsModules m = modules;
		Toasts.Settings s = SETTINGS;
		if (m != null) {
			s.enabled = m.social.isEnabled() && m.socialToasts.get();
			s.corner = m.socialCorner.get();
			s.durationMs = Math.round(Math.max(3, Math.min(10, m.socialDuration.get())) * 1000);
			s.sound = m.socialSound.get();
			s.dnd = m.socialDnd.get();
			s.dndFullscreen = m.socialDndFullscreen.get();
			s.messages = m.socialToastMessages.get();
			s.invites = m.socialToastInvites.get();
			s.requests = m.socialToastRequests.get();
			s.online = m.socialToastOnline.get();
		}
		toasts.settings(s);
		SocialPlatform p = platform;
		if (p != null) {
			try {
				toasts.fullscreen(p.fullscreen());
			} catch (RuntimeException ignored) {
				// egal
			}
		}
	}

	/** Modul „Sozial“ an (Stream + Toasts auch ohne offenen Bildschirm)? Ohne Modul-Anbindung (Tests): an. */
	public static boolean enabled() {
		TrsModules m = modules;
		return m == null || m.social.isEnabled();
	}

	/** Gibt es gerade Toasts? Billig – vor {@link #render} fragen. */
	public static boolean active() {
		Social s = social();
		return s != null && s.toasts().active();
	}

	/** Zeichnet die Toasts in die eingestellte Ecke. Render-Thread; nie Ausnahmen nach außen. */
	public static void render(Canvas c, int width, int height) {
		Social social = social();
		if (social == null) return;
		try {
			long now = System.currentTimeMillis();
			lastFrame = now;
			Toasts toasts = social.toasts();
			List<Toasts.Toast> list = toasts.visible(now);
			if (toasts.takeSound()) {
				SocialPlatform p = platform;
				if (p != null) p.playToastSound();
			}
			if (list.isEmpty()) return;
			Toasts.Corner corner = toasts.settings().corner;
			boolean right = corner == Toasts.Corner.TOP_RIGHT || corner == Toasts.Corner.BOTTOM_RIGHT;
			boolean top = corner == Toasts.Corner.TOP_RIGHT || corner == Toasts.Corner.TOP_LEFT;
			int w = Math.min(TOAST_W, width - MARGIN * 2);
			int y = top ? MARGIN : height - MARGIN - TOAST_H;
			SocialPlatform p = platform;
			String key = p == null ? null : p.quickReplyKey();
			Toasts.Toast quick = toasts.quickTarget(now);
			for (Toasts.Toast t : list) {
				float vis = t.visibility(now);
				int slide = Math.round((1 - vis) * (w + MARGIN));
				int x = right ? width - MARGIN - w + slide : MARGIN - slide;
				draw(c, t, x, y, w, now, t == quick ? key : null);
				y += top ? TOAST_H + GAP : -(TOAST_H + GAP);
			}
		} catch (RuntimeException ignored) {
			// Benachrichtigungen dürfen das Spiel nie stören.
		}
	}

	private static void draw(Canvas c, Toasts.Toast t, int x, int y, int w, long now, String quickKey) {
		Theme th = Theme.get();
		int edge;
		switch (t.kind) {
			case INVITE:
				edge = th.lampOn;
				break;
			case REQUEST:
			case CAPE_OFFER:
				edge = th.accent;
				break;
			case MODERATION:
				edge = th.dustOn;
				break;
			case ONLINE:
				edge = ColorMath.lerp(th.border, th.lampOn, 0.5f);
				break;
			default:
				edge = th.accent;
		}
		Redstone.stone(c, x, y, w, TOAST_H, ColorMath.withAlpha(th.surface, 235), edge);
		int ix = x + 6;
		int iy = y + 6;
		if (t.faceUuid != null) {
			int[] face = FaceCache.shared("TRS-Client").face(t.faceUuid, null);
			Faces.draw(c, face, t.faceUuid, t.faceName, ix, iy, 2, true);
		} else {
			Redstone.block(c, ix - 1, iy - 1, 18, 18, th.bevelDark);
			Icons.draw(c, icon(t.kind), ix, iy, 2, t.kind == Toasts.Kind.MODERATION ? th.dustOn : th.text);
		}
		if (t.kind == Toasts.Kind.ONLINE) Redstone.pip(c, ix + 12, iy + 12, 6, 1f);
		int tx = x + 28;
		int right = x + w - 6;
		String title = t.count > 1 ? t.title + " (" + t.count + ")" : t.title;
		Paint.textClipped(c, title, tx, y + 5, right - tx, th.text, false);
		Paint.textClipped(c, t.text, tx, y + 15, right - tx, th.textDim, false);
		if (quickKey != null) {
			String hint = I18n.tr(t.kind == Toasts.Kind.INVITE ? "social.toast.keyJoin"
					: t.kind == Toasts.Kind.MESSAGE ? "social.toast.keyReply" : "social.toast.keyOpen", quickKey);
			Paint.textClipped(c, hint, tx, y + 25, right - tx, ColorMath.lerp(th.textDim, th.lampOn, 0.6f), false);
		}
		// Redstone-Staub als Zeitbalken.
		int barW = Math.round((w - 4) * t.remaining(now));
		if (barW > 0) Redstone.dustH(c, x + 2, x + 2 + barW, y + TOAST_H - 3, th.dustOn, 0.4f);
	}

	private static String icon(Toasts.Kind kind) {
		switch (kind) {
			case INVITE:
				return "signal";
			case REQUEST:
				return "friends";
			case CAPE_OFFER:
				return "cape";
			case REPORT:
				return "check";
			case MODERATION:
				return "lock";
			case ONLINE:
				return "friends";
			default:
				return "chat";
		}
	}

	/** Was die Schnelltaste tun soll. */
	public static final class QuickAction {
		public enum Kind {
			/** Kleines Eingabefeld für eine Antwort. */
			REPLY,
			/** Einladung: Beitreten (mit Antwortfeld). */
			JOIN,
			/** Sozial-Bildschirm, Reiter Freunde → Anfragen. */
			REQUESTS
		}

		public final Kind kind;
		public final String conversationId;
		public final Chat.Invite invite;
		public final String title;

		QuickAction(Kind kind, String conversationId, Chat.Invite invite, String title) {
			this.kind = kind;
			this.conversationId = conversationId;
			this.invite = invite;
			this.title = title;
		}

		/** Für Selbsttests/Oberfläche. */
		public static QuickAction reply(String conversationId, String title) {
			return new QuickAction(Kind.REPLY, conversationId, null, title);
		}
	}

	/**
	 * Schnelltaste gedrückt: Aktion zum neuesten passenden Toast (und der Toast verschwindet) oder null. Spiel-Thread.
	 */
	public static QuickAction takeQuickAction() {
		Social s = social();
		if (s == null) return null;
		Toasts.Toast t = s.toasts().quickTarget(System.currentTimeMillis());
		if (t == null) return null;
		s.toasts().dismiss(t.id);
		switch (t.kind) {
			case MESSAGE:
				return new QuickAction(QuickAction.Kind.REPLY, t.conversationId, null, t.title);
			case INVITE:
				return new QuickAction(QuickAction.Kind.JOIN, t.conversationId, t.invite, t.title);
			default:
				return new QuickAction(QuickAction.Kind.REQUESTS, null, null, t.title);
		}
	}

	/** Für Selbsttests: einen Toast einblenden, als käme er aus dem Stream. */
	public static boolean testToast(Toasts.Kind kind, String title, String text, String faceUuid, String conversationId,
			Chat.Invite invite) {
		Social s = social();
		if (s == null) return false;
		return s.toasts().add(kind, conversationId == null ? null : "conv:" + conversationId, title, text, faceUuid, title,
				conversationId, invite, System.currentTimeMillis());
	}

	/** Wann zuletzt gezeichnet wurde (Selbsttest). */
	public static long lastFrame() {
		return lastFrame;
	}
}
