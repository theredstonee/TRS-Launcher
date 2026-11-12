package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.i18n.I18n;
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
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.core.waypoint.WaypointBadge;
import dev.theredstonee.trsclient.core.waypoint.WaypointDialog;
import dev.theredstonee.trsclient.core.waypoint.WaypointMenu;
import dev.theredstonee.trsclient.core.waypoint.WaypointShare;
import dev.theredstonee.trsclient.core.waypoint.WaypointTeleport;
import dev.theredstonee.trsclient.core.waypoint.live.FriendFaces;
import dev.theredstonee.trsclient.core.waypoint.live.FriendPosition;
import dev.theredstonee.trsclient.core.waypoint.live.FriendWaypoint;
import dev.theredstonee.trsclient.core.waypoint.live.LiveShare;
import dev.theredstonee.trsclient.core.waypoint.live.Owner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Vollbild-Weltkarte: alles Erkundete dieser Welt (auch von der Platte), flüssig ziehen (mit Schwung) und stufenlos
 * zum Mauszeiger zoomen, Koordinaten unter dem Zeiger (im Nether/in der Oberwelt auch umgerechnet), auf den Spieler
 * zentrieren, Wegpunkte per Rechtsklick anlegen/bearbeiten/löschen, Wegpunkt-Liste mit Suche, andere Dimensionen
 * ansehen, als PNG exportieren; Spieler und Kreaturen wie auf der Minimap. Nah: ein Texel je Block, weiter weg
 * Übersichts- bzw. Weitkacheln ({@link WorldMapTiles}).
 */
public final class WorldMapUi extends UiScreen implements WorldMapSidebar.Host, WaypointDialog.Host {
	/** Verbindung zum Loader. */
	public interface Host {
		void closeScreen();

		void playClick();

		/** Ist diese Taste die Weltkarten-Taste (schließt wieder)? */
		boolean isMapKey(int rawKey);
	}

	/** Grenzen des Zooms in Bildschirmpixeln je Block. */
	static final float MIN_SCREEN_PX = 1f / 32f;
	static final float MAX_SCREEN_PX = 16f;
	/**
	 * Zoomstufen in Bildschirmpixeln je Block (für „stufenlos“ aus) – ganzzahlige Vielfache bzw. Teiler, damit jeder
	 * Block gleich breit ist (kein Flimmern beim Ziehen). Die GUI-Skalierung wird herausgerechnet.
	 */
	static final float[] LADDER = {1f / 32f, 1f / 16f, 0.125f, 0.25f, 0.5f, 1f, 2f, 3f, 4f, 6f, 8f, 12f, 16f};
	static final int BAR_H = 22;
	static final int[] COLORS = WaypointDialog.COLORS;

	private final MapEngine e;
	private final Host host;
	private final MapCamera cam = new MapCamera();
	private final WorldMapTiles tiles = new WorldMapTiles();
	private final WorldMapTiles tilesUnder = new WorldMapTiles();
	private final WorldMapSidebar sidebar = new WorldMapSidebar(this);
	private boolean follow = true;
	private boolean surfaceOnly;
	private int step;
	private boolean sidebarOpen;
	private int width, height;
	private int mouseX, mouseY;

	// Andere Dimension ansehen (null = die, in der man ist).
	private String viewDim;
	private MapLayer foreign;
	private MapDimensions.DimInfo foreignDim;
	private MapDimensions.LayerInfo foreignLayer;
	private List<MapDimensions.DimInfo> dims = new ArrayList<MapDimensions.DimInfo>();
	private long dimsAt;
	private boolean dimsLoading;
	private long lastForeignMaintain;
	private String worldOpened;

	// Kontextmenü (Rechtsklick).
	private boolean menuOpen;
	private int menuX, menuY;
	private int menuBlockX, menuBlockZ;
	private Waypoint menuWaypoint;
	/** Rechtsklick auf einen Wegpunkt eines Freundes (Live-Teilen). */
	private FriendWaypoint menuFriend;
	/** Hervorgehobener Wegpunkt eines Freundes (Liste „Freunde“ angeklickt). */
	private FriendWaypoint pulseFriend;
	private final int[] menuRect = new int[4];
	// Aufklappmenüs oben: Dimension, Export.
	private boolean dimMenuOpen;
	private boolean exportMenuOpen;
	private final int[] popupRect = new int[4];
	private int popupAnchorX;

	/** Wegpunkt-Dialog (derselbe wie in der Wegpunkt-Liste im Spiel). */
	private final WaypointDialog dialog;

	private final double[] point = new double[2];
	private final List<String> hover = new ArrayList<String>();
	/** Kurz hervorgehobener Wegpunkt (Klick in der Liste). */
	private Waypoint pulse;
	private long pulseUntil;

	/** Beim nächsten Öffnen hierhin zentrieren (Wegpunkt-Karte „Anzeigen“) – nur Spiel-Thread. */
	private static Focus pendingFocus;

	/** Markierter Punkt (Wegpunkt-Karte aus dem Chat): Name, Block x/z. */
	private static final class Focus {
		final String name;
		final int x;
		final int z;
		final int color;

		Focus(String name, int x, int z, int color) {
			this.name = name;
			this.x = x;
			this.z = z;
			this.color = color;
		}
	}

	private Focus focus;

	/** Die nächste geöffnete Weltkarte zentriert auf (x, z) und markiert den Punkt. */
	public static void requestFocus(String name, int x, int z, int color) {
		pendingFocus = new Focus(name == null ? "" : name, x, z, color & 0xFFFFFF);
	}

	public WorldMapUi(MapEngine engine, Host host) {
		this.e = engine;
		this.host = host;
		this.dialog = new WaypointDialog(engine, this);
		double gs = guiScale();
		cam.setLimits((float) (MIN_SCREEN_PX / gs), (float) (MAX_SCREEN_PX / gs));
		step = ladderIndex(Math.max(2f, engine.modules().minimapZoom.get().pixelsPerBlock()));
		cam.setScaleNow((float) (LADDER[step] / gs));
		cam.center(engine.playerX(), engine.playerZ());
		sidebarOpen = engine.modules().worldMapList.get();
		engine.setWorldMapOpen(true);
		Focus f = pendingFocus;
		pendingFocus = null;
		if (f != null) {
			focus = f;
			centerOn(f.x + 0.5, f.z + 0.5);
		}
	}

	/** Karte auf einen Punkt zentrieren (folgt dem Spieler nicht mehr). */
	public void centerOn(double x, double z) {
		follow = false;
		cam.center(x, z);
	}

	// --- Ebene und Dimension ---

	/** Dimension, die die Karte gerade zeigt. */
	public String viewDimension() {
		return viewDim == null ? e.dimension() : viewDim;
	}

	/** Zeigt die Karte eine andere Dimension als die, in der man ist? */
	public boolean viewingOther() {
		return viewDim != null && foreign != null;
	}

	private MapLayer layer() {
		if (viewingOther()) return foreign;
		// Die Weltkarte folgt der Ebene der Minimap (Höhle bzw. Innenansicht); per Knopf zurück zur Oberfläche.
		if (!surfaceOnly && e.layeredView()) return e.viewLayer();
		return e.surfaceLayer();
	}

	/** Gespeicherte Dimensionen dieser Welt – plus die aktuelle, auch wenn noch nichts gespeichert ist. */
	List<String> dimensionChoices() {
		List<String> out = new ArrayList<String>();
		for (MapDimensions.DimInfo d : dims) out.add(d.id);
		String cur = e.dimension();
		if (cur != null && !cur.isEmpty() && !"?".equals(cur) && !out.contains(cur)) out.add(cur);
		java.util.Collections.sort(out, (a, b) -> {
			int ka = MapDimensions.kind(a), kb = MapDimensions.kind(b);
			return ka != kb ? Integer.compare(ka, kb) : a.compareTo(b);
		});
		return out;
	}

	private MapDimensions.DimInfo dimInfo(String id) {
		for (MapDimensions.DimInfo d : dims) if (d.id.equals(id)) return d;
		return null;
	}

	/**
	 * Zeigt eine Dimension (null oder die eigene = zurück zur Live-Karte). Zwischen Oberwelt und Nether wird die
	 * Kartenmitte umgerechnet (×8 bzw. ÷8); ins End (und zurück) geht es zur Weltmitte.
	 */
	public boolean viewDimension(String dim) {
		String from = viewDimension();
		String cur = e.dimension();
		if (dim == null || dim.equals(cur)) {
			closeForeign();
			viewDim = null;
		} else {
			MapDimensions.DimInfo info = dimInfo(dim);
			if (info == null || info.layers.isEmpty()) return false;
			openForeign(info, MapDimensions.defaultLayer(info));
			viewDim = dim;
		}
		String to = viewDimension();
		if (!from.equals(to)) {
			double f = MapDimensions.factor(from, to);
			if (follow) {
				// Folgen geht nur, wenn sich die eigene Position umrechnen lässt.
				if (Double.isNaN(MapDimensions.factor(cur, to))) {
					follow = false;
					cam.center(0.5, 0.5);
				}
			} else if (Double.isNaN(f)) {
				cam.center(0.5, 0.5);
			} else if (f != 1.0) {
				cam.center(cam.centerX() * f, cam.centerZ() * f);
			}
			menuOpen = false;
		}
		return true;
	}

