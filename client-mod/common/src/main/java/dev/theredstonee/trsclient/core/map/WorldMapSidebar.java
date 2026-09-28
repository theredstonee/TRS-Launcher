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
import dev.theredstonee.trsclient.core.waypoint.Waypoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Wegpunkt-Liste rechts auf der Weltkarte (ein-/ausklappbar): Suche, Filter nach Dimension, je Wegpunkt Farbe, Name,
 * Dimension/Koordinaten, Entfernung und Ein/Aus-Schalter; Klick zentriert die Karte darauf, der Stift öffnet den
 * Wegpunkt-Dialog der Karte (dort auch Löschen), „+“ legt einen neuen in der Kartenmitte an.
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
	}

	static final int WIDTH = 168;
	static final int ROW_H = 22;
	private static final int HEAD_H = 42;
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
	private List<WaypointFilter.Row> rows = new ArrayList<WaypointFilter.Row>();

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

	void draw(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my, float dt, List<String> tooltip) {
		MapEngine e = host.engine();
		Theme t = Theme.get();
		rect[0] = x;
		rect[1] = y;
		rect[2] = w;
		rect[3] = h;
		hovered = null;
		c.fill(x, y, x + w, y + h, 0xEC141217);
		c.fill(x, y, x + 1, y + h, ColorMath.withAlpha(t.dustOn, 0xB0));
		// Klicks auf leere Flächen der Leiste nicht zur Karte durchlassen.
		hits.add(x, y, w, h, () -> search.setFocused(false));

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
		int ly = y + HEAD_H, lh = h - HEAD_H - FOOT_H;
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
		c.text(c.clip(where, w - 24 - 30), x + 20, y + 12, ColorMath.withAlpha(t.textDim, 0xB0), false);
		hits.add(x, y, w, ROW_H, () -> {
			host.click();
			host.centerOnWaypoint(wp);
		});
		int ex = x + w - 28, py = y + 12;
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
			if (!rows.isEmpty()) host.centerOnWaypoint(rows.get(0).waypoint);
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
