package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Anim;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.module.NewSince;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.menu.NewBadge;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.core.waypoint.WaypointShare;
import dev.theredstonee.trsclient.core.waypoint.live.FriendFaces;
import dev.theredstonee.trsclient.core.waypoint.live.FriendPosition;
import dev.theredstonee.trsclient.core.waypoint.live.FriendWaypoint;
import dev.theredstonee.trsclient.core.waypoint.live.LiveShare;
import dev.theredstonee.trsclient.core.waypoint.live.Owner;

import java.util.ArrayList;
import java.util.List;

/**
 * Wegpunkt-Liste rechts auf der Weltkarte (ein-/ausklappbar): Suche, Filter nach Dimension, je Wegpunkt Farbe, Name,
 * Dimension/Koordinaten, Entfernung und Ein/Aus-Schalter; Klick zentriert die Karte darauf, der Stift öffnet den
 * Wegpunkt-Dialog der Karte (dort auch Löschen), „+“ legt einen neuen in der Kartenmitte an. Reiter „Freunde“
 * (Live-Teilen): je Freund eine Gruppe mit seinen geteilten Wegpunkten, ein-/ausblendbar, „Hinzoomen“ und „Übernehmen“.
 */
final class WorldMapSidebar {
	/** Verbindung zur Weltkarte. */
	interface Host {
		MapEngine engine();

		void click();

		/** Karte auf den Wegpunkt zentrieren (ggf. in seine Dimension wechseln). */
		void centerOnWaypoint(Waypoint w);

		/** Wegpunkt-Dialog öffnen. */
		void editWaypoint(Waypoint w);

		/** Neuer Wegpunkt in der Kartenmitte. */
		void newWaypoint();

		/** Karte auf einen Wegpunkt eines Freundes zentrieren. */
		default void centerOnFriendWaypoint(FriendWaypoint w) {
		}

		/** Karte auf die Live-Position eines Freundes zentrieren („Hinzoomen“). */
		default void centerOnFriend(Owner o) {
		}

		/** Wegpunkt eines Freundes übernehmen. */
		default void adopt(FriendWaypoint w) {
		}
	}

	static final int WIDTH = 168;
	static final int ROW_H = 22;
	private static final int TABS_H = 16;
	private static final int HEAD_H = 42 + TABS_H;
	private static final int FOOT_H = 18;

	final TextInput search = new TextInput(32);
	private String dimFilter = WaypointFilter.ALL;
	private final Host host;
	private float scroll;
	private float scrollTarget;
	private int maxScroll;
	private final int[] rect = new int[4];
	private final int[] listRect = new int[4];
	/** Wegpunkt unter dem Zeiger (die Karte hebt ihn hervor). */
	Waypoint hovered;
	/** Wegpunkt eines Freundes unter dem Zeiger (Reiter „Freunde“). */
	FriendWaypoint hoveredFriend;
	private List<WaypointFilter.Row> rows = new ArrayList<WaypointFilter.Row>();
	/** Reiter „Freunde“ aktiv? */
	private boolean friendsTab;
	/** Zeilen des Reiters „Freunde“: {@link Owner} (Kopfzeile) oder {@link FriendWaypoint}. */
	private final List<Object> friendRows = new ArrayList<Object>();

	WorldMapSidebar(Host host) {
		this.host = host;
	}

	boolean contains(double mx, double my) {
		return mx >= rect[0] && mx < rect[0] + rect[2] && my >= rect[1] && my < rect[1] + rect[3];
	}

	/** Zeilen wie gerade angezeigt (Tests). */
	List<WaypointFilter.Row> rows() {
		return rows;
	}

	void setDimFilter(String dim) {
		dimFilter = dim == null ? WaypointFilter.ALL : dim;
		scrollTarget = 0;
	}

	String dimFilter() {
		return dimFilter;
	}