	private void openForeign(MapDimensions.DimInfo info, MapDimensions.LayerInfo li) {
		closeForeign();
		if (li == null) return;
		foreignDim = info;
		foreignLayer = li;
		foreign = new MapLayer(li.id, e.nextLayerIndex(), li.caveRef(), e.disk(), li.dir);
		// Nur zum Ansehen: etwas weniger im Speicher als die Live-Ebene.
		foreign.setMaxRegions(160);
	}

	private void closeForeign() {
		if (foreign != null) {
			e.textures().releaseLayer(foreign);
			foreign.close(null);
		}
		foreign = null;
		foreignDim = null;
		foreignLayer = null;
	}

	/** Nächste gespeicherte Ebene der angesehenen Dimension (Oberfläche, Höhlenschichten). */
	private void cycleForeignLayer() {
		if (foreignDim == null || foreignDim.layers.size() < 2) return;
		int i = foreignDim.layers.indexOf(foreignLayer);
		MapDimensions.LayerInfo next = foreignDim.layers.get((i + 1) % foreignDim.layers.size());
		openForeign(foreignDim, next);
	}

	/** Hält Dimensionsliste und fremde Ebene aktuell (je Bild). */
	private void syncView(long now) {
		String wk = e.worldKey();
		if (!e.inWorld() || wk == null || wk.isEmpty()) {
			closeForeign();
			viewDim = null;
			return;
		}
		if (!wk.equals(worldOpened)) {
			worldOpened = wk;
			dims = new ArrayList<MapDimensions.DimInfo>();
			dimsAt = 0;
			closeForeign();
			viewDim = null;
		}
		// Man ist inzwischen selbst in der angesehenen Dimension (Portal) → Live-Karte.
		if (viewDim != null && viewDim.equals(e.dimension())) {
			closeForeign();
			viewDim = null;
		}
		if (viewDim != null && (foreign == null || !e.modules().worldMapDimensions.get())) {
			viewDimension(null);
		}
		MapDisk disk = e.disk();
		if (disk != null && !dimsLoading && (dimsAt == 0 || now - dimsAt > 15_000)) {
			dimsLoading = true;
			final String key = wk;
			final java.nio.file.Path worldDir = disk.worldDir(wk);
			final java.nio.file.Path legacyWorldDir = disk.legacyDirOf(worldDir);
			disk.background(() -> MapDimensions.scan(worldDir, legacyWorldDir), list -> {
				dimsLoading = false;
				dimsAt = System.currentTimeMillis();
				if (list != null && key.equals(worldOpened)) dims = list;
			});
		}
		if (foreign != null && now - lastForeignMaintain > 1000) {
			lastForeignMaintain = now;
			foreign.maintain(now, Long.MAX_VALUE, null, 0);
		}
	}

	private double guiScale() {
		MapPlatform p = e.platform();
		return p == null ? 2.0 : p.guiScale();
	}

	// --- Zeichnen ---

	@Override
	protected void draw(Canvas raw, int w, int h, int mx, int my, float dt) {
		Canvas c = FadeCanvas.of(raw, alpha());
		width = w;
		height = h;
		mouseX = mx;
		mouseY = my;
		e.setWorldMapOpen(true);
		Theme t = Theme.get();
		long now = System.currentTimeMillis();
		syncView(now);

		double gs = guiScale();
		cam.setLimits((float) (MIN_SCREEN_PX / gs), (float) (MAX_SCREEN_PX / gs));
		cam.update(dt);
		if (follow && e.inWorld()) {
			double f = MapDimensions.factor(e.dimension(), viewDimension());
			if (!Double.isNaN(f)) cam.follow(e.playerX() * f, e.playerZ() * f);
		}

		c.fill(0, 0, w, h, 0xFF0B0A0E);
		MapLayer layer = layer();
		hover.clear();
		if (layer == null || !e.inWorld()) {
			Paint.textCentered(c, I18n.tr("map.noWorld"), w / 2, h / 2 - 4, t.textDim, false);
			chrome(c, w, h, mx, my, t, null, dt);
			overlays(c, w, h, mx, my, t);
			return;
		}
		if (e.disk() != null) e.disk().nextFrame();
		// Karte v3: fehlende Chunks auch je Bild abtasten (offene Weltkarte → mehr Budget).
		e.frame(true);
		drawMap(c, layer, w, h, now);
		if (e.modules().worldMapGrid.get() && cam.scale() * gs >= 6f) grid(c, w, h);
		markers(c, w, h, mx, my, gs);
		chrome(c, w, h, mx, my, t, layer, dt);
		overlays(c, w, h, mx, my, t);
	}

	private void overlays(Canvas c, int w, int h, int mx, int my, Theme t) {
		// Überlagerungen nach vorn heben: Minecraft zeichnet GUI-Text mit eigener Tiefe.
		c.push();
		c.raise(300);
		boolean popup = menuOpen || dimMenuOpen || exportMenuOpen;
		if (!hover.isEmpty() && !popup && !dialog.isOpen()) tooltip(c, mx, my, t);
		if (menuOpen) menu(c, mx, my, t);
		if (dimMenuOpen) dimMenu(c, mx, my, t);
		if (exportMenuOpen) exportMenu(c, mx, my, t);
		if (dialog.isOpen()) dialog.draw(c, hits, w, h, mx, my);
		c.pop();
	}

	private void drawMap(Canvas c, MapLayer layer, int w, int h, long now) {
		if (!c.images()) return;
		float scale = cam.scale();
		double cx = cam.centerX(), cz = cam.centerZ();
		double wx0 = cx - w / 2.0 / scale, wx1 = cx + w / 2.0 / scale;
		double wz0 = cz - h / 2.0 / scale, wz1 = cz + h / 2.0 / scale;
		// Beim Schieben/Zoomen viel Budget (flüssiges Nachladen), aber nie mehr als ~4 ms je Bild.
		e.textures().beginFrame(24, 4_000_000L);
		c.push();
		c.translate(w / 2f, h / 2f);
		c.scale(scale, scale);
		float screenPx = scale * (float) guiScale();
		// Innenansicht: erst die Oberfläche, darüber der Dach-Schnitt (der reicht nur so weit, wie abgetastet wurde).
		MapLayer under = layer.roof() && !viewingOther() ? e.surfaceLayer() : null;
		if (under != null) tilesUnder.draw(c, e, under, cx, cz, wx0, wz0, wx1, wz1, screenPx, now);
		tiles.draw(c, e, layer, cx, cz, wx0, wz0, wx1, wz1, screenPx, now);
		c.pop();
	}

	private void grid(Canvas c, int w, int h) {
		float scale = cam.scale();
		int alphaChunk = scale * guiScale() >= 12f ? 0x28 : 0x18;
		double cx = cam.centerX(), cz = cam.centerZ();
		double wx0 = cx - w / 2.0 / scale, wz0 = cz - h / 2.0 / scale;
		int first = (int) Math.floor(wx0 / 16) * 16;
		for (int x = first; x <= wx0 + w / scale + 16; x += 16) {
			int sx = (int) Math.round((x - cx) * scale + w / 2.0);
			if (sx < 0 || sx >= w) continue;
			int a = (x & (MapRegion.SIZE - 1)) == 0 ? 0x50 : alphaChunk;
			c.fill(sx, BAR_H, sx + 1, h - BAR_H, a << 24);
		}
		int firstZ = (int) Math.floor(wz0 / 16) * 16;
		for (int z = firstZ; z <= wz0 + h / scale + 16; z += 16) {
			int sy = (int) Math.round((z - cz) * scale + h / 2.0);
			if (sy < BAR_H || sy >= h - BAR_H) continue;
			int a = (z & (MapRegion.SIZE - 1)) == 0 ? 0x50 : alphaChunk;
			c.fill(0, sy, w, sy + 1, a << 24);
		}
	}

	private void toScreen(double wx, double wz) {
		point[0] = cam.screenX(wx) + width / 2.0;
		point[1] = cam.screenY(wz) + height / 2.0;
	}

	private double worldX(double sx) {
		return cam.worldX(sx - width / 2.0);
	}

	private double worldZ(double sy) {
		return cam.worldZ(sy - height / 2.0);
	}

	private boolean onScreen(float margin) {
		return point[0] >= -margin && point[0] <= width + margin && point[1] >= -margin && point[1] <= height + margin;
	}

	/** Wegpunkte der angezeigten Dimension. */
	private List<Waypoint> viewWaypoints() {
		return viewingOther() ? e.waypointsIn(viewDim) : e.waypoints();
	}

	/** Touch: die Karte zeigt Blockpixel nach GUI-Skalierung – nicht zusätzlich vergrößern. */
	@Override
	protected float touchScale(int width, int height) {
		return 1f;
	}

	/** Touch: auf der Karte schiebt der Finger direkt; über der Seitenleiste scrollt er die Liste. */
	@Override
	protected boolean touchDirect(double x, double y) {
		return !overSidebar(x, y) || super.touchDirect(x, y);
	}

	private boolean overSidebar(double mx, double my) {
		return sidebarOpen && sidebar.contains(mx, my);
	}

	/** Beschriftungen der Karte (ohne Überlappung, gesetzt am Ende von {@link #markers}). */
	private final MapLabels labels = new MapLabels();

	private void markers(Canvas c, int w, int h, int mx, int my, double s) {
		markerSprites(c, w, h, mx, my, s);
		labels.flush(c);
	}

