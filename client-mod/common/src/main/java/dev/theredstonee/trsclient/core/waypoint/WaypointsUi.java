package dev.theredstonee.trsclient.core.waypoint;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MapPlatform;
import dev.theredstonee.trsclient.core.map.WaypointFilter;
import dev.theredstonee.trsclient.core.map.WorldMapUi;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.FadeCanvas;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Wegpunkte im Spiel, für alle Versionen gleich: links die Sets dieser Welt (ein-/ausblenden, anlegen, umbenennen,
 * löschen), rechts die Liste mit Suche, Sortierung und Filtern. Bearbeitet wird im selben Dialog wie auf der Weltkarte.
 * Mit {@link #dialogOnly(MapEngine, Host)} zeigt der Bildschirm nur den Dialog für einen neuen Wegpunkt am eigenen Standort.
 */
public final class WaypointsUi extends UiScreen implements WaypointDialog.Host {
	/** Verbindung zum Loader. */
	public interface Host {
		/** Bildschirm schließen (zurück zum vorigen). */
		void closeScreen();

		void playClick();

		/** Taste, mit der die Liste geöffnet wird (schließt wieder)? */
		default boolean isOpenKey(int rawKey) {
			return false;
		}
	}

	private static final int HEAD_H = 22;
	private static final int FOOT_H = 22;
	private static final int ROW_H = 22;
	private static final int SET_ROW_H = 15;
	private static final int SETS_W = 100;

	private final MapEngine e;
	private final Host host;
	private final WaypointDialog dialog;
	private final boolean onlyDialog;
	private final TextInput search = new TextInput(32);
	private final TextInput setName = new TextInput(WaypointStore.MAX_SET_NAME);
	private final WaypointFilter.Query query = new WaypointFilter.Query();
	/** Set, das gerade umbenannt wird; {@link #NEW_SET} = neues. */
	private String renaming;
	private static final String NEW_SET = "\0new";
	private List<WaypointFilter.Row> rows = new ArrayList<WaypointFilter.Row>();
	private float scroll;
	private float setScroll;
	private int maxScroll;
	private final int[] listRect = new int[4];
	private final int[] setsRect = new int[4];
	private final int[] menuRect = new int[4];
	private Waypoint menuOf;
	private int menuX, menuY;
	private String note;
	private long noteUntil;
	private final List<String> tip = new ArrayList<String>();

	public WaypointsUi(MapEngine engine, Host host) {
		this(engine, host, false);
	}

	private WaypointsUi(MapEngine engine, Host host, boolean onlyDialog) {
		this.e = engine;
		this.host = host;
		this.onlyDialog = onlyDialog;
		this.dialog = new WaypointDialog(engine, this);
		query.sort = WaypointMenu.sort(engine.modules());
		dev.theredstonee.trsclient.core.i18n.I18n.refresh();
	}

	/** Nur der Dialog „Neuer Wegpunkt“ am eigenen Standort (Taste im Spiel). */
	public static WaypointsUi dialogOnly(MapEngine engine, Host host) {
		WaypointsUi ui = new WaypointsUi(engine, host, true);
		ui.openNewHere();
		return ui;
	}

	/** Neuer Wegpunkt am eigenen Standort. */
	public void openNewHere() {
		MapPlatform p = e.platform();
		if (p == null || !p.inWorld()) return;
		dialog.openNew(suggestedName(), (int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()), p.dimension(),
				WaypointDialog.COLORS[e.allWaypoints().size() % WaypointDialog.COLORS.length]);
	}

	private String suggestedName() {
		return I18n.tr("waypoint.suggest", e.allWaypoints().size() + 1);
	}

	private WaypointStore store() {
		MapPlatform p = e.platform();
		return p == null ? null : p.waypoints();
	}

	private String worldKey() {
		MapPlatform p = e.platform();
		String k = p == null ? "" : p.waypointWorldKey();
		return k == null ? "" : k;
	}

	@Override
	public void click() {
		host.playClick();
	}

	@Override
	public void dialogClosed() {
		if (onlyDialog) requestClose();
	}

	/** Im Dialog-Modus bleibt das Spiel sichtbar. */
	@Override
	public boolean overlay() {
		return onlyDialog;
	}

	// --- Zeichnen ---

	@Override
	protected void draw(Canvas raw, int w, int h, int mx, int my, float dt) {
		Canvas c = FadeCanvas.of(raw, alpha());
		Theme t = Theme.get();
		tip.clear();
		if (!onlyDialog) panel(c, w, h, mx, my, dt, t);
		if (dialog.isOpen()) {
			c.push();
			c.raise(300);
			dialog.draw(c, hits, w, h, mx, my);
			c.pop();
		} else if (onlyDialog) {
			if (!isClosing()) requestClose();
		}
	}

	private void panel(Canvas c, int w, int h, int mx, int my, float dt, Theme t) {
		int pw = Math.min(w - 12, 560), ph = Math.min(h - 12, 340);
		int px = (w - pw) / 2, py = (h - ph) / 2;
		c.push();
		c.raise(200);
		Redstone.window(c, px, py, pw, ph);
		WaypointStore s = store();
		String key = worldKey();

		// Kopf: Titel, Welt, Schließen.
		Icons.draw(c, "compass", px + 8, py + 7, 1, 0xFF000000 | (t.dustOn & 0xFFFFFF));
		c.text(I18n.tr("waypoints.title"), px + 20, py + 7, t.text, false);
		int closeX = px + pw - 20;
		Redstone.iconButton(c, closeX, py + 3, 16, "close", inside(mx, my, closeX, py + 3, 16, 16), false);
		hits.add(closeX, py + 3, 16, 16, () -> {
			host.playClick();
			requestClose();
		});
		String world = key.isEmpty() ? I18n.tr("waypoints.noWorld") : key.substring(key.indexOf(':') + 1);
		int titleEnd = px + 26 + c.textWidth(I18n.tr("waypoints.title"));
		Paint.textRight(c, c.clip(world, Math.max(20, closeX - titleEnd - 8)), closeX - 6, py + 7, t.textDim, false);
		c.fill(px + 1, py + HEAD_H - 1, px + pw - 1, py + HEAD_H, ColorMath.withAlpha(t.dustOn, 0x90));

		int bodyY = py + HEAD_H, bodyH = ph - HEAD_H - FOOT_H;
		if (s != null && !key.isEmpty()) {
			List<Waypoint> all = s.all(key);
			setsColumn(c, s, key, all, px + 1, bodyY, SETS_W, bodyH, mx, my, dt, t);
			c.fill(px + SETS_W + 1, bodyY, px + SETS_W + 2, bodyY + bodyH, 0x60000000);
			list(c, s, key, all, px + SETS_W + 2, bodyY, pw - SETS_W - 3, bodyH, mx, my, dt, t);
		} else {
			Paint.textCentered(c, I18n.tr("waypoints.noWorld"), px + pw / 2, bodyY + bodyH / 2, t.textDim, false);
		}

		// Fuß: anlegen, Anzahl, Hinweis.
		int fy = py + ph - FOOT_H;
		c.fill(px + 1, fy, px + pw - 1, fy + 1, 0x60000000);
		String add = I18n.tr("waypoints.addHere");
		int aw = c.textWidth(add) + 14;
		boolean canAdd = e.platform() != null && e.platform().inWorld();
		Redstone.button(c, px + 6, fy + 3, aw, 16, add, canAdd, canAdd && inside(mx, my, px + 6, fy + 3, aw, 16));
		if (canAdd) {
			hits.add(px + 6, fy + 3, aw, 16, () -> {
				host.playClick();
				openNewHere();
			});
		}
		long now = System.currentTimeMillis();
		if (note != null && now < noteUntil) {
			c.text(c.clip(note, pw - aw - 24), px + aw + 14, fy + 7, 0xFFFFB02E, false);
		} else {
			c.text(c.clip(I18n.tr("map.list.count", rows.size()), pw - aw - 24), px + aw + 14, fy + 7, t.textDim, false);
		}
		if (menuOf != null) rowMenu(c, w, h, mx, my, t);
		tooltip(c, w, h, mx, my, t);
		c.pop();
	}

	// --- Sets ---

	private void setsColumn(Canvas c, final WaypointStore s, final String key, List<Waypoint> all, int x, int y, int w, int h, int mx, int my, float dt, Theme t) {
		setsRect[0] = x;
		setsRect[1] = y;
		setsRect[2] = w;
		setsRect[3] = h;
		List<WaypointSet> sets = s.sets(key);
		c.text(c.clip(I18n.tr("waypoints.sets"), w - 24), x + 6, y + 5, t.textDim, false);
		Redstone.iconButton(c, x + w - 17, y + 2, 14, "plus", inside(mx, my, x + w - 17, y + 2, 14, 14), false);
		hits.add(x + w - 17, y + 2, 14, 14, () -> {
			host.playClick();
			beginRename(NEW_SET, "");
		});
		if (inside(mx, my, x + w - 17, y + 2, 14, 14)) tip.add(I18n.tr("waypoints.newSet"));
		int ly = y + 18, lh = h - 18;
		int total = (sets.size() + 1 + (NEW_SET.equals(renaming) ? 1 : 0)) * SET_ROW_H;
		int max = Math.max(0, total - lh);
		setScroll = Math.max(0, Math.min(max, setScroll));
		c.flush();
		c.scissor(x, ly, x + w, ly + lh);
		hits.clip(x, ly, w, lh);
		int ry = ly - Math.round(setScroll);
		setRow(c, null, I18n.tr("waypoints.allSets"), all.size(), x, ry, w, mx, my, t, s, key);
		ry += SET_ROW_H;
		for (WaypointSet set : sets) {
			int count = 0;
			for (Waypoint wp : all) if (set.id.equals(wp.setId())) count++;
			setRow(c, set, set.label(), count, x, ry, w, mx, my, t, s, key);
			ry += SET_ROW_H;
		}
		if (NEW_SET.equals(renaming)) renameField(c, x, ry, w, t);
		c.flush();
		hits.noClip();
		c.noScissor();
	}

	private void setRow(Canvas c, final WaypointSet set, String label, int count, int x, int y, int w, int mx, int my, Theme t, final WaypointStore s, final String key) {
		final String id = set == null ? WaypointFilter.ALL : set.id;
		boolean selected = id.equals(query.set);
		boolean hov = inside(mx, my, x, y, w, SET_ROW_H) && inside(mx, my, setsRect[0], setsRect[1], setsRect[2], setsRect[3]);
		if (set != null && set.id.equals(renaming)) {
			renameField(c, x, y, w, t);
			return;
		}
		if (selected || hov) c.fill(x, y, x + w, y + SET_ROW_H, ColorMath.withAlpha(t.dustOn, selected ? 0x48 : 0x28));
		boolean shown = set == null || set.visible;
		c.text(c.clip(label, w - 32 - (selected && set != null && !set.builtIn() ? 24 : 0)), x + 6, y + 4, shown ? t.text : t.textDim, false);
		hits.add(x, y, w, SET_ROW_H, () -> {
			host.playClick();
			query.set = id;
			scroll = 0;
		});
		if (set == null) {
			Paint.textRight(c, Integer.toString(count), x + w - 5, y + 4, t.textDim, false);
			return;
		}
		int ex = x + w - 13;
		boolean hovEye = hov && inside(mx, my, ex - 1, y + 3, 11, 10);
		Icons.draw(c, "eye", ex, y + 4, 1, shown ? (hovEye ? t.text : t.textDim) : 0x60FFFFFF);
		if (!shown) c.fill(ex - 1, y + 7, ex + 9, y + 8, 0xC0FF6B5E);
		hits.add(ex - 1, y + 3, 11, 10, () -> {
			host.playClick();
			s.setSetVisible(key, set.id, !set.visible);
			e.waypointEdited();
		});
		if (hovEye) tip.add(I18n.tr(set.visible ? "waypoints.hideSet" : "waypoints.showSet"));
		int right = ex - 3;
		if (selected && !set.builtIn()) {
			int dx = ex - 12, rx = ex - 23;
			boolean hovDel = hov && inside(mx, my, dx - 1, y + 3, 11, 10);
			boolean hovRen = hov && inside(mx, my, rx - 1, y + 3, 11, 10);
			Icons.draw(c, "trash", dx, y + 4, 1, hovDel ? 0xFFFF6B5E : t.textDim);
			Icons.draw(c, "pencil", rx, y + 4, 1, hovRen ? t.text : t.textDim);
			hits.add(dx - 1, y + 3, 11, 10, () -> {
				host.playClick();
				s.removeSet(key, set.id);
				query.set = WaypointFilter.ALL;
				e.waypointEdited();
			});
			hits.add(rx - 1, y + 3, 11, 10, () -> {
				host.playClick();
				beginRename(set.id, set.label());
			});
			if (hovDel) tip.add(I18n.tr("waypoints.deleteSet"));
			if (hovRen) tip.add(I18n.tr("waypoints.renameSet"));
		} else {
			Paint.textRight(c, Integer.toString(count), right, y + 4, t.textDim, false);
		}
	}

	private void beginRename(String id, String current) {
		renaming = id;
		setName.setText(current);
		setName.setFocused(true);
		search.setFocused(false);
	}

	private void renameField(Canvas c, int x, int y, int w, Theme t) {
		Redstone.well(c, x + 3, y + 1, w - 6, 13, t.accent);
		boolean empty = setName.isEmpty();
		c.text(empty ? c.clip(I18n.tr("waypoint.setName"), w - 12) : c.clip(setName.text(), w - 12), x + 6, y + 4, empty ? t.textDim : t.text, false);
		hits.add(x + 3, y + 1, w - 6, 13, () -> setName.setFocused(true));
	}

	private void commitRename() {
		WaypointStore s = store();
		String key = worldKey();
		if (s != null && !key.isEmpty() && !setName.isEmpty()) {
			if (NEW_SET.equals(renaming)) {
				WaypointSet created = s.addSet(key, setName.text());
				if (created != null) query.set = created.id;
			} else {
				s.renameSet(key, renaming, setName.text());
			}
			e.waypointEdited();
		}
		cancelRename();
	}

	private void cancelRename() {
		renaming = null;
		setName.setFocused(false);
	}

	// --- Liste ---

	private void list(Canvas c, final WaypointStore s, final String key, List<Waypoint> all, int x, int y, int w, int h, int mx, int my, float dt, Theme t) {
		TrsModules m = e.modules();
		MapPlatform platform = e.platform();
		String playerDim = platform.dimension();
		List<String> dims = WaypointFilter.dimensions(all, playerDim);
		if (!dims.contains(query.dimension)) query.dimension = WaypointFilter.ALL;
		if (!WaypointFilter.ALL.equals(query.set)) {
			boolean known = false;
			for (WaypointSet set : s.sets(key)) if (set.id.equals(query.set)) known = true;
			if (!known) query.set = WaypointFilter.ALL;
		}
		query.text = search.text();
		rows = WaypointFilter.apply(all, query, wp -> s.shown(key, wp), playerDim, platform.x(), platform.z());

		// Werkzeugleiste: Suche und drei Wahlknöpfe.
		int tx = x + 5, ty = y + 4, bw = 54, dimW = 74;
		int searchW = Math.max(60, w - 10 - 2 * (bw + 3) - dimW - 3);
		Redstone.well(c, tx, ty, searchW, 15, search.focused() ? t.accent : t.border);
		Icons.draw(c, "search", tx + 3, ty + 4, 1, t.textDim);
		String text = search.text();
		if (text.isEmpty() && !search.focused()) {
			c.text(c.clip(I18n.tr("map.list.search"), searchW - 16), tx + 14, ty + 4, t.textDim, false);
		} else {
			c.text(c.clip(text, searchW - 16), tx + 14, ty + 4, t.text, false);
			if (search.focused() && (System.currentTimeMillis() / 500) % 2 == 0) {
				int cx = tx + 14 + c.textWidth(text.substring(0, Math.min(search.cursor(), text.length())));
				if (cx < tx + searchW - 2) c.fill(cx, ty + 2, cx + 1, ty + 13, t.text);
			}
		}
		hits.add(tx, ty, searchW, 15, () -> {
			search.setFocused(true);
			cancelRename();
		});
		int bx = tx + searchW + 3;
		choice(c, bx, ty, bw, I18n.tr("setting.waypoints.sort." + query.sort.name().toLowerCase(java.util.Locale.ROOT)), mx, my, t, I18n.tr("waypoints.sortBy"), () -> {
			WaypointSort[] all2 = WaypointSort.values();
			query.sort = all2[(query.sort.ordinal() + 1) % all2.length];
			m.waypointSort.set(query.sort);
			saveSettings();
		});
		bx += bw + 3;
		choice(c, bx, ty, bw, I18n.tr("waypoints.kind." + query.kind.name().toLowerCase(java.util.Locale.ROOT)), mx, my, t, I18n.tr("waypoints.filterKind"), () -> {
			WaypointFilter.Kind[] kinds = WaypointFilter.Kind.values();
			query.kind = kinds[(query.kind.ordinal() + 1) % kinds.length];
			scroll = 0;
		});
		bx += bw + 3;
		final List<String> order = dims;
		String dimLabel = WaypointFilter.ALL.equals(query.dimension) ? I18n.tr("map.list.allDims") : WorldMapUi.dimensionName(query.dimension);
		choice(c, bx, ty, dimW, dimLabel, mx, my, t, I18n.tr("waypoints.filterDim"), () -> {
			int i = Math.max(0, order.indexOf(query.dimension));
			query.dimension = order.get((i + 1) % order.size());
			scroll = 0;
		});

		int ly = y + 23, lh = h - 23;
		listRect[0] = x;
		listRect[1] = ly;
		listRect[2] = w;
		listRect[3] = lh;
		maxScroll = Math.max(0, rows.size() * ROW_H - lh);
		scroll = Math.max(0, Math.min(maxScroll, scroll));
		c.fill(x, ly - 1, x + w, ly, 0x60000000);
		if (rows.isEmpty()) {
			String msg = all.isEmpty() ? I18n.tr("waypoints.empty", keyName(m)) : I18n.tr("map.list.noMatch");
			List<String> lines = Paint.wrap(c, msg, w - 20);
			int my2 = ly + 14;
			for (String line : lines) {
				Paint.textCentered(c, line, x + w / 2, my2, t.textDim, false);
				my2 += 10;
			}
		}
		c.flush();
		c.scissor(x, ly, x + w, ly + lh);
		hits.clip(x, ly, w, lh);
		int first = (int) (scroll / ROW_H);
		Waypoint target = s.target(key);
		for (int i = first; i < rows.size(); i++) {
			int ry = ly + i * ROW_H - Math.round(scroll);
			if (ry >= ly + lh) break;
			row(c, rows.get(i), target, s, key, x, ry, w, mx, my, ly, lh, t);
		}
		c.flush();
		hits.noClip();
		c.noScissor();
		if (maxScroll > 0) {
			int barH = Math.max(12, lh * lh / (rows.size() * ROW_H));
			int barY = ly + Math.round((lh - barH) * (scroll / maxScroll));
			c.fill(x + w - 3, barY, x + w - 1, barY + barH, ColorMath.withAlpha(t.dustOn, 0x90));
		}
	}

	private String keyName(TrsModules m) {
		MapPlatform p = e.platform();
		String name = p == null ? null : p.keyName(m.waypointAddKey.get());
		return name == null ? "—" : name;
	}

	private void saveSettings() {
		try {
			dev.theredstonee.trsclient.core.config.ConfigStore store = dev.theredstonee.trsclient.core.config.ConfigStore.active();
			if (store != null) store.saveLater(e.modules().registry);
		} catch (java.io.IOException | RuntimeException ex) {
			// nur die Sortierung – nicht schlimm
		}
	}

	private void choice(Canvas c, int x, int y, int w, String label, int mx, int my, Theme t, String tooltip, final Runnable action) {
		boolean hov = inside(mx, my, x, y, w, 15);
		Redstone.button(c, x, y, w, 15, "", false, hov);
		String clipped = c.clip(label, w - 6);
		c.text(clipped, x + (w - c.textWidth(clipped)) / 2, y + 4, t.text, false);
		hits.add(x, y, w, 15, () -> {
			host.playClick();
			action.run();
		});
		if (hov) tip.add(tooltip);
	}

	private void row(Canvas c, final WaypointFilter.Row r, Waypoint target, final WaypointStore s, final String key, int x, int y, int w, int mx, int my, int ly, int lh, Theme t) {
		final Waypoint wp = r.waypoint;
		boolean inList = my >= ly && my < ly + lh;
		boolean hov = inList && inside(mx, my, x, y, w, ROW_H);
		if (hov) c.fill(x, y, x + w, y + ROW_H, ColorMath.withAlpha(t.dustOn, 0x30));
		boolean shown = s.shown(key, wp);
		boolean isTarget = wp == target;
		WaypointBadge.draw(c, wp, x + 12, y + ROW_H / 2f, 1f, shown ? 255 : 0x70);
		// Rechts die Knöpfe: Ziel, Ein/Aus, Menü.
		int ix = x + w - 13, iy = y + 7;
		boolean hovMenu = hov && inside(mx, my, ix - 1, iy - 1, 11, 10);
		Icons.draw(c, "menu", ix, iy, 1, hovMenu ? t.text : t.textDim);
		hits.add(ix - 1, iy - 1, 11, 10, () -> {
			host.playClick();
			openRowMenu(wp, mx, my);
		});
		ix -= 13;
		boolean hovEye = hov && inside(mx, my, ix - 1, iy - 1, 11, 10);
		Icons.draw(c, "eye", ix, iy, 1, wp.visible ? (hovEye ? t.text : t.textDim) : 0x60FFFFFF);
		if (!wp.visible) c.fill(ix - 1, iy + 3, ix + 9, iy + 4, 0xC0FF6B5E);
		hits.add(ix - 1, iy - 1, 11, 10, () -> {
			host.playClick();
			wp.visible = !wp.visible;
			e.waypointEdited();
		});
		if (hovEye) tip.add(I18n.tr(wp.visible ? "map.menu.hide" : "map.menu.show"));
		ix -= 13;
		boolean hovTarget = hov && inside(mx, my, ix - 1, iy - 1, 11, 10);
		Icons.draw(c, "crosshair", ix, iy, 1, isTarget ? (0xFF000000 | (t.dustOn & 0xFFFFFF)) : hovTarget ? t.text : t.textDim);
		hits.add(ix - 1, iy - 1, 11, 10, () -> {
			host.playClick();
			note(WaypointMenu.run(isTarget ? WaypointMenu.UNTARGET : WaypointMenu.TARGET, e, wp));
		});
		if (hovTarget) tip.add(I18n.tr(isTarget ? "map.menu.untarget" : "map.menu.target"));

		// Text: Name + Entfernung, darunter Set/Dimension/Koordinaten.
		int textX = x + 24, textW = ix - textX - 6;
		String dist = WaypointFilter.distanceText(r.distance);
		if (!Double.isNaN(r.distance)) dist = (r.converted ? "≈" : "") + I18n.tr("map.distance", dist);
		int dw = c.textWidth(dist);
		String name = wp.name;
		c.text(c.clip(name, textW - dw - 6), textX, y + 3, shown ? t.text : t.textDim, false);
		c.text(dist, textX + textW - dw, y + 3, t.textDim, false);
		String where = wp.x + " " + wp.y + " " + wp.z;
		if (wp.dimension != null && !wp.dimension.isEmpty() && !wp.dimension.equals(e.platform().dimension())) where = WorldMapUi.dimensionName(wp.dimension) + " · " + where;
		if (wp.death && wp.created > 0) where = age(wp.created) + " · " + where;
		c.text(c.clip(where, textW), textX, y + 12, ColorMath.withAlpha(t.textDim, 0xB0), false);
		hits.add(x, y, Math.max(0, ix - x - 2), ROW_H, () -> {
			host.playClick();
			dialog.openEdit(wp);
		});
		hits.add(x, y, w, ROW_H, 1, () -> openRowMenu(wp, mx, my));
	}

	/** Wie lange ein Todespunkt her ist: „vor 5 min“. */
	private static String age(long created) {
		long min = Math.max(0, (System.currentTimeMillis() - created) / 60_000L);
		if (min < 60) return I18n.tr("waypoints.ageMinutes", min);
		long hours = min / 60;
		if (hours < 48) return I18n.tr("waypoints.ageHours", hours);
		return I18n.tr("waypoints.ageDays", hours / 24);
	}

	private void note(String text) {
		if (text == null) return;
		note = text;
		noteUntil = System.currentTimeMillis() + 4000;
	}

	// --- Menü an einer Zeile ---

	private void openRowMenu(Waypoint w, int x, int y) {
		menuOf = w;
		menuX = x;
		menuY = y;
	}

	private void rowMenu(Canvas c, int width, int height, int mx, int my, Theme t) {
		final Waypoint wp = menuOf;
		List<String> items = WaypointMenu.forWaypoint(e, wp);
		int w = 0;
		for (String id : items) w = Math.max(w, c.textWidth(I18n.tr("map.menu." + id)));
		w += 24;
		int h = items.size() * 14 + 16;
		int x = Math.min(menuX, width - w - 4), y = Math.min(menuY, height - h - 4);
		menuRect[0] = x;
		menuRect[1] = y;
		menuRect[2] = w;
		menuRect[3] = h;
		c.flush();
		c.push();
		c.raise(50);
		Redstone.window(c, x, y, w, h);
		c.text(c.clip(wp.name, w - 8), x + 4, y + 3, t.textDim, false);
		for (int i = 0; i < items.size(); i++) {
			final String id = items.get(i);
			int iy = y + 14 + i * 14;
			boolean hov = mx >= x && mx < x + w && my >= iy && my < iy + 14;
			if (hov) c.fill(x + 1, iy, x + w - 1, iy + 14, ColorMath.withAlpha(t.dustOn, 0x50));
			c.text(I18n.tr("map.menu." + id), x + 8, iy + 3, WaypointMenu.DELETE.equals(id) ? 0xFFFF6B5E : t.text, false);
			hits.add(x, iy, w, 14, () -> {
				host.playClick();
				menuOf = null;
				menuAction(id, wp);
			});
		}
		c.pop();
	}

	private void menuAction(String id, Waypoint wp) {
		if (WaypointMenu.EDIT.equals(id)) {
			dialog.openEdit(wp);
		} else if (WaypointMenu.SHARE.equals(id)) {
			WaypointShare.Result r = WaypointShare.shareLocal(wp);
			if (r != WaypointShare.Result.OK) note(I18n.tr(r.key()));
		} else {
			note(WaypointMenu.run(id, e, wp));
			// Der Befehl ist unterwegs: schließen, damit man das Ergebnis sieht (der Server entscheidet).
			if (WaypointMenu.TELEPORT.equals(id)) requestClose();
		}
	}

	private void tooltip(Canvas c, int w, int h, int mx, int my, Theme t) {
		if (tip.isEmpty() || menuOf != null || dialog.isOpen()) return;
		String s = tip.get(0);
		int tw = c.textWidth(s);
		int x = Math.min(mx + 8, w - tw - 8), y = Math.max(2, my - 14);
		c.flush();
		c.push();
		c.raise(50);
		Redstone.block(c, x - 3, y - 2, tw + 6, 13, 0xF0141217);
		Redstone.frame(c, x - 3, y - 2, tw + 6, 13, ColorMath.withAlpha(t.dustOn, 0xA0));
		c.text(s, x, y + 1, t.text, false);
		c.pop();
	}

	// --- Eingaben ---

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (dialog.isOpen()) {
			if (dialog.clickOutside(mx, my)) return true;
			return super.mouseClicked(mx, my, button);
		}
		if (menuOf != null) {
			if (inside(mx, my, menuRect[0], menuRect[1], menuRect[2], menuRect[3])) return super.mouseClicked(mx, my, button);
			menuOf = null;
			if (button != 1) return true;
		}
		search.setFocused(false);
		if (renaming != null) {
			if (!setName.isEmpty()) commitRename();
			else cancelRename();
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (dialog.isOpen() || amount == 0) return false;
		menuOf = null;
		if (inside(mx, my, setsRect[0], setsRect[1], setsRect[2], setsRect[3])) {
			setScroll -= (float) amount * SET_ROW_H * 2;
			return true;
		}
		scroll = Math.max(0, Math.min(maxScroll, scroll - (float) amount * ROW_H * 2));
		return true;
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (dialog.isOpen()) return dialog.key(key, shift);
		if (renaming != null) {
			if (key == UiKey.ESCAPE) cancelRename();
			else if (key == UiKey.ENTER) commitRename();
			else setName.key(key);
			return true;
		}
		if (search.focused()) {
			if (key == UiKey.ESCAPE || key == UiKey.ENTER) search.setFocused(false);
			else search.key(key);
			scroll = 0;
			return true;
		}
		if (key == UiKey.ESCAPE) {
			if (menuOf != null) {
				menuOf = null;
				return true;
			}
			requestClose();
			return true;
		}
		if (host.isOpenKey(rawKey)) {
			requestClose();
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(char ch) {
		if (dialog.isOpen()) return dialog.type(ch);
		if (renaming != null) return setName.type(ch);
		if (search.focused()) {
			scroll = 0;
			return search.type(ch);
		}
		return false;
	}

	@Override
	protected void onClosed() {
		host.closeScreen();
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	// --- Selbsttest ---

	/** Dialog für diesen Wegpunkt öffnen. */
	public void testEdit(Waypoint w) {
		dialog.openEdit(w);
	}

	/** Filter setzen (null = lassen). */
	public void testFilter(String text, String set, WaypointSort sort) {
		if (text != null) search.setText(text);
		if (set != null) query.set = set;
		if (sort != null) query.sort = sort;
	}

	/** Zeilen wie gerade angezeigt, Namen. */
	public List<String> testRows() {
		List<String> out = new ArrayList<String>();
		for (WaypointFilter.Row r : rows) out.add(r.waypoint.name);
		return out;
	}

	/** Menü einer Zeile öffnen. */
	public void testRowMenu(Waypoint w, int x, int y) {
		openRowMenu(w, x, y);
	}
}