	void setFriendsTab(boolean friends) {
		if (friendsTab != friends) scrollTarget = 0;
		friendsTab = friends;
	}

	boolean friendsTab() {
		return friendsTab;
	}

	/** Zeilen des Reiters „Freunde“ (Tests). */
	List<Object> friendRows() {
		return friendRows;
	}

	void draw(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my, float dt, List<String> tooltip) {
		MapEngine e = host.engine();
		Theme t = Theme.get();
		rect[0] = x;
		rect[1] = y;
		rect[2] = w;
		rect[3] = h;
		hovered = null;
		hoveredFriend = null;
		c.fill(x, y, x + w, y + h, 0xEC141217);
		c.fill(x, y, x + 1, y + h, ColorMath.withAlpha(t.dustOn, 0xB0));
		// Klicks auf leere Flächen der Leiste nicht zur Karte durchlassen.
		hits.add(x, y, w, h, () -> search.setFocused(false));

		// Reiter: eigene Wegpunkte | Freunde (Live-Teilen).
		LiveShare live = LiveShare.get();
		if (live == null) friendsTab = false;
		int tabW = (w - 12) / 2;
		tab(c, hits, x + 6, y + 4, tabW, I18n.tr("map.list.tabMine"), !friendsTab, mx, my, false);
		if (live != null) {
			tab(c, hits, x + 6 + tabW, y + 4, w - 12 - tabW, I18n.tr("map.list.tabFriends"), friendsTab, mx, my, true);
			if (isNew(NewSince.WORLD_MAP_FRIENDS) && !friendsTab) NewBadge.dot(c, x + 6 + w - 12 - 5, y + 5);
		}
		y += TABS_H;
		h -= TABS_H;

		if (friendsTab) {
			seen(NewSince.WORLD_MAP_FRIENDS);
			drawFriends(c, hits, x, y, w, h, mx, my, dt, tooltip, live);
			return;
		}

		List<Waypoint> all = e.allWaypoints();
		String playerDim = e.dimension();
		List<String> dims = WaypointFilter.dimensions(all, playerDim);
		if (!dims.contains(dimFilter)) dimFilter = WaypointFilter.ALL;
		rows = WaypointFilter.apply(all, search.text(), dimFilter, playerDim, e.playerX(), e.playerZ());

		// Suche.
		int fx = x + 6, fy = y + 5, fw = w - 12;
		Redstone.well(c, fx, fy, fw, 16, search.focused() ? t.accent : t.border);
		Icons.draw(c, "search", fx + 4, fy + 4, 1, t.textDim);
		String text = search.text();
		if (text.isEmpty() && !search.focused()) {
			c.text(c.clip(I18n.tr("map.list.search"), fw - 20), fx + 15, fy + 4, t.textDim, false);
		} else {
			c.text(c.clip(text, fw - 20), fx + 15, fy + 4, t.text, false);
			if (search.focused() && (System.currentTimeMillis() / 500) % 2 == 0) {
				int cx = fx + 15 + c.textWidth(text.substring(0, Math.min(search.cursor(), text.length())));
				if (cx < fx + fw - 3) c.fill(cx, fy + 3, cx + 1, fy + 13, t.text);
			}
		}
		hits.add(fx, fy, fw, 16, () -> search.setFocused(true));

		// Dimensionsfilter (Klick links: zurück, rechts: weiter).
		int dy = fy + 20;
		String label = WaypointFilter.ALL.equals(dimFilter) ? I18n.tr("map.list.allDims") : WorldMapUi.dimensionName(dimFilter);
		boolean hovDim = inside(mx, my, fx, dy, fw, 14);
		Redstone.button(c, fx, dy, fw, 14, "", false, hovDim);
		c.text("‹", fx + 4, dy + 3, t.textDim, false);
		c.text("›", fx + fw - 8, dy + 3, t.textDim, false);
		String clipped = c.clip(label, fw - 24);
		c.text(clipped, fx + (fw - c.textWidth(clipped)) / 2, dy + 3, t.text, false);
		final List<String> order = dims;
		hits.add(fx, dy, fw / 2, 14, () -> cycleDim(order, -1));
		hits.add(fx + fw / 2, dy, fw - fw / 2, 14, () -> cycleDim(order, 1));

		// Liste.
		int ly = y + HEAD_H - TABS_H, lh = h - (HEAD_H - TABS_H) - FOOT_H;
		listRect[0] = x;
		listRect[1] = ly;
		listRect[2] = w;
		listRect[3] = lh;
		maxScroll = Math.max(0, rows.size() * ROW_H - lh);
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
		scroll = Anim.approach(scroll, scrollTarget, dt, 0.05f);
		if (Math.abs(scroll - scrollTarget) < 0.5f) scroll = scrollTarget;
		c.fill(x + 1, ly - 1, x + w, ly, 0x60000000);
		if (rows.isEmpty()) {
			String empty = I18n.tr(all.isEmpty() ? "map.list.empty" : "map.list.noMatch");
			int ew = c.textWidth(empty);
			c.text(c.clip(empty, w - 12), x + Math.max(6, (w - ew) / 2), ly + 12, t.textDim, false);
		}
		c.flush();
		c.scissor(x + 1, ly, x + w, ly + lh);
		hits.clip(x + 1, ly, w - 1, lh);
		int first = (int) (scroll / ROW_H);
		for (int i = first; i < rows.size(); i++) {
			int ry = ly + i * ROW_H - Math.round(scroll);
			if (ry >= ly + lh) break;
			row(c, hits, rows.get(i), x + 1, ry, w - 1, mx, my, ly, lh, t, tooltip);
		}
		c.flush();
		hits.noClip();
		c.noScissor();
		// Bildlaufleiste.
		if (maxScroll > 0) {
			int bh = Math.max(12, lh * lh / (rows.size() * ROW_H));
			int by = ly + Math.round((lh - bh) * (scroll / maxScroll));
			c.fill(x + w - 3, by, x + w - 1, by + bh, ColorMath.withAlpha(t.dustOn, 0x90));
		}

		// Fuß: Anzahl + „Neu“.
		int footY = y + h - FOOT_H;
		c.fill(x + 1, footY, x + w, footY + 1, 0x60000000);
		String count = I18n.tr("map.list.count", rows.size());
		c.text(c.clip(count, w - 34), x + 6, footY + 5, t.textDim, false);
		int bx = x + w - 22, byy = footY + 2;
		boolean hovNew = inside(mx, my, bx, byy, 16, 14);
		Redstone.button(c, bx, byy, 16, 14, "", false, hovNew);
		Icons.draw(c, "plus", bx + 4, byy + 3, 1, t.text);
		hits.add(bx, byy, 16, 14, () -> {
			host.click();
			host.newWaypoint();
		});
		if (hovNew) tooltip.add(I18n.tr("map.list.new"));
	}

