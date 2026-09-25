package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Anim;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.FadeCanvas;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Vollbild-Weltkarte: alles Erkundete dieser Welt/Dimension (auch von der Platte), flüssig ziehen und zoomen
 * (um den Mauszeiger), Koordinaten unter dem Zeiger, auf den Spieler zentrieren, Wegpunkte per Rechtsklick
 * anlegen/bearbeiten/löschen, Spieler und Kreaturen wie auf der Minimap. Nah: ein Texel je Block, weit: Kacheln
 * aus Übersichten (ein Texel je 4 Blöcke).
 */
public final class WorldMapUi extends UiScreen {
	/** Verbindung zum Loader. */
	public interface Host {
		void closeScreen();

		void playClick();

		/** Ist diese Taste die Weltkarten-Taste (schließt wieder)? */
		boolean isMapKey(int rawKey);
	}

	static final float MIN_SCALE = 0.03f;
	static final float MAX_SCALE = 16f;
	/**
	 * Zoomstufen in Bildschirmpixeln je Block – ganzzahlige Vielfache bzw. Teiler, damit jeder Block gleich breit ist
	 * (kein Flimmern beim Ziehen). Die GUI-Skalierung wird herausgerechnet.
	 */
	static final float[] LADDER = {0.125f, 0.25f, 0.5f, 1f, 2f, 3f, 4f, 6f, 8f, 12f, 16f};
	/** Ab diesem Zoom (Bildschirmpixel je Block) werden die vollen Bereichs-Texturen gezeichnet. */
	static final float DETAIL_SCALE = 1f;
	static final int BAR_H = 22;
	static final int[] COLORS = {0xE0281E, 0xFF7A1F, 0xFFB84D, 0xF2E14C, 0x3DDC84, 0x2FA85A, 0x4DD8E0, 0x3D7BFF,
			0xB07CFF, 0xFF6FB5, 0xFFFFFF, 0xB8B8C8};

	private final MapEngine e;
	private final Host host;
	private double centerX, centerZ;
	private boolean follow = true;
	private float scale = 2f;
	private float targetScale = 2f;
	private boolean anchored;
	private double anchorWX, anchorWZ;
	private float anchorSX, anchorSY;
	private boolean surfaceOnly;
	private int step;

	private boolean dragging;
	private double dragX, dragY;
	private int width, height;
	private int mouseX, mouseY;

	// Kontextmenü (Rechtsklick).
	private boolean menuOpen;
	private int menuX, menuY;
	private int menuBlockX, menuBlockZ;
	private Waypoint menuWaypoint;
	private final int[] menuRect = new int[4];

	// Wegpunkt-Dialog.
	private boolean dialogOpen;
	private Waypoint editing;
	private final TextInput name = new TextInput(32);
	private final TextInput inX = new TextInput(9);
	private final TextInput inY = new TextInput(6);
	private final TextInput inZ = new TextInput(9);
	private int color = COLORS[0];
	private final int[] dialogRect = new int[4];

	private final double[] point = new double[2];
	private final List<String> hover = new ArrayList<String>();

	public WorldMapUi(MapEngine engine, Host host) {
		this.e = engine;
		this.host = host;
		step = ladderIndex(Math.max(2f, engine.modules().minimapZoom.get().pixelsPerBlock()));
		scale = targetScale = LADDER[step] / (float) guiScale();
		centerX = engine.playerX();
		centerZ = engine.playerZ();
		engine.setWorldMapOpen(true);
	}

	private MapLayer layer() {
		if (!surfaceOnly && e.caveActive()) return e.viewLayer();
		return e.surfaceLayer();
	}

	private double guiScale() {
		MapPlatform p = e.platform();
		return p == null ? 2.0 : p.guiScale();
	}

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

