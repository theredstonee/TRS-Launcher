package dev.theredstonee.trsclient.core.ui.menus;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.net.StatusPing;
import dev.theredstonee.trsclient.core.partner.PartnerServer;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.TextureRef;

import java.util.List;
import java.util.Locale;

/**
 * Abschnitt „Partner-Server“ über den eigenen Servern der Mehrspieler-Liste. Liegt im Listeninhalt vor dem ersten
 * Eintrag (scrollt mit) und ist {@link #height} hoch; {@code y} ist überall die Oberkante, an der sonst der erste
 * Eintrag stünde. Zeichnen und Treffer kennen Minecraft nicht.
 */
public final class PartnerSection {
	public static final int TITLE_H = 14;
	public static final int ROW = 36;
	public static final int OWN_H = 14;
	static final int TAG = 0xFF3D8BFF;
	static final int EDGE = 0x8C3D8BFF;
	static final int EDGE_HOVER = 0xFF6AA6FF;
	static final int FILL = 0xD00E2454;
	static final int FILL_HOVER = 0xE0163266;

	/** Was ein Klick getroffen hat. */
	public enum Target { NONE, HIDE, ROW }

	public static final class Hit {
		public final Target target;
		public final int row;

		Hit(Target target, int row) {
			this.target = target;
			this.row = row;
		}
	}

	static final Hit NONE = new Hit(Target.NONE, -1);

	/** Daten je Zeile (im Spiel {@code PartnerServers}). */
	public interface Source {
		TextureRef logo(PartnerServer s);

		StatusPing.Result ping(PartnerServer s);
	}

	private PartnerSection() {
	}

	/** Höhe des Abschnitts (0 = keine Partner-Server, Liste bleibt wie Vanilla). */
	public static int height(int servers) {
		return servers <= 0 ? 0 : TITLE_H + servers * ROW + OWN_H;
	}

	/** Oberkante des Inhalts von Zeile {@code i} (Karte reicht 2 px darüber und darunter hinaus, wie Vanilla). */
	public static int rowTop(int y, int i) {
		return y + TITLE_H + i * ROW;
	}

	/**
	 * Treffer bei ({@code mx}, {@code my}): {@code x}/{@code w} = Inhaltsbreite der Einträge, {@code top}/{@code bottom}
	 * = sichtbarer Bereich der Liste.
	 */
	public static Hit hit(int servers, int x, int y, int w, int top, int bottom, int mx, int my) {
		if (servers <= 0 || my < top || my >= bottom || mx < x - 2 || mx >= x + w + 2) return NONE;
		int[] hide = hideRect(x, y, w, null);
		if (mx >= hide[0] && mx < hide[2] && my >= hide[1] && my < hide[3]) return new Hit(Target.HIDE, -1);
		for (int i = 0; i < servers; i++) {
			int ry = rowTop(y, i);
			if (my >= ry - 2 && my < ry + 34) return new Hit(Target.ROW, i);
		}
		return NONE;
	}

	/** Fläche von „ausblenden ✕“ {x1, y1, x2, y2}; ohne Canvas mit geschätzter Breite. */
	static int[] hideRect(int x, int y, int w, Canvas c) {
		String label = I18n.tr("menus.partners.hide");
		int tw = c != null ? c.textWidth(label) : label.length() * 6;
		int right = x + w;
		return new int[]{right - tw - 14, y, right + 2, y + 11};
	}

	/** {@code styled} = Menü-Stil der Serverliste an (sonst Ping-Balken grün wie Vanilla). */
	public static void draw(Canvas c, List<PartnerServer> servers, Source source, int x, int y, int w, int top, int bottom,
			int mouseX, int mouseY, boolean styled) {
		int n = servers.size();
		if (n == 0) return;
		boolean mouseIn = mouseY >= top && mouseY < bottom;
		if (y + TITLE_H >= top && y <= bottom) {
			String title = I18n.tr("menus.partners.title").toUpperCase(Locale.ROOT);
			c.text(c.clip(title, Math.max(0, w - 80)), x, y + 2, 0xFFBBBBBB, true);
			int[] hide = hideRect(x, y, w, c);
			boolean hot = mouseIn && mouseX >= hide[0] && mouseX < hide[2] && mouseY >= hide[1] && mouseY < hide[3];
			int color = hot ? 0xFFFFFFFF : 0xFF9A9A9A;
			String label = I18n.tr("menus.partners.hide");
			c.text(label, hide[0], y + 2, color, true);
			Icons.draw(c, "close", x + w - 8, y + 2, 1, color);
		}
		for (int i = 0; i < n; i++) {
			int ry = rowTop(y, i);
			if (ry + 34 < top || ry - 2 > bottom) continue;
			boolean hover = mouseIn && mouseX >= x - 2 && mouseX < x + w + 2 && mouseY >= ry - 2 && mouseY < ry + 34;
			row(c, servers.get(i), source, x, ry, w, hover, styled);
		}
		int oy = rowTop(y, n) + 1;
		if (oy + 10 >= top && oy <= bottom) {
			c.text(I18n.tr("menus.partners.own").toUpperCase(Locale.ROOT), x, oy, 0xFFBBBBBB, true);
		}
	}