	private void row(Canvas c, Hits hits, final WaypointFilter.Row r, int x, int y, int w, int mx, int my, int ly, int lh,
			Theme t, List<String> tooltip) {
		final Waypoint wp = r.waypoint;
		boolean inList = my >= ly && my < ly + lh;
		boolean hov = inList && inside(mx, my, x, y, w, ROW_H);
		if (hov) {
			c.fill(x, y, x + w, y + ROW_H, ColorMath.withAlpha(t.dustOn, 0x38));
			hovered = wp;
		}
		MapSprites sprites = host.engine().sprites();
		double gs = host.engine().platform() == null ? 2.0 : host.engine().platform().guiScale();
		boolean shown = wp.visible || wp.death;
		if (wp.death) sprites.draw(c, MapSprites.GRAVE, x + 10, y + ROW_H / 2f, 10f, 0f, 0xFFFFFFFF, gs);
		else sprites.draw(c, MapSprites.DIAMOND, x + 10, y + ROW_H / 2f, 9f, 0f, (shown ? 0xFF000000 : 0x70000000) | wp.color, gs);
		// Zeile 1: Name, Entfernung.
		String dist = WaypointFilter.distanceText(r.distance);
		if (!Double.isNaN(r.distance)) dist = (r.converted ? "≈" : "") + I18n.tr("map.distance", dist);
		int dw = c.textWidth(dist);
		int nameW = w - 24 - dw - 6;
		c.text(c.clip(wp.name, nameW), x + 20, y + 3, shown ? t.text : t.textDim, false);
		c.text(dist, x + w - 5 - dw, y + 3, t.textDim, false);
		// Zeile 2: Dimension (falls nicht die eigene) + Koordinaten, rechts die Knöpfe.
		String where = wp.x + ", " + wp.z;
		if (wp.dimension != null && !wp.dimension.isEmpty() && !wp.dimension.equals(host.engine().dimension())) {
			where = WorldMapUi.dimensionName(wp.dimension) + " · " + where;
		}
		c.text(c.clip(where, w - 24 - 44), x + 20, y + 12, ColorMath.withAlpha(t.textDim, 0xB0), false);
		hits.add(x, y, w, ROW_H, () -> {
			host.click();
			host.centerOnWaypoint(wp);
		});
		int ex = x + w - 28, py = y + 12;
		if (LiveShare.get() != null) {
			// Mit Freunden teilen (Live): Symbol leuchtet, wenn geteilt; Todespunkte teilt die Einstellung.
			int sx = x + w - 41;
			boolean on = wp.shared || (wp.death && host.engine().modules().waypointShareDeath.get());
			boolean hovShare = hov && inside(mx, my, sx - 1, py - 1, 11, 10);
			Icons.draw(c, "friends", sx, py, 1, on ? FriendFaces.FRIEND : (hovShare ? t.text : 0x60FFFFFF));
			hits.add(sx - 1, py - 1, 11, 10, () -> {
				host.click();
				wp.shared = !wp.shared;
				host.engine().waypointEdited();
			});
			if (hovShare) tooltip.add(I18n.tr(wp.shared ? "map.menu.unshareFriends" : "map.menu.shareFriends"));
		}
		if (!wp.death) {
			boolean hovEye = hov && inside(mx, my, ex - 1, py - 1, 11, 10);
			Icons.draw(c, "eye", ex, py, 1, shown ? (hovEye ? t.text : t.textDim) : 0x60FFFFFF);
			if (!shown) c.fill(ex - 1, py + 3, ex + 9, py + 4, 0xC0FF6B5E);
			hits.add(ex - 1, py - 1, 11, 10, () -> {
				host.click();
				wp.visible = !wp.visible;
				host.engine().waypointEdited();
			});
			if (hovEye) tooltip.add(I18n.tr(wp.visible ? "map.menu.hide" : "map.menu.show"));
		}
		int px = x + w - 15;
		boolean hovEdit = hov && inside(mx, my, px - 1, py - 1, 11, 10);
		Icons.draw(c, "pencil", px, py, 1, hovEdit ? t.text : t.textDim);
		hits.add(px - 1, py - 1, 11, 10, () -> {
			host.click();
			host.editWaypoint(wp);
		});
		if (hovEdit) tooltip.add(I18n.tr("map.menu.edit"));
	}