		// Zoom weich, um den Anker (Mauszeiger) herum.
		float before = scale;
		scale = Anim.approach(scale, targetScale, dt, 0.08f);
		if (Math.abs(scale - targetScale) < 0.0005f) scale = targetScale;
		if (anchored && scale != before) {
			centerX = anchorWX - (anchorSX - w / 2f) / scale;
			centerZ = anchorWZ - (anchorSY - h / 2f) / scale;
		}
		if (scale == targetScale) anchored = false;
		if (follow && e.inWorld()) {
			centerX = e.playerX();
			centerZ = e.playerZ();
		}

		c.fill(0, 0, w, h, 0xFF0B0A0E);
		MapLayer layer = layer();
		if (layer == null || !e.inWorld()) {
			Paint.textCentered(c, I18n.tr("map.noWorld"), w / 2, h / 2 - 4, t.textDim, false);
			chrome(c, w, h, mx, my, t, null);
			return;
		}
		double s = guiScale();
		hover.clear();
		drawMap(c, layer, w, h, now);
		if (e.modules().worldMapGrid.get() && scale * s >= 6f) grid(c, w, h);
		markers(c, w, h, mx, my, s);
		chrome(c, w, h, mx, my, t, layer);
		// Überlagerungen nach vorn heben: Minecraft zeichnet GUI-Text mit eigener Tiefe.
		c.push();
		c.raise(300);
		if (!hover.isEmpty() && !menuOpen && !dialogOpen) tooltip(c, mx, my, t);
		if (menuOpen) menu(c, mx, my, t);
		if (dialogOpen) dialog(c, w, h, mx, my, t);
		c.pop();
	}

	// --- Karte ---

	private void drawMap(Canvas c, MapLayer layer, int w, int h, long now) {
		if (!c.images()) return;
		double wx0 = centerX - w / 2.0 / scale, wx1 = centerX + w / 2.0 / scale;
		double wz0 = centerZ - (h / 2.0) / scale, wz1 = centerZ + (h / 2.0) / scale;
		MapTextures tex = e.textures();
		tex.beginFrame(5);
		c.push();
		c.translate(w / 2f, h / 2f);
		c.scale(scale, scale);
		float screenPx = scale * (float) guiScale();
		if (screenPx >= DETAIL_SCALE) {
			// Unter den Bereichen die Übersicht (falls ein Bereich noch lädt) – nur bei mittlerem Zoom sichtbar.
			if (screenPx < 3f) supers(c, layer, wx0, wz0, wx1, wz1, now);
			MinimapRenderer.drawPieces(c, e, layer, wx0, wz0, wx1, wz1, centerX, centerZ, 0xFFFFFFFF, now);
		} else {
			supers(c, layer, wx0, wz0, wx1, wz1, now);
		}
		c.pop();
	}

	private void supers(Canvas c, MapLayer layer, double wx0, double wz0, double wx1, double wz1, long now) {
		int span = MapTextures.SUPER * MapRegion.SIZE;
		int sx0 = (int) Math.floor(wx0 / span), sx1 = (int) Math.floor(wx1 / span);
		int sz0 = (int) Math.floor(wz0 / span), sz1 = (int) Math.floor(wz1 / span);
		// Nur Kacheln, in denen es überhaupt Bereiche gibt.
		java.util.Set<Long> known = layer.knownKeys();
		for (int sz = sz0; sz <= sz1; sz++) {
			for (int sx = sx0; sx <= sx1; sx++) {
				if (!anyIn(known, sx, sz)) continue;
				TextureRef ref = e.textures().superTile(layer, sx, sz, now);
				if (ref == null) continue;
				c.push();
				c.translate((float) (sx * (double) span - centerX), (float) (sz * (double) span - centerZ));
				float k = (float) span / MapTextures.SUPER_PIXELS;
				c.scale(k, k);
				c.image(ref, 0, 0, MapTextures.SUPER_PIXELS, MapTextures.SUPER_PIXELS, 0xFFFFFFFF);
				c.pop();
			}
		}
	}

	private static boolean anyIn(java.util.Set<Long> known, int sx, int sz) {
		for (int rz = 0; rz < MapTextures.SUPER; rz++) {
			for (int rx = 0; rx < MapTextures.SUPER; rx++) {
				if (known.contains(MapRegion.key(sx * MapTextures.SUPER + rx, sz * MapTextures.SUPER + rz))) return true;
			}
		}
		return false;
	}

	private void grid(Canvas c, int w, int h) {
		int alphaChunk = scale * guiScale() >= 12f ? 0x28 : 0x18;
		double wx0 = centerX - w / 2.0 / scale, wz0 = centerZ - h / 2.0 / scale;
		int first = (int) Math.floor(wx0 / 16) * 16;
		for (int x = first; x <= wx0 + w / scale + 16; x += 16) {
			int sx = (int) Math.round((x - centerX) * scale + w / 2.0);
			if (sx < 0 || sx >= w) continue;
			int a = (x & (MapRegion.SIZE - 1)) == 0 ? 0x50 : alphaChunk;
			c.fill(sx, BAR_H, sx + 1, h - BAR_H, a << 24);
		}
		int firstZ = (int) Math.floor(wz0 / 16) * 16;
		for (int z = firstZ; z <= wz0 + h / scale + 16; z += 16) {
			int sy = (int) Math.round((z - centerZ) * scale + h / 2.0);
			if (sy < BAR_H || sy >= h - BAR_H) continue;
			int a = (z & (MapRegion.SIZE - 1)) == 0 ? 0x50 : alphaChunk;
			c.fill(0, sy, w, sy + 1, a << 24);
		}
	}

	private void toScreen(double wx, double wz) {
		point[0] = (wx - centerX) * scale + width / 2.0;
		point[1] = (wz - centerZ) * scale + height / 2.0;
	}

	private double worldX(double sx) {
		return centerX + (sx - width / 2.0) / scale;
	}

	private double worldZ(double sy) {
		return centerZ + (sy - height / 2.0) / scale;
	}

	private boolean onScreen(float margin) {
		return point[0] >= -margin && point[0] <= width + margin && point[1] >= -margin && point[1] <= height + margin;
	}

	private void markers(Canvas c, int w, int h, int mx, int my, double s) {
		TrsModules m = e.modules();
		MapSprites sprites = e.sprites();
		double px = e.playerX(), pz = e.playerZ();
		// Wegpunkte.
		if (m.worldMapWaypoints.get()) {
			Waypoint hovered = null;
			double hoveredD = 6.5 * 6.5;
			for (Waypoint wp : e.waypoints()) {
				if (!wp.visible && !wp.death) continue;
				toScreen(wp.x + 0.5, wp.z + 0.5);
				if (!onScreen(20)) continue;
				float x = (float) point[0], y = (float) point[1];
				if (wp.death) sprites.draw(c, MapSprites.GRAVE, x, y, 11f, 0f, 0xFFFFFFFF, s);
				else sprites.draw(c, MapSprites.DIAMOND, x, y, 10f, 0f, 0xFF000000 | wp.color, s);
				String label = wp.name;
				if (scale * s >= 0.7f || Math.abs(mx - x) < 8 && Math.abs(my - y) < 8) {
					int lw = c.textWidth(label);
					c.fill(Math.round(x - lw / 2f - 2), Math.round(y + 7), Math.round(x + lw / 2f + 2), Math.round(y + 17), 0xA0000000);
					c.text(label, Math.round(x - lw / 2f), Math.round(y + 8), 0xFF000000 | wp.color, false);
				}
				double d = (mx - x) * (mx - x) + (my - y) * (my - y);
				if (d <= hoveredD) {
					hoveredD = d;
					hovered = wp;
				}
			}
			// Nur der nächste Wegpunkt unter dem Zeiger bekommt den Hinweis.
			if (hovered != null) {
				hover.add(hovered.name);
				hover.add(I18n.tr("map.distance", distance(hovered.x + 0.5 - px, hovered.z + 0.5 - pz)));
				hover.add(hovered.x + " / " + hovered.y + " / " + hovered.z);
			}
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
					c.text(en.name, Math.round(x - lw / 2f), Math.round(y + 7), en.friend ? MinimapRenderer.FRIEND : 0xFFFFFFFF, true);
				}
			}
		}
		// Eigene Position.
		toScreen(px, pz);
		float yawRad = (float) Math.toRadians(e.yaw());
		sprites.draw(c, MapSprites.ARROW, (float) point[0], (float) point[1], 13f, yawRad + (float) Math.PI,
				0xFF000000 | (Theme.get().dustOn & 0xFFFFFF), s);
	}

	private static String distance(double dx, double dz) {
		long d = Math.round(Math.sqrt(dx * dx + dz * dz));
		return d >= 10000 ? String.format(Locale.ROOT, "%.1fk", d / 1000.0) : Long.toString(d);
	}

	// --- Leisten ---

	private void chrome(Canvas c, int w, int h, int mx, int my, Theme t, MapLayer layer) {
		// Oben: Titel, Ebene, Knöpfe.
		c.fill(0, 0, w, BAR_H, 0xE0141217);
		c.fill(0, BAR_H - 1, w, BAR_H, ColorMath.withAlpha(t.dustOn, 0xB0));
		Icons.draw(c, "map", 8, 7, 1, 0xFF000000 | (t.dustOn & 0xFFFFFF));
		String title = I18n.tr("map.title");
		c.text(title, 20, 7, t.text, false);
		int x = 26 + c.textWidth(title);
		String dim = dimensionName(e.dimension());
		if (layer != null && layer.cave()) dim += " · " + I18n.tr("map.cave");
		c.text(dim, x, 7, t.textDim, false);

		int bx = w - 6;
		bx = button(c, bx, "close", I18n.tr("map.close"), mx, my, t, this::requestClose);
		bx = button(c, bx, "home", I18n.tr("map.center"), mx, my, t, () -> {
			follow = true;
			anchored = false;
		});
		bx = button(c, bx, "plus", I18n.tr("map.zoomIn"), mx, my, t, () -> zoomStep(1, w / 2f, h / 2f));
		bx = textButton(c, bx, "-", I18n.tr("map.zoomOut"), mx, my, t, () -> zoomStep(-1, w / 2f, h / 2f));
		if (e.caveActive()) {
			bx = button(c, bx, surfaceOnly ? "layers" : "sun", I18n.tr(surfaceOnly ? "map.showCave" : "map.showSurface"), mx, my, t,
					() -> surfaceOnly = !surfaceOnly);
		}

		// Unten: Koordinaten unter dem Zeiger, Maßstab, Hinweise, Fair Play.
		c.fill(0, h - BAR_H, w, h, 0xE0141217);
		if (layer != null && my > BAR_H && my < h - BAR_H) {
			int bxw = (int) Math.floor(worldX(mx)), bzw = (int) Math.floor(worldZ(my));
			int y = e.heightAt(layer, bxw, bzw);
			String pos = y == Integer.MIN_VALUE ? I18n.tr("map.cursor", bxw, bzw) : I18n.tr("map.cursorY", bxw, y, bzw);
			c.text(pos, 8, h - BAR_H + 7, t.text, false);
		}
		String hint = I18n.tr("map.hint");
		float screenPx = LADDER[step];
		String zoom = screenPx >= 1f ? Math.round(screenPx) + " px/" + I18n.tr("map.block") : "1:" + Math.round(1 / screenPx);
		String right = zoom;
		FairPlay fp = e.fairPlay();
		if (fp.active()) right = I18n.tr(fp.serverFair() ? "map.fairPlayServer" : "map.fairPlay") + "  ·  " + right;
		int rw = c.textWidth(right);
		c.text(right, w - 8 - rw, h - BAR_H + 7, fp.active() ? (0xFF000000 | (t.dustOn & 0xFFFFFF)) : t.textDim, false);
		int hw = c.textWidth(hint);
		if (w / 2 + hw / 2 < w - 16 - rw && w / 2 - hw / 2 > 150) c.text(hint, w / 2 - hw / 2, h - BAR_H + 7, t.textDim, false);
	}

	private int button(Canvas c, int right, String icon, String tip, int mx, int my, Theme t, Runnable action) {
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

	static String dimensionName(String dim) {
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

	// --- Kontextmenü ---

	private List<String> menuItems() {
		List<String> items = new ArrayList<String>();
		if (menuWaypoint != null) {
			items.add("edit");
			items.add(menuWaypoint.visible ? "hide" : "show");
			items.add("delete");
		} else {
			items.add("create");
		}
		items.add("centerHere");
		items.add("centerPlayer");
		return items;
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
		String head = menuWaypoint != null ? menuWaypoint.name : menuBlockX + " / " + menuBlockZ;
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
		if ("create".equals(id)) {
			openDialog(null);
		} else if ("edit".equals(id)) {
			openDialog(menuWaypoint);
		} else if ("hide".equals(id) || "show".equals(id)) {
			menuWaypoint.visible = !menuWaypoint.visible;
			e.waypointEdited();
		} else if ("delete".equals(id)) {
			e.removeWaypoint(menuWaypoint);
		} else if ("centerHere".equals(id)) {
			follow = false;
			centerX = menuBlockX + 0.5;
			centerZ = menuBlockZ + 0.5;
		} else if ("centerPlayer".equals(id)) {
			follow = true;
		}
	}

	private Waypoint waypointAt(double mx, double my) {
		Waypoint best = null;
		double bestD = 8 * 8;
		for (Waypoint wp : e.waypoints()) {
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

	// --- Wegpunkt-Dialog ---

	private void openDialog(Waypoint wp) {
		editing = wp;
		dialogOpen = true;
		if (wp != null) {
			name.setText(wp.name);
			inX.setText(Integer.toString(wp.x));
			inY.setText(Integer.toString(wp.y));
			inZ.setText(Integer.toString(wp.z));
			color = wp.color;
		} else {
			name.setText(Waypoint.defaultName());
			inX.setText(Integer.toString(menuBlockX));
			int y = e.heightAt(layer(), menuBlockX, menuBlockZ);
			inY.setText(Integer.toString(y == Integer.MIN_VALUE ? (int) Math.floor(e.playerY()) : y + 1));
			inZ.setText(Integer.toString(menuBlockZ));
			color = COLORS[(e.waypoints().size()) % COLORS.length];
		}
		focus(name);
	}

	private void focus(TextInput input) {
		name.setFocused(input == name);
		inX.setFocused(input == inX);
		inY.setFocused(input == inY);
		inZ.setFocused(input == inZ);
	}

	private TextInput focused() {
		if (name.focused()) return name;
		if (inX.focused()) return inX;
		if (inY.focused()) return inY;
		if (inZ.focused()) return inZ;
		return null;
	}

	private void dialog(Canvas c, int w, int h, int mx, int my, Theme t) {
		c.fill(0, 0, w, h, 0x80000000);
		int dw = 240, dh = 150;
		int x = (w - dw) / 2, y = (h - dh) / 2;
		dialogRect[0] = x;
		dialogRect[1] = y;
		dialogRect[2] = dw;
		dialogRect[3] = dh;
		c.flush();
		c.push();
		c.raise(100);
		Redstone.window(c, x, y, dw, dh);
		String title = I18n.tr(editing == null ? "map.newWaypoint" : "map.editWaypoint");
		c.text(title, x + 10, y + 8, t.text, false);
		int fy = y + 24;
		c.text(I18n.tr("waypoint.name"), x + 10, fy, t.textDim, false);
		field(c, name, x + 10, fy + 10, dw - 20, t);
		fy += 34;
		int fw = (dw - 20 - 8) / 3;
		c.text("X", x + 10, fy, t.textDim, false);
		c.text("Y", x + 10 + fw + 4, fy, t.textDim, false);
		c.text("Z", x + 10 + 2 * (fw + 4), fy, t.textDim, false);
		field(c, inX, x + 10, fy + 10, fw, t);
		field(c, inY, x + 10 + fw + 4, fy + 10, fw, t);
		field(c, inZ, x + 10 + 2 * (fw + 4), fy + 10, fw, t);
		fy += 34;
		// Farben.
		int sw = (dw - 20) / COLORS.length;
		for (int i = 0; i < COLORS.length; i++) {
			final int col = COLORS[i];
			int sx = x + 10 + i * sw;
			boolean sel = (color & 0xFFFFFF) == col;
			Redstone.block(c, sx + 1, fy, sw - 2, 12, 0xFF000000 | col);
			if (sel) Redstone.frame(c, sx, fy - 1, sw, 14, 0xFFFFFFFF);
			hits.add(sx, fy - 1, sw, 14, () -> {
				color = col;
				host.playClick();
			});
		}
		fy += 20;
		// Knöpfe.
		int bw = 64;
		int by = y + dh - 24;
		boolean hovSave = inside(mx, my, x + dw - 10 - bw, by, bw, 16);
		Redstone.button(c, x + dw - 10 - bw, by, bw, 16, I18n.tr("map.save"), true, hovSave);
		hits.add(x + dw - 10 - bw, by, bw, 16, this::saveDialog);
		boolean hovCancel = inside(mx, my, x + dw - 14 - 2 * bw, by, bw, 16);
		Redstone.button(c, x + dw - 14 - 2 * bw, by, bw, 16, I18n.tr("map.cancel"), false, hovCancel);
		hits.add(x + dw - 14 - 2 * bw, by, bw, 16, () -> {
			host.playClick();
			dialogOpen = false;
		});
		if (editing != null) {
			boolean hovDel = inside(mx, my, x + 10, by, bw, 16);
			Redstone.button(c, x + 10, by, bw, 16, I18n.tr("map.menu.delete"), false, hovDel);
			hits.add(x + 10, by, bw, 16, () -> {
				host.playClick();
				e.removeWaypoint(editing);
				dialogOpen = false;
			});
		}
		c.pop();
	}

	private void field(Canvas c, final TextInput input, int x, int y, int w, Theme t) {
		Redstone.well(c, x, y, w, 16, input.focused() ? t.accent : t.border);
		String clipped = c.clip(input.text(), w - 8);
		c.text(clipped, x + 4, y + 4, t.text, false);
		if (input.focused() && (System.currentTimeMillis() / 500) % 2 == 0) {
			int cursorX = x + 4 + c.textWidth(input.text().substring(0, Math.min(input.cursor(), input.text().length())));
			if (cursorX < x + w - 2) c.fill(cursorX, y + 3, cursorX + 1, y + 13, t.text);
		}
		hits.add(x, y, w, 16, () -> focus(input));
	}

	private void saveDialog() {
		host.playClick();
		int x = parse(inX.text(), editing != null ? editing.x : menuBlockX);
		int y = parse(inY.text(), editing != null ? editing.y : (int) Math.floor(e.playerY()));
		int z = parse(inZ.text(), editing != null ? editing.z : menuBlockZ);
		String n = name.text().trim().isEmpty() ? Waypoint.defaultName() : name.text().trim();
		if (editing == null) {
			e.addWaypoint(n, x, y, z, color & 0xFFFFFF);
		} else {
			editing.name = n;
			editing.x = x;
			editing.y = y;
			editing.z = z;
			editing.color = color & 0xFFFFFF;
			e.waypointEdited();
		}
		dialogOpen = false;
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

	/** Eine Zoomstufe weiter (+1 = näher) um den Punkt (sx, sy). */
	private void zoomStep(int dir, float sx, float sy) {
		int next = Math.max(0, Math.min(LADDER.length - 1, step + dir));
		if (next == step) return;
		step = next;
		zoomTo(LADDER[step] / (float) guiScale(), sx, sy);
	}

	private void zoomTo(float next, float sx, float sy) {
		next = Math.max(MIN_SCALE, Math.min(MAX_SCALE, next));
		if (next == targetScale) return;
		anchorWX = worldX(sx);
		anchorWZ = worldZ(sy);
		anchorSX = sx;
		anchorSY = sy;
		anchored = true;
		if (follow) {
			// Beim Zoomen um den Zeiger löst sich die Karte vom Spieler.
			follow = sx == width / 2f && sy == height / 2f;
		}
		targetScale = next;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (dialogOpen) {
			if (!inside(mx, my, dialogRect[0], dialogRect[1], dialogRect[2], dialogRect[3])) {
				dialogOpen = false;
				return true;
			}
			focus(null);
			return super.mouseClicked(mx, my, button);
		}
		if (menuOpen) {
			if (inside(mx, my, menuRect[0], menuRect[1], menuRect[2], menuRect[3])) return super.mouseClicked(mx, my, button);
			menuOpen = false;
			if (button != 1) return true;
		}
		if (super.mouseClicked(mx, my, button)) return true;
		if (my < BAR_H || my >= height - BAR_H) return false;
		if (button == 1) {
			menuOpen = true;
			menuX = (int) mx;
			menuY = (int) my;
			menuBlockX = (int) Math.floor(worldX(mx));
			menuBlockZ = (int) Math.floor(worldZ(my));
			menuWaypoint = waypointAt(mx, my);
			return true;
		}
		if (button == 0) {
			dragging = true;
			dragX = mx;
			dragY = my;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button) {
		if (!dragging) return super.mouseDragged(mx, my, button);
		double dx = mx - dragX, dy = my - dragY;
		if (dx != 0 || dy != 0) {
			if (follow) {
				centerX = e.playerX();
				centerZ = e.playerZ();
			}
			follow = false;
			anchored = false;
			centerX -= dx / scale;
			centerZ -= dy / scale;
			dragX = mx;
			dragY = my;
		}
		return true;
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		dragging = false;
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (dialogOpen || amount == 0) return false;
		menuOpen = false;
		zoomStep(amount > 0 ? 1 : -1, (float) mx, (float) my);
		return true;
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (dialogOpen) {
			if (key == UiKey.ESCAPE) {
				dialogOpen = false;
				return true;
			}
			if (key == UiKey.ENTER) {
				saveDialog();
				return true;
			}
			if (key == UiKey.TAB) {
				TextInput f = focused();
				TextInput[] order = {name, inX, inY, inZ};
				int i = 0;
				for (int k = 0; k < order.length; k++) if (order[k] == f) i = k;
				focus(order[(i + (shift ? order.length - 1 : 1)) % order.length]);
				return true;
			}
			TextInput f = focused();
			return f != null && f.key(key);
		}
		if (key == UiKey.ESCAPE) {
			if (menuOpen) {
				menuOpen = false;
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
		if (!dialogOpen) return false;
		TextInput f = focused();
		if (f == null) return false;
		if (f != name && !(Character.isDigit(ch) || ch == '-')) return true;
		return f.type(ch);
	}

	@Override
	public void requestClose() {
		super.requestClose();
		e.setWorldMapOpen(false);
	}

	@Override
	protected void onClosed() {
		e.setWorldMapOpen(false);
		host.closeScreen();
	}

	// --- Für den Selbsttest ---

	/** Zoomt sofort (ohne Animation) auf {@code s} GUI-Pixel je Block. */
	public void setScaleNow(float screenPxPerBlock) {
		step = ladderIndex(screenPxPerBlock);
		scale = targetScale = LADDER[step] / (float) guiScale();
		anchored = false;
	}

	/** Öffnet den Dialog für einen neuen Wegpunkt an dieser Position (Selbsttest). */
	public void testOpenDialog(int bx, int bz) {
		menuOpen = false;
		menuBlockX = bx;
		menuBlockZ = bz;
		openDialog(null);
	}

	/** Öffnet das Kontextmenü an einer Bildschirmposition (Selbsttest). */
	public void testContextMenu(int sx, int sy) {
		mouseClicked(sx, sy, 1);
	}
}