	private void markerSprites(Canvas c, int w, int h, int mx, int my, double s) {
		TrsModules m = e.modules();
		MapSprites sprites = e.sprites();
		double px = e.playerX(), pz = e.playerZ();
		boolean mapHover = my > BAR_H && my < h - BAR_H && !overSidebar(mx, my);
		float scale = cam.scale();
		long now = System.currentTimeMillis();
		// Hervorgehobener Wegpunkt (Zeiger in der Liste oder gerade angeklickt).
		Waypoint highlight = sidebarOpen && sidebar.hovered != null ? sidebar.hovered : (now < pulseUntil ? pulse : null);
		// Wegpunkte der Freunde (Live-Teilen) unter den eigenen.
		FriendWaypoint friendHovered = null;
		if (friendWaypointsShown()) {
			FriendWaypoint friendHl = sidebarOpen && sidebar.hoveredFriend != null ? sidebar.hoveredFriend
					: (now < pulseUntil ? pulseFriend : null);
			double best = 6.5 * 6.5;
			for (FriendWaypoint fw : LiveShare.get().friendWaypoints(WaypointShare.canonicalDimension(viewDimension()))) {
				toScreen(fw.x + 0.5, fw.z + 0.5);
				if (!onScreen(20)) continue;
				float x = (float) point[0], y = (float) point[1];
				if (fw == friendHl) {
					float p = 13f + 3f * (float) Math.sin(now / 150.0);
					sprites.draw(c, MapSprites.HALO, x, y, p, 0f, 0xFF000000 | fw.color, s);
				}
				MinimapRenderer.friendMark(c, e, fw, x, y, 1.15f, s);
				boolean near = Math.abs(mx - x) < 8 && Math.abs(my - y) < 8;
				if (fw == friendHl || scale * s >= 1.5f || near) {
					int lw = c.textWidth(fw.name);
					int labelColor = fw.death ? FriendFaces.FRIEND : ColorMath.lerp(0xFF000000 | fw.color, 0xFFFFFFFF, 0.25f);
					labels.add(fw.name, Math.round(x - lw / 2f), Math.round(y + 8), labelColor, 0x90000000, false,
							fw == friendHl || near ? MapLabels.HOVER : MapLabels.FRIEND, true);
				}
				double d = (mx - x) * (mx - x) + (my - y) * (my - y);
				if (mapHover && d <= best) {
					best = d;
					friendHovered = fw;
				}
			}
		}
		// Wegpunkte.
		if (m.worldMapWaypoints.get() || highlight != null) {
			Waypoint hovered = null;
			double hoveredD = 6.5 * 6.5;
			Waypoint target = e.waypointTarget();
			for (Waypoint wp : viewWaypoints()) {
				boolean hl = wp == highlight;
				boolean shown = e.waypointShown(wp);
				if (!hl && (!m.worldMapWaypoints.get() || !shown)) continue;
				toScreen(wp.x + 0.5, wp.z + 0.5);
				if (!onScreen(20)) continue;
				float x = (float) point[0], y = (float) point[1];
				if (hl) {
					float p = 13f + 3f * (float) Math.sin(now / 150.0);
					sprites.draw(c, MapSprites.HALO, x, y, p, 0f, 0xFF000000 | wp.color, s);
				}
				if (wp == target) sprites.draw(c, MapSprites.HALO, x, y, 18f, 0f, 0xFF000000 | wp.color, s);
				WaypointBadge.draw(c, wp, x, y, 1f, shown ? 255 : 0x90);
				String label = wp.name;
				boolean near = Math.abs(mx - x) < 8 && Math.abs(my - y) < 8;
				if (hl || scale * s >= 0.7f || near) {
					int lw = c.textWidth(label);
					labels.add(label, Math.round(x - lw / 2f), Math.round(y + 8), 0xFF000000 | wp.color, 0xA0000000, false,
							hl || near ? MapLabels.HOVER : MapLabels.OWN, true);
				}
				double d = (mx - x) * (mx - x) + (my - y) * (my - y);
				if (mapHover && d <= hoveredD) {
					hoveredD = d;
					hovered = wp;
				}
			}
			// Nur der nächste Wegpunkt unter dem Zeiger bekommt den Hinweis.
			if (hovered != null) {
				friendHovered = null;
				WaypointFilter.Row row = WaypointFilter.row(hovered, e.dimension(), px, pz);
				hover.add(hovered.name);
				if (!Double.isNaN(row.distance)) {
					hover.add((row.converted ? "≈" : "") + I18n.tr("map.distance", WaypointFilter.distanceText(row.distance)));
				}
				hover.add(hovered.x + " / " + hovered.y + " / " + hovered.z);
			}
		}
		if (friendHovered != null) {
			Owner owner = LiveShare.get().owner(friendHovered.owner);
			hover.add(friendHovered.name);
			hover.add(I18n.tr("map.friend.by", owner == null ? "?" : owner.name()));
			double fx = friendHovered.x + 0.5, fz = friendHovered.z + 0.5;
			double f = MapDimensions.factor(e.dimension(), viewDimension());
			if (!Double.isNaN(f) && f > 0) {
				double dx = fx / f - px, dz = fz / f - pz;
				hover.add((f != 1 ? "≈" : "") + I18n.tr("map.distance", WaypointFilter.distanceText(Math.sqrt(dx * dx + dz * dz))));
			}
			hover.add(friendHovered.x + " / " + friendHovered.y + " / " + friendHovered.z);
			hover.add(I18n.tr("map.friend.adoptHint"));
		}
		// Live-Köpfe der Freunde (auch in anderen Dimensionen der Karte, wenn sie dort sind).
		if (e.modules().waypointFriendPositions.get()) friendHeads(c, s, mx, my, mapHover);
		if (viewingOther()) {
			// Eigene Position umgerechnet (Nether ↔ Oberwelt) als blasser Pfeil.
			double f = MapDimensions.factor(e.dimension(), viewDim);
			if (!Double.isNaN(f)) {
				toScreen(px * f, pz * f);
				float yawRad = (float) Math.toRadians(e.yaw());
				sprites.draw(c, MapSprites.ARROW, (float) point[0], (float) point[1], 13f, yawRad + (float) Math.PI,
						0x90000000 | (Theme.get().dustOn & 0xFFFFFF), s);
			}
			return;
		}
		// Kreaturen und Spieler.
		float alpha = e.alpha();
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < e.entityCount(); i++) {
				MapEntity en = e.entity(i);
				boolean player = en.type == MapEntity.PLAYER;
				if ((pass == 1) != player) continue;
				if (player ? !m.worldMapPlayers.get()
						: (en.type == MapEntity.HOSTILE ? !m.worldMapHostile.get() : !m.worldMapPassive.get())) continue;
				toScreen(en.lerpX(alpha), en.lerpZ(alpha));
				if (!onScreen(10)) continue;
				float x = (float) point[0], y = (float) point[1];
				if (!player) {
					sprites.draw(c, MapSprites.DOT, x, y, en.type == MapEntity.HOSTILE ? 6f : 5f, 0f,
							en.type == MapEntity.HOSTILE ? MinimapRenderer.HOSTILE : MinimapRenderer.PASSIVE, s);
					continue;
				}
				if (en.friend) sprites.draw(c, MapSprites.HALO, x, y, 16f, 0f, MinimapRenderer.FRIEND, s);
				MinimapRenderer.face(c, e, en, x, y, 9f, s);
				if (en.name != null) {
					int lw = c.textWidth(en.name);
					labels.add(en.name, Math.round(x - lw / 2f), Math.round(y + 7), en.friend ? MinimapRenderer.FRIEND : 0xFFFFFFFF, 0, true,
							Math.abs(mx - x) < 8 && Math.abs(my - y) < 8 ? MapLabels.HOVER : MapLabels.PLAYER, true);
				}
			}
		}
		// Geteilter Punkt aus dem Chat („Anzeigen“).
		if (focus != null) {
			toScreen(focus.x + 0.5, focus.z + 0.5);
			if (onScreen(20)) {
				float x = (float) point[0], y = (float) point[1];
				float p = 12f + 3f * (float) Math.sin(now / 180.0);
				sprites.draw(c, MapSprites.HALO, x, y, p, 0f, 0xFF000000 | focus.color, s);
				sprites.draw(c, MapSprites.DIAMOND, x, y, 10f, 0f, 0xFF000000 | focus.color, s);
				int lw = c.textWidth(focus.name);
				labels.add(focus.name, Math.round(x - lw / 2f), Math.round(y + 8), 0xFF000000 | focus.color, 0xA0000000, false, MapLabels.FORCE, true);
			}
		}
		// Eigene Position.
		toScreen(px, pz);
		float yawRad = (float) Math.toRadians(e.yaw());
		sprites.draw(c, MapSprites.ARROW, (float) point[0], (float) point[1], 13f, yawRad + (float) Math.PI,
				0xFF000000 | (Theme.get().dustOn & 0xFFFFFF), s);
	}

	// --- Leisten ---

	/** Name der angezeigten Ebene, z. B. „Nether · Höhle Y 32“. */
	private String viewTitle(MapLayer layer) {
		String dim = dimensionName(viewDimension());
		if (viewingOther()) {
			if (foreignLayer != null && foreignLayer.cave()) {
				dim += " · " + I18n.tr("map.layer.cave", MapDimensions.bandBottom(foreignLayer.caveBand()));
			}
			return dim;
		}
		if (layer != null && layer.cave()) dim += " · " + I18n.tr("map.cave");
		else if (layer != null && layer.roof()) dim += " · " + I18n.tr("map.roof");
		return dim;
	}

	private void chrome(Canvas c, int w, int h, int mx, int my, Theme t, MapLayer layer, float dt) {
		// Seitenleiste (Wegpunkt-Liste).
		if (sidebarOpen && layer != null) {
			sidebar.draw(c, hits, w - WorldMapSidebar.WIDTH, BAR_H, WorldMapSidebar.WIDTH, h - 2 * BAR_H, mx, my, dt, hover);
		}

		// Oben: Titel, Dimension (Knopf), Knöpfe.
		c.fill(0, 0, w, BAR_H, 0xE0141217);
		c.fill(0, BAR_H - 1, w, BAR_H, ColorMath.withAlpha(t.dustOn, 0xB0));
		Icons.draw(c, "map", 8, 7, 1, 0xFF000000 | (t.dustOn & 0xFFFFFF));
		String title = I18n.tr("map.title");
		c.text(title, 20, 7, t.text, false);
		int x = 26 + c.textWidth(title);

		int bx = w - 6;
		bx = button(c, bx, "close", I18n.tr("map.close"), mx, my, this::requestClose);
		bx = button(c, bx, "home", I18n.tr("map.center"), mx, my, this::centerOnPlayer);
		bx = button(c, bx, "plus", I18n.tr("map.zoomIn"), mx, my, () -> zoomButton(1));
		bx = textButton(c, bx, "-", I18n.tr("map.zoomOut"), mx, my, t, () -> zoomButton(-1));
		if (viewingOther()) {
			if (foreignDim != null && foreignDim.layers.size() > 1) {
				bx = button(c, bx, "layers", I18n.tr("map.nextLayer"), mx, my, this::cycleForeignLayer);
			}
		} else if (e.layeredView()) {
			bx = button(c, bx, surfaceOnly ? "layers" : "sun", I18n.tr(surfaceOnly ? (e.caveActive() ? "map.showCave" : "map.showRoof") : "map.showSurface"), mx, my,
					() -> surfaceOnly = !surfaceOnly);
		}
		if (layer != null) {
			final int ex = bx;
			bx = button(c, bx, "image", I18n.tr("map.export"), mx, my, () -> {
				exportMenuOpen = !exportMenuOpen;
				dimMenuOpen = false;
				menuOpen = false;
				popupAnchorX = ex;
			});
			bx = button(c, bx, "pin", I18n.tr(sidebarOpen ? "map.list.hide" : "map.list.show"), mx, my, this::toggleSidebar);
		}

		// Dimension: als Knopf, wenn es andere gespeicherte Dimensionen gibt.
		String dimText = viewTitle(layer);
		boolean canSwitch = layer != null && e.modules().worldMapDimensions.get() && dimensionChoices().size() > 1;
		int maxDim = Math.max(20, bx - 8 - x - (canSwitch ? 10 : 0));
		String shown = c.clip(dimText, maxDim);
		int dw = c.textWidth(shown);
		if (canSwitch) {
			boolean hov = mx >= x - 3 && mx < x + dw + 13 && my >= 3 && my < 19;
			if (hov || dimMenuOpen) c.fill(x - 3, 3, x + dw + 13, 19, ColorMath.withAlpha(t.dustOn, hov ? 0x50 : 0x30));
			c.text(shown, x, 7, viewingOther() ? (0xFF000000 | (t.dustOn & 0xFFFFFF)) : t.textDim, false);
			c.text("▾", x + dw + 4, 7, t.textDim, false);
			final int ax = x - 3;
			hits.add(x - 3, 3, dw + 16, 16, () -> {
				host.playClick();
				dimMenuOpen = !dimMenuOpen;
				exportMenuOpen = false;
				menuOpen = false;
				popupAnchorX = ax;
			});
			if (hov) hover.add(I18n.tr("map.switchDim"));
		} else {
			c.text(shown, x, 7, t.textDim, false);
		}

		// Unten: Koordinaten unter dem Zeiger (+ umgerechnet), Maßstab, Hinweise/Export, Fair Play.
		c.fill(0, h - BAR_H, w, h, 0xE0141217);
		// Rechts: Maßstab (+ Fair Play). Die Koordinaten haben Vorrang – wird es eng, fällt erst Fair Play weg.
		String zoom = zoomText();
		FairPlay fp = e.fairPlay();
		String right = fp.active() ? I18n.tr(fp.serverFair() ? "map.fairPlayServer" : "map.fairPlay") + "  ·  " + zoom : zoom;
		List<String> coords = null;
		if (layer != null && my > BAR_H && my < h - BAR_H && !overSidebar(mx, my)) {
			int bxw = (int) Math.floor(worldX(mx)), bzw = (int) Math.floor(worldZ(my));
			coords = coordinateTexts(bxw, e.heightAt(layer, bxw, bzw), bzw);
		}
		int leftEnd = 150;
		if (coords != null) {
			String pos = fitting(c, coords, w - 16 - c.textWidth(right) - 12);
			if (pos == null) {
				right = zoom;
				pos = fitting(c, coords, w - 16 - c.textWidth(right) - 12);
			}
			if (pos == null) pos = coords.get(coords.size() - 1);
			c.text(pos, 8, h - BAR_H + 7, t.text, false);
			leftEnd = Math.max(leftEnd, 8 + c.textWidth(pos) + 12);
		}
		int rw = c.textWidth(right);
		c.text(right, w - 8 - rw, h - BAR_H + 7, fp.active() && right != zoom ? (0xFF000000 | (t.dustOn & 0xFFFFFF)) : t.textDim, false);
		// Mitte: nur die Bedienhilfe, wenn Platz ist – Meldungen erscheinen als eigener Hinweis darüber (toast).
		String hint = I18n.tr("map.hint");
		int hw = c.textWidth(hint);
		if (w / 2 + hw / 2 < w - 16 - rw && w / 2 - hw / 2 > leftEnd) {
			c.text(hint, w / 2 - hw / 2, h - BAR_H + 7, t.textDim, false);
		}
		toast(c, w, h, t);
	}

	/**
	 * Koordinatenzeile unter dem Zeiger in immer kürzeren Fassungen – X und Z (auch die umgerechneten) bleiben in jeder
	 * Fassung vollständig; zuerst werden Abstände enger, dann fällt die Höhe weg, dann der Name der anderen Dimension.
	 */
	List<String> coordinateTexts(int x, int y, int z) {
		List<String> out = new ArrayList<String>();
		String view = viewDimension();
		String other = e.modules().worldMapNetherCoords.get() ? MapDimensions.counterpart(view) : null;
		boolean hasY = y != Integer.MIN_VALUE;
		String full = hasY ? I18n.tr("map.cursorY", x, y, z) : I18n.tr("map.cursor", x, z);
		String noY = I18n.tr("map.cursor", x, z);
		if (other == null) {
			out.add(full);
			out.add(tight(full));
			if (hasY) out.add(tight(noY));
			return out;
		}
		int ox = MapDimensions.convert(x, view, other), oz = MapDimensions.convert(z, view, other);
		String o = I18n.tr("map.cursorOther", dimensionName(other), ox, oz);
		out.add(full + "   ·   " + o);
		out.add(tight(full) + " · " + tight(o));
		if (hasY) out.add(tight(noY) + " · " + tight(o));
		out.add(tight(noY) + " → " + ox + ", " + oz);
		return out;
	}

	/** Mehrfache Leerzeichen zu einem (engere Fassung derselben Angaben). */
	static String tight(String s) {
		return s.replaceAll(" {2,}", " ");
	}

	/** Erste Fassung, die in {@code max} Pixel passt, sonst null. */
	private static String fitting(Canvas c, List<String> variants, int max) {
		for (String v : variants) {
			if (c.textWidth(v) <= max) return v;
		}
		return null;
	}

	/**
	 * Hinweis über der unteren Leiste (Export läuft/fertig, Fehler, „keine Karte dort“): eigener Kasten, nie über den
	 * Koordinaten. Zweite Zeile (z. B. Dateiname) wird in der Mitte mit „…“ gekürzt – vollständig steht er im Chat.
	 */
	private void toast(Canvas c, int w, int h, Theme t) {
		long now = System.currentTimeMillis();
		String title, detail = null;
		int accent;
		float progress = -1f;
		if (MapExport.running()) {
			progress = MapExport.current().progress();
			title = I18n.tr("map.export.running", Math.round(progress * 100));
			accent = 0xFF000000 | (t.dustOn & 0xFFFFFF);
		} else if (toastTitle != null && now < toastUntil) {
			title = toastTitle;
			detail = toastDetail;
			accent = toastColor;
		} else {
			return;
		}
		int free = sidebarOpen ? w - WorldMapSidebar.WIDTH : w;
		int maxW = Math.max(120, Math.min(free - 24, 380));
		String line1 = c.clip(title, maxW - 16);
		String line2 = detail == null ? null : middleEllipsis(c, detail, maxW - 16);
		int bw = Math.max(c.textWidth(line1), line2 == null ? 0 : c.textWidth(line2)) + 16;
		if (progress >= 0) bw = Math.max(bw, 140);
		int bh = line2 == null ? 18 : 28;
		if (progress >= 0) bh += 4;
		int x = free / 2 - bw / 2, y = h - BAR_H - bh - 6;
		c.push();
		c.raise(250);
		Redstone.block(c, x, y, bw, bh, 0xF0141217);
		Redstone.frame(c, x, y, bw, bh, ColorMath.withAlpha(accent, 0xC0));
		c.fill(x + 1, y + 1, x + 3, y + bh - 1, accent);
		c.text(line1, x + 8, y + 5, accent, false);
		if (line2 != null) c.text(line2, x + 8, y + 15, t.text, false);
		if (progress >= 0) {
			int px = x + 8, pw = bw - 16, py = y + bh - 6;
			c.fill(px, py, px + pw, py + 2, 0x60FFFFFF);
			c.fill(px, py, px + Math.round(pw * Math.max(0f, Math.min(1f, progress))), py + 2, accent);
		}
		c.pop();
	}

	/** Kürzt in der Mitte („trs-map_Welt…12.30.05.png“), damit Anfang und Endung lesbar bleiben. */
	static String middleEllipsis(Canvas c, String s, int max) {
		if (c.textWidth(s) <= max) return s;
		String dots = "…";
		int keepEnd = s.length() / 2, keepStart = s.length() - keepEnd;
		while (keepStart + keepEnd > 2) {
			if (keepEnd >= keepStart) keepEnd--;
			else keepStart--;
			String v = s.substring(0, keepStart) + dots + s.substring(s.length() - keepEnd);
			if (c.textWidth(v) <= max) return v;
		}
		return dots;
	}

	/** Maßstab rechts unten: „2 px/Block“, „1.5 px/Block“ oder „1:8“. */
	private String zoomText() {
		float screenPx = cam.targetScale() * (float) guiScale();
		if (screenPx >= 1f) {
			float r = Math.round(screenPx * 10f) / 10f;
			String v = r == Math.round(r) ? Integer.toString(Math.round(r)) : String.format(Locale.ROOT, "%.1f", r);
			return v + " px/" + I18n.tr("map.block");
		}
		return "1:" + Math.round(1 / screenPx);
	}

	private int button(Canvas c, int right, String icon, String tip, int mx, int my, Runnable action) {
		int x = right - 16, y = 3;
		boolean hov = mx >= x && mx < x + 16 && my >= y && my < y + 16;
		Redstone.iconButton(c, x, y, 16, icon, hov, false);
		hits.add(x, y, 16, 16, () -> {
			host.playClick();
			action.run();
		});
		if (hov) hover.add(tip);
		return x - 4;
	}

	private int textButton(Canvas c, int right, String label, String tip, int mx, int my, Theme t, Runnable action) {
		int x = right - 16, y = 3;
		boolean hov = mx >= x && mx < x + 16 && my >= y && my < y + 16;
		Redstone.button(c, x, y, 16, 16, "", false, hov);
		Paint.textCentered(c, label, x + 8, y + 4, t.text, false);
		hits.add(x, y, 16, 16, () -> {
			host.playClick();
			action.run();
		});
		if (hov) hover.add(tip);
		return x - 4;
	}

	public static String dimensionName(String dim) {
		if (dim == null) return "";
		String d = dim.toLowerCase(Locale.ROOT);
		if (d.contains("nether") || d.equals("dim-1")) return I18n.tr("map.dim.nether");
		if (d.contains("the_end") || d.endsWith(":end") || d.equals("dim1")) return I18n.tr("map.dim.end");
		if (d.contains("overworld") || d.equals("dim0")) return I18n.tr("map.dim.overworld");
		int colon = dim.indexOf(':');
		return colon >= 0 ? dim.substring(colon + 1) : dim;
	}

	private void tooltip(Canvas c, int mx, int my, Theme t) {
		int w = 0;
		for (String s : hover) w = Math.max(w, c.textWidth(s));
		int h = hover.size() * 10 + 4;
		int x = Math.min(mx + 10, width - w - 10), y = Math.min(my + 10, height - h - 4);
		Redstone.block(c, x - 3, y - 2, w + 6, h, 0xF0141217);
		Redstone.frame(c, x - 3, y - 2, w + 6, h, ColorMath.withAlpha(t.dustOn, 0xA0));
		for (int i = 0; i < hover.size(); i++) c.text(hover.get(i), x, y + 1 + i * 10, i == 0 ? t.text : t.textDim, false);
	}

	// --- Knöpfe ---

	private void centerOnPlayer() {
		if (viewingOther()) viewDimension(null);
		follow = true;
		cam.center(e.playerX(), e.playerZ());
	}

	private void toggleSidebar() {
		sidebarOpen = !sidebarOpen;
		if (!sidebarOpen) sidebar.search.setFocused(false);
		e.modules().worldMapList.set(sidebarOpen);
		saveSettings();
	}

	@Override
	public void settingsChanged() {
		saveSettings();
	}

	private void saveSettings() {
		try {
			dev.theredstonee.trsclient.core.config.ConfigStore store = dev.theredstonee.trsclient.core.config.ConfigStore.active();
			if (store != null) store.saveLater(e.modules().registry);
		} catch (java.io.IOException | RuntimeException ex) {
			// nur die Einstellung „Liste offen“ – nicht schlimm
		}
	}

	private boolean smoothZoom() {
		return e.modules().worldMapSmoothZoom.get();
	}

	private void zoomButton(int dir) {
		if (smoothZoom()) {
			// Knöpfe zoomen um die Bildmitte (Spieler folgen bleibt an).
			cam.zoomBy(dir > 0 ? 2.0 : 0.5, 0, 0);
		} else {
			zoomStep(dir, width / 2f, height / 2f);
		}
	}

	// --- Aufklappmenüs oben ---

	private void dimMenu(Canvas c, int mx, int my, Theme t) {
		final List<String> choices = dimensionChoices();
		int w = 0;
		for (String d : choices) w = Math.max(w, c.textWidth(dimensionName(d)) + 20);
		w = Math.max(w + 16, 110);
		int h = choices.size() * 14 + 6;
		int x = Math.max(4, Math.min(popupAnchorX, width - w - 4)), y = BAR_H + 2;
		setPopup(x, y, w, h);
		Redstone.window(c, x, y, w, h);
		String view = viewDimension();
		for (int i = 0; i < choices.size(); i++) {
			final String id = choices.get(i);
			int iy = y + 3 + i * 14;
			boolean hov = mx >= x && mx < x + w && my >= iy && my < iy + 14;
			boolean sel = id.equals(view);
			if (hov || sel) c.fill(x + 1, iy, x + w - 1, iy + 14, ColorMath.withAlpha(t.dustOn, sel ? 0x40 : 0x50));
			c.text(dimensionName(id), x + 8, iy + 3, sel ? t.text : t.textDim, false);
			if (id.equals(e.dimension())) {
				// Hier ist man gerade.
				e.sprites().draw(c, MapSprites.ARROW, x + w - 9, iy + 7, 8f, (float) Math.PI, 0xFF000000 | (t.dustOn & 0xFFFFFF), guiScale());
			}
			hits.add(x, iy, w, 14, () -> {
				host.playClick();
				dimMenuOpen = false;
				if (!viewDimension(id)) flash(I18n.tr("map.noMapThere"));
			});
		}
	}

	private void exportMenu(Canvas c, int mx, int my, Theme t) {
		String[] ids = {"all", "view"};
		int w = 0;
		for (String id : ids) w = Math.max(w, c.textWidth(I18n.tr("map.export." + id)));
		w += 24;
		int h = ids.length * 14 + 20;
		int x = Math.max(4, Math.min(popupAnchorX - w, width - w - 4)), y = BAR_H + 2;
		setPopup(x, y, w, h);
		Redstone.window(c, x, y, w, h);
		c.text(c.clip(I18n.tr("map.export"), w - 8), x + 4, y + 3, t.textDim, false);
		for (int i = 0; i < ids.length; i++) {
			final String id = ids[i];
			int iy = y + 14 + i * 14;
			boolean hov = mx >= x && mx < x + w && my >= iy && my < iy + 14;
			if (hov) c.fill(x + 1, iy, x + w - 1, iy + 14, ColorMath.withAlpha(t.dustOn, 0x50));
			c.text(I18n.tr("map.export." + id), x + 8, iy + 3, t.text, false);
			hits.add(x, iy, w, 14, () -> {
				host.playClick();
				exportMenuOpen = false;
				startExport("view".equals(id));
			});
		}
	}

	private void setPopup(int x, int y, int w, int h) {
		popupRect[0] = x;
		popupRect[1] = y;
		popupRect[2] = w;
		popupRect[3] = h;
	}

	// --- Export ---

	/**
	 * Export starten: ganz oder sichtbarer Ausschnitt (ohne Leisten und Liste). Die Innenansicht wird als Oberfläche
	 * exportiert (der Dach-Schnitt ist nur Stückwerk um Gebäude).
	 */
	public MapExport.StartResult startExport(boolean visibleOnly) {
		MapLayer layer = layer();
		if (layer != null && layer.roof()) layer = e.surfaceLayer();
		int[] rect = null;
		if (visibleOnly) {
			int right = sidebarOpen ? width - WorldMapSidebar.WIDTH : width;
			rect = new int[]{(int) Math.floor(worldX(0)), (int) Math.floor(worldZ(BAR_H)), (int) Math.ceil(worldX(right)),
					(int) Math.ceil(worldZ(height - BAR_H))};
		}
		Path out = MapExport.gameDir(e.disk()).resolve("screenshots");
		final MapPlatform platform = e.platform();
		MapExport.StartResult r = MapExport.start(layer, e.worldKey(), viewDimension(), rect,
				e.modules().worldMapExportSize.getInt(), out, e.disk(), true, job -> {
					String file = job.file().getFileName().toString();
					if (job.state() == MapExport.State.DONE) {
						// Im Chat der volle Name, auf der Karte ein eigener Hinweis (Name ggf. in der Mitte gekürzt).
						if (platform != null) platform.message(I18n.tr("map.export.done", file));
						showToast(I18n.tr("map.export.saved"), file, 0xFF4BE38A, 7000);
					} else if (job.state() == MapExport.State.CANCELLED) {
						showToast(I18n.tr("map.export.cancelled"), null, 0xFFFFB02E, 4000);
					} else {
						String msg = I18n.tr("map.export.failed", job.error() == null ? "?" : job.error());
						if (platform != null) platform.message(msg);
						showToast(msg, null, 0xFFFF6B5E, 7000);
					}
				});
		if (r == MapExport.StartResult.RUNNING) flash(I18n.tr("map.export.busy"));
		else if (r == MapExport.StartResult.EMPTY) flash(I18n.tr("map.export.empty"));
		else if (r == MapExport.StartResult.TOO_BIG) flash(I18n.tr("map.export.tooBig"));
		return r;
	}

	// --- Kontextmenü ---

	private List<String> menuItems() {
		List<String> items = new ArrayList<String>();
		if (menuWaypoint != null) {
			items.addAll(WaypointMenu.forWaypoint(e, menuWaypoint));
			// Teilen an Freunde geht nur in der eigenen Dimension (Wegpunkt-Karte braucht die Welt).
			if (viewingOther()) items.remove(WaypointMenu.SHARE);
		} else if (menuFriend != null) {
			items.add("adopt");
			String dim = WaypointShare.localDimension(menuFriend.dimension, e.dimension());
			if (WaypointTeleport.possible(e.modules(), e.platform(), dim)) items.add(WaypointMenu.TELEPORT);
			if (chatBridge()) items.add(WaypointMenu.COPY);
		} else {
			items.add("create");
			if (spotHeight() != Integer.MIN_VALUE && WaypointTeleport.possible(e.modules(), e.platform(), viewDimension())) items.add("teleportHere");
			if (chatBridge()) {
				items.add(WaypointMenu.COPY);
				items.add(WaypointMenu.CHAT);
				items.add(WaypointMenu.CHAT_XAERO);
				items.add(WaypointMenu.CHAT_JOURNEYMAP);
			}
			if (!viewingOther()) items.add(WaypointMenu.SHARE);
		}
		items.add("centerHere");
		items.add("centerPlayer");
		return items;
	}

	private boolean chatBridge() {
		return e.platform() != null && e.platform().chatBridge();
	}

	/** Oberster bekannter Block an der Menü-Stelle (Füße darüber), {@link Integer#MIN_VALUE} = unbekannt. */
	private int spotHeight() {
		int y = e.heightAt(layer(), menuBlockX, menuBlockZ);
		if (y == Integer.MIN_VALUE && !viewingOther()) y = e.heightAt(e.surfaceLayer(), menuBlockX, menuBlockZ);
		return y == Integer.MIN_VALUE ? y : y + 1;
	}

	/** Höhe der Stelle für Kopieren und Teilen: bekannt, sonst die eigene. */
	private int spotHeightOrPlayer() {
		int y = spotHeight();
		return y == Integer.MIN_VALUE ? (int) Math.floor(e.playerY()) : y;
	}

	/** Rechtsklick-Menü für einen Wegpunkt öffnen (auch aus der Liste). */
	@Override
	public void waypointMenu(Waypoint w, int x, int y) {
		menuOpen = true;
		menuX = x;
		menuY = y;
		menuBlockX = w.x;
		menuBlockZ = w.z;
		menuWaypoint = w;
		menuFriend = null;
	}

	/** Werden Wegpunkte der Freunde auf der Weltkarte gezeigt? */
	private boolean friendWaypointsShown() {
		LiveShare live = LiveShare.get();
		return live != null && live.active() && e.modules().waypointFriendWaypoints.get() && e.modules().worldMapWaypoints.get();
	}

	/** Köpfe der Freunde mit Blickrichtung und Namen; Zeiger darüber → Hinweis mit Entfernung. */
	private void friendHeads(Canvas c, double s, int mx, int my, boolean mapHover) {
		LiveShare live = LiveShare.get();
		if (live == null || !live.active()) return;
		String dim = WaypointShare.canonicalDimension(viewDimension());
		long now = System.currentTimeMillis();
		for (Owner o : live.owners()) {
			FriendPosition p = o.position();
			if (o.hidden() || p == null || !p.dimension.equals(dim)) continue;
			int alpha = MinimapRenderer.positionAlpha(p, now);
			if (alpha == 0 || (!viewingOther() && MinimapRenderer.loadedEntity(e, o.uuid))) continue;
			toScreen(p.x, p.z);
			if (!onScreen(12)) continue;
			float x = (float) point[0], y = (float) point[1];
			sprites().draw(c, MapSprites.HALO, x, y, 16f, 0f, ColorMath.withAlpha(FriendFaces.FRIEND, alpha), s);
			FriendFaces.facing(c, sprites(), x, y, 9f, (float) Math.toRadians(p.yaw) + (float) Math.PI, alpha, s);
			FriendFaces.head(c, o.uuid, o.name(), x, y, 9f, alpha);
			// Der Kopf bleibt frei – Beschriftungen weichen ihm aus.
			labels.occupy(Math.round(x - 9), Math.round(y - 9), Math.round(x + 9), Math.round(y + 7));
			String n = o.name();
			int lw = c.textWidth(n);
			boolean near = mapHover && Math.abs(mx - x) < 7 && Math.abs(my - y) < 7;
			labels.add(n, Math.round(x - lw / 2f), Math.round(y + 8), ColorMath.withAlpha(FriendFaces.FRIEND, alpha), 0, true,
					near ? MapLabels.HOVER : MapLabels.FRIEND_HEAD, true);
			if (mapHover && Math.abs(mx - x) < 7 && Math.abs(my - y) < 7 && hover.isEmpty()) {
				hover.add(n);
				double f = MapDimensions.factor(e.dimension(), viewDimension());
				if (!Double.isNaN(f) && f > 0) {
					double dx = p.x / f - e.playerX(), dz = p.z / f - e.playerZ();
					hover.add((f != 1 ? "≈" : "") + I18n.tr("map.distance", WaypointFilter.distanceText(Math.sqrt(dx * dx + dz * dz))));
				}
				long age = (now - p.at) / 1000;
				hover.add(age < 5 ? I18n.tr("map.friend.live") : I18n.tr("map.friend.seconds", age));
			}
		}
	}

	private MapSprites sprites() {
		return e.sprites();
	}

	private void menu(Canvas c, int mx, int my, Theme t) {
		List<String> items = menuItems();
		int w = 0;
		for (String id : items) w = Math.max(w, c.textWidth(I18n.tr("map.menu." + id)));
		w += 24;
		int h = items.size() * 14 + 16;
		int x = Math.min(menuX, width - w - 4), y = Math.min(menuY, height - h - 4);
		menuRect[0] = x;
		menuRect[1] = y;
		menuRect[2] = w;
		menuRect[3] = h;
		Redstone.window(c, x, y, w, h);
		String head = menuWaypoint != null ? menuWaypoint.name : menuFriend != null ? menuFriend.name : menuBlockX + " / " + menuBlockZ;
		c.text(c.clip(head, w - 8), x + 4, y + 3, t.textDim, false);
		for (int i = 0; i < items.size(); i++) {
			final String id = items.get(i);
			int iy = y + 14 + i * 14;
			boolean hov = mx >= x && mx < x + w && my >= iy && my < iy + 14;
			if (hov) c.fill(x + 1, iy, x + w - 1, iy + 14, ColorMath.withAlpha(t.dustOn, 0x50));
			c.text(I18n.tr("map.menu." + id), x + 8, iy + 3, id.equals("delete") ? 0xFFFF6B5E : t.text, false);
			hits.add(x, iy, w, 14, () -> {
				host.playClick();
				menuOpen = false;
				menuAction(id);
			});
		}
	}

	private void menuAction(String id) {
		MapPlatform p = e.platform();
		if ("create".equals(id)) {
			newAtMenuSpot();
		} else if (WaypointMenu.EDIT.equals(id)) {
			dialog.openEdit(menuWaypoint);
		} else if ("adopt".equals(id)) {
			adoptFriend(menuFriend);
		} else if (WaypointMenu.SHARE.equals(id)) {
			share();
		} else if ("centerHere".equals(id)) {
			centerOn(menuBlockX + 0.5, menuBlockZ + 0.5);
		} else if ("centerPlayer".equals(id)) {
			centerOnPlayer();
		} else if ("teleportHere".equals(id)) {
			teleportAfter(WaypointTeleport.send(e.modules(), p, menuBlockX, spotHeight(), menuBlockZ, viewDimension()));
		} else if (menuWaypoint != null) {
			if (WaypointMenu.TELEPORT.equals(id)) {
				teleportAfter(WaypointTeleport.send(e.modules(), p, menuWaypoint.x, menuWaypoint.y, menuWaypoint.z, menuWaypoint.dimension));
			} else {
				String note = WaypointMenu.run(id, e, menuWaypoint);
				if (note != null) flash(note);
			}
		} else if (menuFriend != null) {
			if (WaypointMenu.TELEPORT.equals(id)) {
				teleportAfter(WaypointTeleport.send(e.modules(), p, menuFriend.x, menuFriend.y, menuFriend.z,
						WaypointShare.localDimension(menuFriend.dimension, e.dimension())));
			} else if (WaypointMenu.COPY.equals(id) && p != null) {
				copy(WaypointMenu.coordinates(menuFriend.x, menuFriend.y, menuFriend.z));
			}
		} else if (WaypointMenu.COPY.equals(id) && p != null) {
			copy(WaypointMenu.coordinates(menuBlockX, spotHeightOrPlayer(), menuBlockZ));
		} else if (WaypointMenu.CHAT.equals(id) && p != null) {
			p.sendChat(WaypointMenu.chatLine("", menuBlockX, spotHeightOrPlayer(), menuBlockZ, viewDimension(), e.dimension()));
			flash(I18n.tr("map.chat.sent"));
		} else if ((WaypointMenu.CHAT_XAERO.equals(id) || WaypointMenu.CHAT_JOURNEYMAP.equals(id)) && p != null) {
			// Xaero braucht einen Namen – die Stelle heißt wie beim Teilen an Freunde.
			p.sendChat(WaypointMenu.mapModLine(id, I18n.tr("waypoint.share.spot"), "", menuBlockX, spotHeightOrPlayer(),
					menuBlockZ, -1, viewDimension(), e.dimension()));
			flash(I18n.tr("map.chat.sent"));
		}
	}

	private void copy(String text) {
		e.platform().copyToClipboard(text);
		flash(I18n.tr("waypoint.card.copied", text));
	}

	/** Der Befehl ist unterwegs: Karte schließen, damit man das Ergebnis sieht (der Server entscheidet). */
	private void teleportAfter(boolean sent) {
		if (sent) requestClose();
		else flash(I18n.tr("map.teleport.denied"));
	}

	private void newAtMenuSpot() {
		int y = spotHeight();
		if (y == Integer.MIN_VALUE) y = viewingOther() ? 64 : (int) Math.floor(e.playerY());
		dialog.openNew(Waypoint.defaultName(), menuBlockX, y, menuBlockZ, viewDimension(), COLORS[e.allWaypoints().size() % COLORS.length]);
		menuOpen = dimMenuOpen = exportMenuOpen = false;
		sidebar.search.setFocused(false);
	}

	/** „Übernehmen“: Wegpunkt eines Freundes als eigenen speichern. */
	public void adoptFriend(FriendWaypoint fw) {
		menuOpen = false;
		if (fw == null) return;
		WaypointShare.Result r = WaypointShare.adopt(fw);
		flash(I18n.tr(r.key(), fw.name));
	}

	/** Wegpunkt eines Freundes unter dem Zeiger (null = keiner). */
	private FriendWaypoint friendAt(double mx, double my) {
		if (!friendWaypointsShown()) return null;
		FriendWaypoint best = null;
		double bestD = 8 * 8;
		for (FriendWaypoint fw : LiveShare.get().friendWaypoints(WaypointShare.canonicalDimension(viewDimension()))) {
			toScreen(fw.x + 0.5, fw.z + 0.5);
			double dx = point[0] - mx, dy = point[1] - my;
			double d = dx * dx + dy * dy;
			if (d <= bestD) {
				bestD = d;
				best = fw;
			}
		}
		return best;
	}

	/** „Teilen“: Wegpunkt unter dem Zeiger bzw. die angeklickte Stelle an Freunde/Gruppen schicken. */
	private void share() {
		dev.theredstonee.trsclient.core.waypoint.WaypointShare.Result r;
		if (menuWaypoint != null) {
			r = dev.theredstonee.trsclient.core.waypoint.WaypointShare.shareLocal(menuWaypoint);
		} else {
			MapLayer layer = e.surfaceLayer();
			int y = layer == null ? Integer.MIN_VALUE : e.heightAt(layer, menuBlockX, menuBlockZ);
			if (y == Integer.MIN_VALUE) y = (int) Math.floor(e.playerY());
			else y += 1;
			r = dev.theredstonee.trsclient.core.waypoint.WaypointShare.sharePosition(
					I18n.tr("waypoint.share.spot"), menuBlockX, y, menuBlockZ);
		}
		if (r != dev.theredstonee.trsclient.core.waypoint.WaypointShare.Result.OK) flash(I18n.tr(r.key()));
	}

	/** Kurzer Hinweis über der unteren Leiste (z. B. „Nicht mit TRS verbunden“, „Karte gespeichert“). */
	private String toastTitle;
	private String toastDetail;
	private int toastColor;
	private long toastUntil;

	private void flash(String text) {
		showToast(text, null, 0xFFFFB02E, 4000);
	}

	private void showToast(String title, String detail, int color, long ms) {
		toastTitle = title;
		toastDetail = detail;
		toastColor = color;
		toastUntil = System.currentTimeMillis() + ms;
	}

	/** Aktueller Hinweis (Selbsttest): Titel und zweite Zeile, null = keiner. */
	public String[] testToast() {
		return toastTitle == null || System.currentTimeMillis() >= toastUntil ? null : new String[]{toastTitle, toastDetail};
	}

	private Waypoint waypointAt(double mx, double my) {
		Waypoint best = null;
		double bestD = 8 * 8;
		for (Waypoint wp : viewWaypoints()) {
			if (!e.waypointShown(wp)) continue;
			toScreen(wp.x + 0.5, wp.z + 0.5);
			double dx = point[0] - mx, dy = point[1] - my;
			double d = dx * dx + dy * dy;
			if (d <= bestD) {
				bestD = d;
				best = wp;
			}
		}
		return best;
	}

	// --- Wegpunkt-Liste (WorldMapSidebar.Host) ---

	@Override
	public MapEngine engine() {
		return e;
	}

	@Override
	public void click() {
		host.playClick();
	}

	@Override
	public void centerOnWaypoint(Waypoint w) {
		String wd = w.dimension == null || w.dimension.isEmpty() ? viewDimension() : w.dimension;
		if (!wd.equals(viewDimension())) {
			if (!e.modules().worldMapDimensions.get() && !wd.equals(e.dimension())) {
				flash(I18n.tr("map.noMapThere"));
				return;
			}
			if (!viewDimension(wd)) {
				flash(I18n.tr("map.noMapThere"));
				return;
			}
		}
		follow = false;
		// Mitte der freien Fläche links neben der Liste.
		double shift = sidebarOpen ? WorldMapSidebar.WIDTH / 2.0 / cam.targetScale() : 0;
		cam.center(w.x + 0.5 + shift, w.z + 0.5);
		pulse = w;
		pulseUntil = System.currentTimeMillis() + 1600;
	}

	@Override
	public void editWaypoint(Waypoint w) {
		menuOpen = dimMenuOpen = exportMenuOpen = false;
		sidebar.search.setFocused(false);
		dialog.openEdit(w);
	}

	@Override
	public void centerOnFriendWaypoint(FriendWaypoint w) {
		String wd = w.dimension;
		String view = WaypointShare.canonicalDimension(viewDimension());
		if (!wd.equals(view)) {
			String local = WaypointShare.localDimension(wd, e.dimension());
			if (!e.modules().worldMapDimensions.get() && !local.equals(e.dimension()) || !viewDimension(local)) {
				flash(I18n.tr("map.noMapThere"));
				return;
			}
		}
		follow = false;
		double shift = sidebarOpen ? WorldMapSidebar.WIDTH / 2.0 / cam.targetScale() : 0;
		cam.center(w.x + 0.5 + shift, w.z + 0.5);
		pulseFriend = w;
		pulse = null;
		pulseUntil = System.currentTimeMillis() + 1600;
	}

	@Override
	public void centerOnFriend(Owner o) {
		FriendPosition p = o == null ? null : o.position();
		if (p == null || !LiveShare.live(p, System.currentTimeMillis())) {
			flash(I18n.tr("map.friend.noPosition"));
			return;
		}
		String local = WaypointShare.localDimension(p.dimension, e.dimension());
		if (!local.equals(viewDimension()) && (!e.modules().worldMapDimensions.get() && !local.equals(e.dimension()) || !viewDimension(local))) {
			flash(I18n.tr("map.noMapThere"));
			return;
		}
		follow = false;
		double shift = sidebarOpen ? WorldMapSidebar.WIDTH / 2.0 / cam.targetScale() : 0;
		cam.center(p.x + shift, p.z);
	}

	@Override
	public void adopt(FriendWaypoint w) {
		adoptFriend(w);
	}

	@Override
	public void newWaypoint() {
		double shift = sidebarOpen ? WorldMapSidebar.WIDTH / 2.0 / cam.scale() : 0;
		menuBlockX = (int) Math.floor(cam.centerX() - shift);
		menuBlockZ = (int) Math.floor(cam.centerZ());
		newAtMenuSpot();
	}

	static int parse(String s, int fallback) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	// --- Eingaben ---

	static int ladderIndex(float screenPx) {
		int best = 0;
		for (int i = 0; i < LADDER.length; i++) {
			if (Math.abs(LADDER[i] - screenPx) < Math.abs(LADDER[best] - screenPx)) best = i;
		}
		return best;
	}

	/** Eine Zoomstufe weiter (+1 = näher) um den Punkt (sx, sy) – „stufenlos“ aus. */
	private void zoomStep(int dir, float sx, float sy) {
		step = ladderIndex(cam.targetScale() * (float) guiScale());
		int next = Math.max(0, Math.min(LADDER.length - 1, step + dir));
		if (next == step) return;
		step = next;
		zoomTo(LADDER[step] / (float) guiScale(), sx, sy);
	}

	private void zoomTo(float next, float sx, float sy) {
		if (follow) {
			// Beim Zoomen um den Zeiger löst sich die Karte vom Spieler.
			follow = Math.abs(sx - width / 2f) < 1 && Math.abs(sy - height / 2f) < 1;
		}
		cam.zoomTo(next, sx - width / 2.0, sy - height / 2.0);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		cam.stop();
		if (dialog.isOpen()) {
			if (dialog.clickOutside(mx, my)) return true;
			return super.mouseClicked(mx, my, button);
		}
		if (dimMenuOpen || exportMenuOpen) {
			if (inside(mx, my, popupRect[0], popupRect[1], popupRect[2], popupRect[3])) return super.mouseClicked(mx, my, button);
			dimMenuOpen = exportMenuOpen = false;
			if (my < BAR_H) return super.mouseClicked(mx, my, button);
			return true;
		}
		if (menuOpen) {
			if (inside(mx, my, menuRect[0], menuRect[1], menuRect[2], menuRect[3])) return super.mouseClicked(mx, my, button);
			menuOpen = false;
			if (button != 1) return true;
		}
		boolean inSidebar = overSidebar(mx, my);
		if (!inSidebar) sidebar.search.setFocused(false);
		if (super.mouseClicked(mx, my, button)) return true;
		if (inSidebar) return true;
		if (my < BAR_H || my >= height - BAR_H) return false;
		if (button == 1) {
			menuOpen = true;
			menuX = (int) mx;
			menuY = (int) my;
			menuBlockX = (int) Math.floor(worldX(mx));
			menuBlockZ = (int) Math.floor(worldZ(my));
			menuWaypoint = waypointAt(mx, my);
			menuFriend = menuWaypoint == null ? friendAt(mx, my) : null;
			return true;
		}
		if (button == 0) {
			cam.beginDrag(mx, my, System.nanoTime());
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button) {
		if (!cam.dragging()) return super.mouseDragged(mx, my, button);
		if (follow) {
			// Ab der ersten Bewegung folgt die Karte nicht mehr dem Spieler.
			double f = MapDimensions.factor(e.dimension(), viewDimension());
			if (!Double.isNaN(f)) cam.follow(e.playerX() * f, e.playerZ() * f);
			follow = false;
		}
		cam.dragTo(mx, my, System.nanoTime());
		return true;
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		if (cam.dragging()) cam.endDrag(System.nanoTime(), e.modules().worldMapInertia.get());
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (dialog.isOpen() || amount == 0) return false;
		if (overSidebar(mx, my)) return sidebar.scroll(amount);
		menuOpen = dimMenuOpen = exportMenuOpen = false;
		if (smoothZoom()) {
			double factor = MapCamera.wheelFactor(amount);
			float next = cam.clamp((float) (cam.targetScale() * factor));
			zoomTo(next, (float) mx, (float) my);
		} else {
			zoomStep(amount > 0 ? 1 : -1, (float) mx, (float) my);
		}
		return true;
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (dialog.isOpen()) return dialog.key(key, shift);
		if (sidebarOpen && sidebar.search.focused()) return sidebar.key(key);
		if (key == UiKey.ESCAPE) {
			if (menuOpen || dimMenuOpen || exportMenuOpen) {
				menuOpen = dimMenuOpen = exportMenuOpen = false;
				return true;
			}
			requestClose();
			return true;
		}
		if (host.isMapKey(rawKey)) {
			requestClose();
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(char ch) {
		if (dialog.isOpen()) return dialog.type(ch);
		if (sidebarOpen && sidebar.search.focused()) return sidebar.type(ch);
		return false;
	}

	@Override
	public void requestClose() {
		super.requestClose();
		e.setWorldMapOpen(false);
	}

	@Override
	protected void onClosed() {
		closeForeign();
		e.setWorldMapOpen(false);
		host.closeScreen();
	}

	// --- Für den Selbsttest ---

	/** Zoomt sofort (ohne Animation) auf {@code s} Bildschirmpixel je Block (auf die Stufenleiter gerundet). */
	public void setScaleNow(float screenPxPerBlock) {
		step = ladderIndex(screenPxPerBlock);
		cam.setScaleNow(LADDER[step] / (float) guiScale());
	}

	/** Zoomt sofort auf genau {@code s} Bildschirmpixel je Block (stufenlos, Selbsttest). */
	public void setScreenPxNow(float screenPxPerBlock) {
		cam.setScaleNow(screenPxPerBlock / (float) guiScale());
	}

	/** Stufenloser Zoom wie mit dem Mausrad an einer Bildschirmposition (Selbsttest, animiert). */
	public void testScroll(double notches, int sx, int sy) {
		mouseScrolled(sx, sy, notches);
	}

	/** Bildschirmpixel je Block (Ziel der Animation) – Selbsttest. */
	public float testScreenPx() {
		return cam.targetScale() * (float) guiScale();
	}

	/** Weltpunkt unter einer Bildschirmposition (Selbsttest). */
	public double[] testWorldAt(int sx, int sy) {
		return new double[]{worldX(sx), worldZ(sy)};
	}

	/** Öffnet den Dialog für einen neuen Wegpunkt an dieser Position (Selbsttest). */
	public void testOpenDialog(int bx, int bz) {
		menuOpen = false;
		menuBlockX = bx;
		menuBlockZ = bz;
		newAtMenuSpot();
	}

	/** Selbsttest: Dialog für einen vorhandenen Wegpunkt öffnen. */
	public void testEditDialog(Waypoint w) {
		dialog.openEdit(w);
	}

	/** Selbsttest: freien Farbwähler im offenen Dialog zeigen. */
	public void testColorPicker() {
		dialog.testPicker();
	}

	/** Selbsttest: Einträge des offenen Kontextmenüs. */
	public List<String> testMenuItems() {
		return menuItems();
	}

	/** Selbsttest: Kontextmenü auf freier Stelle mit dieser Blockposition öffnen. */
	public void testSpotMenu(int sx, int sy, int bx, int bz) {
		menuOpen = true;
		menuX = sx;
		menuY = sy;
		menuBlockX = bx;
		menuBlockZ = bz;
		menuWaypoint = null;
		menuFriend = null;
	}

	/** Selbsttest: Eintrag des offenen Kontextmenüs ausführen. */
	public void testMenuAction(String id) {
		menuOpen = false;
		menuAction(id);
	}

	/** Öffnet das Kontextmenü an einer Bildschirmposition (Selbsttest). */
	public void testContextMenu(int sx, int sy) {
		mouseClicked(sx, sy, 1);
	}

	/** Wegpunkt-Liste auf/zu und Suchtext setzen (Selbsttest; ändert die Einstellung nicht). */
	public void testSidebar(boolean open, String query) {
		sidebarOpen = open;
		sidebar.search.setText(query == null ? "" : query);
		sidebar.search.setFocused(false);
	}

	/** Selbsttest: Wegpunkt-Liste öffnen und Reiter „Freunde“ (true) bzw. „Meine“ wählen. */
	public void testSidebarFriends(boolean friends) {
		sidebarOpen = true;
		sidebar.search.setText("");
		sidebar.search.setFocused(false);
		sidebar.setFriendsTab(friends);
	}

	/** Zeilen im Reiter „Freunde“ (Kopfzeilen + Wegpunkte, nach dem letzten Bild). */
	public int testFriendRows() {
		return sidebar.friendRows().size();
	}

	/** Selbsttest: Kontextmenü auf einem Wegpunkt eines Freundes öffnen. */
	public void testFriendMenu(FriendWaypoint fw) {
		toScreen(fw.x + 0.5, fw.z + 0.5);
		menuOpen = true;
		menuX = (int) point[0];
		menuY = (int) point[1];
		menuBlockX = fw.x;
		menuBlockZ = fw.z;
		menuWaypoint = null;
		menuFriend = fw;
	}

	/** Anzahl der Zeilen in der Wegpunkt-Liste (nach dem letzten Bild). */
	public int testListRows() {
		return sidebar.rows().size();
	}

	/** Gespeicherte Dimensionen, die die Karte anbietet (nach dem Einlesen). */
	public List<String> testDimensions() {
		return dimensionChoices();
	}

	/** Welche Kachelstufe zuletzt gezeichnet wurde (0 = Bereiche, 1 = Übersicht, 2 = Weitkacheln). */
	public int testTileLevel() {
		return tiles.lastLevel;
	}
}