	private void tab(Canvas c, Hits hits, int x, int y, int w, String label, boolean active, int mx, int my, final boolean friends) {
		Theme t = Theme.get();
		boolean hov = inside(mx, my, x, y, w, 13);
		c.fill(x, y, x + w, y + 13, active ? ColorMath.withAlpha(t.dustOn, 0x50) : (hov ? 0x30FFFFFF : 0x18FFFFFF));
		if (active) c.fill(x, y + 12, x + w, y + 13, 0xFF000000 | (t.dustOn & 0xFFFFFF));
		String s = c.clip(label, w - 4);
		c.text(s, x + (w - c.textWidth(s)) / 2, y + 3, active ? t.text : t.textDim, false);
		hits.add(x, y, w, 13, () -> {
			if (friendsTab == friends) return;
			host.click();
			setFriendsTab(friends);
		});
	}

	private static boolean isNew(String id) {
		TrsModules m = dev.theredstonee.trsclient.core.intro.IntroGate.modules();
		return m != null && m.clientState.news().isNew(id);
	}

	private static void seen(String id) {
		TrsModules m = dev.theredstonee.trsclient.core.intro.IntroGate.modules();
		if (m != null && m.clientState.news().markSeen(id)) dev.theredstonee.trsclient.core.intro.IntroGate.save(m);
	}