	static void row(Canvas c, PartnerServer s, Source source, int x, int y, int w, boolean hover, boolean styled) {
		int fill = hover ? FILL_HOVER : FILL;
		int edge = hover ? EDGE_HOVER : EDGE;
		c.fill(x - 2, y - 2, x + w + 2, y + 34, edge);
		c.fill(x - 1, y - 1, x + w + 1, y + 33, fill);
		icon(c, source == null ? null : source.logo(s), s, x, y);

		StatusPing.Result r = source == null ? null : source.ping(s);
		String players = r != null && r.ok && r.online >= 0 && r.max >= 0 ? r.online + "/" + r.max : "";
		int pw = players.isEmpty() ? 0 : c.textWidth(players);
		if (!players.isEmpty()) c.text(players, x + w - 15 - 2 - pw, y + 1, 0xFF9A9A9A, true);
		long ping = r == null ? 0 : (r.ok ? r.latencyMs : -1);
		if (styled) MenuSkin.pingBars(c, x + w - 15, y, ping, r == null, fill);
		else bars(c, x + w - 15, y, ping, r == null);

		String tag = I18n.tr("menus.partners.tag");
		int tagW = c.textWidth(tag) + 6;
		int textX = x + 35;
		int room = Math.max(0, x + w - 15 - 6 - pw - textX - tagW - 4);
		String name = c.clip(s.name, room);
		c.text(name, textX, y + 1, 0xFFFFFFFF, true);
		int tx = textX + c.textWidth(name) + 4;
		c.fill(tx, y, tx + tagW, y + 10, TAG);
		c.text(tag, tx + 3, y + 1, 0xFFFFFFFF, false);

		if (s.motd != null) c.text(c.clip(s.motd, Math.max(0, w - 37)), textX, y + 12, 0xFFAAAAAA, true);
		if (hover) {
			String join = I18n.tr("menus.partners.join");
			int jw = c.textWidth(join) + 8;
			int jx = x + w - jw;
			c.fill(jx, y + 20, jx + jw, y + 32, TAG);
			c.fill(jx, y + 20, jx + jw, y + 21, 0x40FFFFFF);
			c.text(join, jx + 4, y + 22, 0xFFFFFFFF, true);
		} else if (s.partnerName != null && !s.partnerName.equals(s.name)) {
			String by = c.clip(s.partnerName, Math.max(0, w - 37));
			c.text(by, textX, y + 22, 0xFF6F86B0, false);
		}
	}

	/** Ping-Balken in Vanilla-Farben (klassische Serverliste). */
	static void bars(Canvas c, int x, int y, long ping, boolean pinging) {
		int lit = pinging ? -1 : ping < 0 ? 0 : ping < 150 ? 5 : ping < 300 ? 4 : ping < 600 ? 3 : ping < 1000 ? 2 : 1;
		int running = (int) (System.currentTimeMillis() / 100 % 8);
		for (int i = 0; i < 5; i++) {
			int bh = 2 + i + (i > 2 ? 1 : 0) + 1;
			int color = lit < 0 ? (i == running ? 0xFF3DDC84 : 0xFF3A3A3A) : lit == 0 ? 0xFF8B1E1E : i < lit ? 0xFF3DDC84 : 0xFF3A3A3A;
			c.fill(x + i * 2, y + 8 - bh, x + i * 2 + 1, y + 8, color);
		}
	}

	static void icon(Canvas c, TextureRef logo, PartnerServer s, int x, int y) {
		c.fill(x, y, x + 32, y + 32, 0xFF1A1A1A);
		if (logo != null && c.images()) {
			c.push();
			c.translate(x, y);
			c.scale(32f / logo.width);
			c.image(logo, 0, 0, logo.width, logo.height, 0xFFFFFFFF);
			c.pop();
			return;
		}
		String name = s.partnerName != null ? s.partnerName : s.name;
		String initials = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
		c.fill(x + 4, y + 4, x + 28, y + 28, TAG);
		c.text(initials, x + 16 - c.textWidth(initials) / 2, y + 12, 0xFFFFFFFF, true);
	}
}