	/** Reiter „Freunde“: Suche, je Freund Kopfzeile (Kopf, Name, live/zuletzt, Ein/Aus, Hinzoomen) und seine Wegpunkte. */
	private void drawFriends(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my, float dt, List<String> tooltip,
			LiveShare live) {
		MapEngine e = host.engine();
		Theme t = Theme.get();
		// Suche (gilt auch hier).
		int fx = x + 6, fy = y + 5, fw = w - 12;
		Redstone.well(c, fx, fy, fw, 16, search.focused() ? t.accent : t.border);
		Icons.draw(c, "search", fx + 4, fy + 4, 1, t.textDim);
		String text = search.text();
		c.text(c.clip(text.isEmpty() && !search.focused() ? I18n.tr("map.list.search") : text, fw - 20), fx + 15, fy + 4,
				text.isEmpty() && !search.focused() ? t.textDim : t.text, false);
		hits.add(fx, fy, fw, 16, () -> search.setFocused(true));

		long now = System.currentTimeMillis();
		String query = text.trim().toLowerCase(java.util.Locale.ROOT);
		friendRows.clear();
		boolean active = live != null && live.active();
		if (active) {
			for (Owner o : live.owners()) {
				boolean ownerMatch = query.isEmpty() || o.name().toLowerCase(java.util.Locale.ROOT).contains(query);
				List<FriendWaypoint> wps = new ArrayList<FriendWaypoint>();
				for (FriendWaypoint fw2 : o.waypoints()) {
					if (ownerMatch || fw2.name.toLowerCase(java.util.Locale.ROOT).contains(query)) wps.add(fw2);
				}
				if (!ownerMatch && wps.isEmpty()) continue;
				friendRows.add(o);
				if (!o.hidden()) friendRows.addAll(wps);
			}
		}

		int ly = y + 26, lh = h - 26 - FOOT_H;
		listRect[0] = x;
		listRect[1] = ly;
		listRect[2] = w;
		listRect[3] = lh;
		maxScroll = Math.max(0, friendRows.size() * ROW_H - lh);
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
		scroll = Anim.approach(scroll, scrollTarget, dt, 0.05f);
		if (Math.abs(scroll - scrollTarget) < 0.5f) scroll = scrollTarget;
		c.fill(x + 1, ly - 1, x + w, ly, 0x60000000);
		if (friendRows.isEmpty()) {
			String key = !active ? "map.friends.off" : live.owners().isEmpty() ? "map.friends.empty" : "map.list.noMatch";
			List<String> lines = dev.theredstonee.trsclient.core.ui.Paint.wrap(c, I18n.tr(key), w - 16);
			int ty = ly + 10;
			for (String l : lines) {
				c.text(l, x + Math.max(6, (w - c.textWidth(l)) / 2), ty, t.textDim, false);
				ty += 10;
			}
		}
		c.flush();
		c.scissor(x + 1, ly, x + w, ly + lh);
		hits.clip(x + 1, ly, w - 1, lh);
		int first = (int) (scroll / ROW_H);
		String dim = WaypointShare.canonicalDimension(e.dimension());
		for (int i = first; i < friendRows.size(); i++) {
			int ry = ly + i * ROW_H - Math.round(scroll);
			if (ry >= ly + lh) break;
			Object row = friendRows.get(i);
			boolean inList = my >= ly && my < ly + lh;
			if (row instanceof Owner) ownerRow(c, hits, (Owner) row, x + 1, ry, w - 1, mx, my, inList, now, live, tooltip, t);
			else friendRow(c, hits, (FriendWaypoint) row, x + 1, ry, w - 1, mx, my, inList, dim, tooltip, t);
		}
		c.flush();
		hits.noClip();
		c.noScissor();
		if (maxScroll > 0) {
			int bh = Math.max(12, lh * lh / (friendRows.size() * ROW_H));
			int by = ly + Math.round((lh - bh) * (scroll / maxScroll));
			c.fill(x + w - 3, by, x + w - 1, by + bh, ColorMath.withAlpha(t.dustOn, 0x90));
		}
		// Fuß: Anzahl Freunde mit Freigabe in dieser Welt.
		int footY = y + h - FOOT_H;
		c.fill(x + 1, footY, x + w, footY + 1, 0x60000000);
		int n = active ? live.owners().size() : 0;
		c.text(c.clip(I18n.tr("map.friends.count", n), w - 12), x + 6, footY + 5, t.textDim, false);
	}

	private void ownerRow(Canvas c, Hits hits, final Owner o, int x, int y, int w, int mx, int my, boolean inList, long now,
			final LiveShare live, List<String> tooltip, Theme t) {
		boolean hov = inList && inside(mx, my, x, y, w, ROW_H);
		c.fill(x, y, x + w, y + ROW_H, hov ? ColorMath.withAlpha(t.dustOn, 0x30) : 0x22FFFFFF);
		FriendPosition p = o.position();
		boolean isLive = LiveShare.live(p, now);
		FriendFaces.head(c, o.uuid, o.name(), x + 10, y + ROW_H / 2f, 10f, 255);
		c.text(c.clip(o.name(), w - 60), x + 20, y + 3, t.text, false);
		String state;
		if (isLive) {
			long age = (now - p.at) / 1000;
			state = age < 5 ? I18n.tr("map.friend.live") : I18n.tr("map.friend.seconds", age);
		} else {
			state = I18n.tr("map.friends.notHere");
		}
		state = I18n.tr("map.friends.waypoints", o.waypoints().size()) + " · " + state;
		c.text(c.clip(state, w - 52), x + 20, y + 12, isLive ? ColorMath.withAlpha(FriendFaces.FRIEND, 0xD0) : ColorMath.withAlpha(t.textDim, 0xB0), false);
		int py = y + 6;
		// Hinzoomen (nur mit Live-Position).
		int zx = x + w - 28;
		boolean hovZoom = hov && inside(mx, my, zx - 1, py - 1, 11, 10);
		Icons.draw(c, "zoom", zx, py, 1, isLive ? (hovZoom ? t.text : t.textDim) : 0x40FFFFFF);
		if (isLive) {
			hits.add(zx - 1, py - 1, 11, 10, () -> {
				host.click();
				host.centerOnFriend(o);
			});
			if (hovZoom) tooltip.add(I18n.tr("map.friends.zoom"));
		}
		// Ein-/Ausblenden.
		int ex = x + w - 15;
		boolean shown = !o.hidden();
		boolean hovEye = hov && inside(mx, my, ex - 1, py - 1, 11, 10);
		Icons.draw(c, "eye", ex, py, 1, shown ? (hovEye ? t.text : t.textDim) : 0x60FFFFFF);
		if (!shown) c.fill(ex - 1, py + 3, ex + 9, py + 4, 0xC0FF6B5E);
		hits.add(ex - 1, py - 1, 11, 10, () -> {
			host.click();
			live.setHidden(o.uuid, shown);
		});
		if (hovEye) tooltip.add(I18n.tr(shown ? "map.friends.hide" : "map.friends.show"));
		hits.add(x, y, w - 32, ROW_H, () -> {
			host.click();
			if (LiveShare.live(o.position(), System.currentTimeMillis())) host.centerOnFriend(o);
		});
	}

	private void friendRow(Canvas c, Hits hits, final FriendWaypoint wp, int x, int y, int w, int mx, int my, boolean inList,
			String dim, List<String> tooltip, Theme t) {
		boolean hov = inList && inside(mx, my, x, y, w, ROW_H);
		if (hov) {
			c.fill(x, y, x + w, y + ROW_H, ColorMath.withAlpha(t.dustOn, 0x38));
			hoveredFriend = wp;
		}
		MapEngine e = host.engine();
		double gs = e.platform() == null ? 2.0 : e.platform().guiScale();
		MinimapRenderer.friendMark(c, e, wp, x + 14, y + ROW_H / 2f, 1f, gs);
		String dist = "";
		if (wp.dimension.equals(dim)) {
			double dx = wp.x + 0.5 - e.playerX(), dz = wp.z + 0.5 - e.playerZ();
			dist = I18n.tr("map.distance", WaypointFilter.distanceText(Math.sqrt(dx * dx + dz * dz)));
		}
		int dw = c.textWidth(dist);
		c.text(c.clip(wp.name, w - 28 - dw - 6), x + 24, y + 3, t.text, false);
		c.text(dist, x + w - 5 - dw, y + 3, t.textDim, false);
		String where = wp.x + ", " + wp.z;
		if (!wp.dimension.equals(dim)) where = WorldMapUi.dimensionName(WaypointShare.localDimension(wp.dimension, e.dimension())) + " · " + where;
		c.text(c.clip(where, w - 24 - 20), x + 24, y + 12, ColorMath.withAlpha(t.textDim, 0xB0), false);
		hits.add(x, y, w, ROW_H, () -> {
			host.click();
			host.centerOnFriendWaypoint(wp);
		});
		// Übernehmen.
		int ax = x + w - 15, py = y + 12;
		boolean hovAdopt = hov && inside(mx, my, ax - 1, py - 1, 11, 10);
		Icons.draw(c, "plus", ax, py, 1, hovAdopt ? FriendFaces.FRIEND : t.textDim);
		hits.add(ax - 1, py - 1, 11, 10, () -> {
			host.click();
			host.adopt(wp);
		});
		if (hovAdopt) tooltip.add(I18n.tr("map.menu.adopt"));
	}

	private void cycleDim(List<String> order, int dir) {
		host.click();
		int i = Math.max(0, order.indexOf(dimFilter));
		setDimFilter(order.get((i + dir + order.size()) % order.size()));
	}

	/** Mausrad über der Liste. */
	boolean scroll(double amount) {
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget - (float) amount * ROW_H * 2));
		return true;
	}

	/** Taste bei aktiver Suche (true = verbraucht; alles wird verbraucht, damit z. B. „M“ die Karte nicht schließt). */
	boolean key(UiKey key) {
		if (key == UiKey.ESCAPE) {
			search.setFocused(false);
			return true;
		}
		if (key == UiKey.ENTER) {
			search.setFocused(false);
			if (friendsTab) {
				for (Object r : friendRows) {
					if (r instanceof FriendWaypoint) {
						host.centerOnFriendWaypoint((FriendWaypoint) r);
						break;
					}
				}
			} else if (!rows.isEmpty()) {
				host.centerOnWaypoint(rows.get(0).waypoint);
			}
			return true;
		}
		search.key(key);
		scrollTarget = 0;
		return true;
	}

	boolean type(char ch) {
		boolean changed = search.type(ch);
		if (changed) scrollTarget = 0;
		return true;
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
