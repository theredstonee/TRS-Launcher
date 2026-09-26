package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.format.HudFormat;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Anim;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;

import java.util.List;

/**
 * Zeichnet die Minimap in jeder Minecraft-Version gleich: Bereichs-Texturen (ein Texel je Block) werden mit
 * Drehung, Zoom und Bruchteil-Verschiebung gezeichnet (kein Ruckeln, kein Neuaufbau je Bild), rund über
 * Streifen ({@link MapShapes}) unter einem Redstone-Rahmen, eckig über einen Scissor. Darüber Wegpunkte,
 * Todespunkt, Spieler (Gesichter, Freunde hervorgehoben), Kreaturen, Himmelsrichtungen und die eigene Position.
 */
public final class MinimapRenderer {
	/** Scissor im lokalen Koordinatensystem des HUD-Elements (false = nicht möglich → Streifen). */
	public interface Clip {
		boolean begin(int x1, int y1, int x2, int y2);

		void end();
	}

	/** Unterauflösung der Textur-Ausschnitte (1/16 Block genau). */
	static final int SUB = 16;
	static final int LINE_H = 10;
	static final int HOSTILE = 0xFFE5483E;
	static final int PASSIVE = 0xFFEDE6D2;
	static final int FRIEND = 0xFFFFC23D;

	private final float[] bands = new float[4 * 512];
	private float zoom = -1f;
	private float spin = -1f;
	private long lastFrame;
	private final double[] point = new double[2];

	// Zwischengespeicherte Texte (nur bei Änderung neu).
	private String coordsText = "";
	private long coordsKey = Long.MIN_VALUE;
	private String timeText = "";
	private long timeKey = Long.MIN_VALUE;

	private static boolean round(TrsModules m) {
		return m.minimapShape.get() == TrsModules.MapShape.ROUND;
	}

	/** Rand um die Karte (Rahmen) in GUI-Pixeln. */
	static int pad(TrsModules m) {
		return round(m) ? (int) Math.ceil(MapSprites.RING_OUT) : 3;
	}

	public int width(TrsModules m) {
		return m.minimapSize.getInt() + 2 * pad(m);
	}

	public int height(TrsModules m) {
		int lines = 0;
		if (m.minimapCoords.get()) lines++;
		if (m.minimapBiome.get()) lines++;
		if (m.minimapTime.get()) lines++;
		return width(m) + (lines > 0 ? lines * LINE_H + 2 : 0);
	}

	/**
	 * @param scale Bildschirmpixel je GUI-Pixel (GUI-Skalierung × HUD-Größe) – für scharfe Rahmen/Symbole
	 * @param clip Scissor für die gedrehte eckige Karte oder null
	 */
	public void draw(Canvas c, MapEngine e, boolean preview, double scale, Clip clip) {
		long t0 = System.nanoTime();
		TrsModules m = e.modules();
		long nowMs = System.currentTimeMillis();
		float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (t0 - lastFrame) / 1e9f);
		lastFrame = t0;

		int size = m.minimapSize.getInt();
		int pad = pad(m);
		boolean round = round(m);
		float radius = size / 2f;
		float cx = pad + radius, cy = pad + radius;
		int opacity = Math.round(m.minimapOpacity.getFloat() * 2.55f);
		Theme theme = Theme.get();

		// Zoom in Bildschirmpixeln je Block (ganzzahlig → gestochen scharf), umgerechnet in GUI-Pixel.
		float target = (float) (m.minimapZoom.get().pixelsPerBlock() / Math.max(0.5, scale));
		zoom = zoom < 0 ? target : Anim.approach(zoom, target, dt, 0.09f);
		// Drehen an/aus weich überblenden.
		float spinTarget = m.minimapRotate.get() ? 1f : 0f;
		spin = spin < 0 ? spinTarget : Anim.approach(spin, spinTarget, dt, 0.12f);

		MapLayer layer = e.viewLayer();
		boolean live = layer != null && e.inWorld();
		double px = live ? e.playerX() : 0, pz = live ? e.playerZ() : 0;
		float yawRad = (float) Math.toRadians(live ? e.yaw() : 180f);
		float theta = (float) ((Math.PI - yawRad) * spin);

		// Hintergrund.
		int bg = ColorMath.withAlpha(0xFF101014, Math.round(opacity * 0.9f));
		if (round) {
			if (!e.sprites().drawDisc(c, cx, cy, radius, scale, bg)) c.fill(pad, pad, pad + size, pad + size, bg);
		} else {
			c.fill(pad, pad, pad + size, pad + size, bg);
		}

		if (live) {
			drawMap(c, e, layer, cx, cy, radius, round, theta, px, pz, opacity, nowMs, clip);
		}

		// Rahmen.
		if (round) {
			if (!e.sprites().drawRing(c, cx, cy, radius, scale, 255)) squareFrame(c, pad, size, theme);
		} else {
			squareFrame(c, pad, size, theme);
		}

		if (live) {
			if (m.minimapWaypoints.get() || m.minimapDeath.get()) drawWaypoints(c, e, cx, cy, radius, round, theta, px, pz, scale);
			drawEntities(c, e, cx, cy, radius, round, theta, px, pz, scale);
		}
		if (m.minimapCompass.get()) drawCompass(c, e, cx, cy, radius, round, theta, scale, theme);
		// Eigene Position: Pfeil in Blickrichtung (bei gedrehter Karte immer nach oben).
		float arrowRot = theta + yawRad + (float) Math.PI;
		if (!e.sprites().draw(c, MapSprites.ARROW, cx, cy, 10f, arrowRot, 0xFF000000 | (theme.dustOn & 0xFFFFFF), scale)) {
			c.fill((int) cx - 2, (int) cy - 2, (int) cx + 2, (int) cy + 2, theme.dustOn);
		}
		drawInfo(c, e, pad * 2 + size, pad * 2 + size + 1, preview, live);
		e.recordDraw(System.nanoTime() - t0);
	}

	private void drawMap(Canvas c, MapEngine e, MapLayer layer, float cx, float cy, float radius, boolean round,
			float theta, double px, double pz, int opacity, long now, Clip clip) {
		int n;
		boolean clipped = false;
		if (round) {
			// Karte bis knapp unter den Stein; die Staublinie verdeckt die Streifenkanten.
			float mapR = radius - 0.5f;
			n = MapShapes.circle(mapR, MapSprites.ringIn(radius) - 0.5f, bands);
		} else if (Math.abs(theta) < 1e-4f) {
			bands[0] = -radius;
			bands[1] = radius;
			bands[2] = -radius;
			bands[3] = radius;
			n = 1;
		} else if (clip != null && clip.begin(Math.round(cx - radius), Math.round(cy - radius), Math.round(cx + radius),
				Math.round(cy + radius))) {
			clipped = true;
			float half = radius * 1.4143f + 1f;
			bands[0] = -half;
			bands[1] = half;
			bands[2] = -half;
			bands[3] = half;
			n = 1;
		} else {
			n = MapShapes.rotatedSquare(radius, -theta, 2f, bands);
		}
		if (!c.images()) {
			if (clipped) clip.end();
			return;
		}
		e.textures().beginFrame(3);
		int tint = (opacity << 24) | 0xFFFFFF;
		// Innenansicht: darunter die Oberfläche – noch nicht abgetastete Stellen bleiben so nicht leer
		// (nur deckend, sonst schimmerte das ausgeblendete Dach durch).
		MapLayer under = layer.roof() && opacity >= 255 ? e.surfaceLayer() : null;
		float inv = 1f / zoom;
		c.push();
		c.translate(cx, cy);
		if (theta != 0f) c.rotate(theta);
		c.scale(zoom, zoom);
		for (int b = 0; b < n; b++) {
			// Streifen in Weltblöcken.
			double wx0 = px + bands[b * 4 + 2] * inv, wx1 = px + bands[b * 4 + 3] * inv;
			double wz0 = pz + bands[b * 4] * inv, wz1 = pz + bands[b * 4 + 1] * inv;
			if (under != null) drawPieces(c, e, under, wx0, wz0, wx1, wz1, px, pz, tint, now);
			drawPieces(c, e, layer, wx0, wz0, wx1, wz1, px, pz, tint, now);
		}
		c.pop();
		if (clipped) clip.end();
	}

	/** Zeichnet das Welt-Rechteck [wx0,wx1]×[wz0,wz1], aufgeteilt auf die Bereichs-Texturen. */
	static void drawPieces(Canvas c, MapEngine e, MapLayer layer, double wx0, double wz0, double wx1, double wz1,
			double originX, double originZ, int tint, long now) {
		// Kanten auf 1/16 Block runden: benachbarte Stücke (Streifen, Bereiche) teilen dann exakt dieselbe Kante –
		// keine Haarrisse beim Drehen, keine doppelt gezeichneten Linien.
		wx0 = Math.round(wx0 * SUB) / (double) SUB;
		wx1 = Math.round(wx1 * SUB) / (double) SUB;
		wz0 = Math.round(wz0 * SUB) / (double) SUB;
		wz1 = Math.round(wz1 * SUB) / (double) SUB;
		if (wx1 <= wx0 || wz1 <= wz0) return;
		int rx0 = (int) Math.floor(wx0) >> MapRegion.SHIFT, rx1 = (int) Math.floor(wx1 - 1e-6) >> MapRegion.SHIFT;
		int rz0 = (int) Math.floor(wz0) >> MapRegion.SHIFT, rz1 = (int) Math.floor(wz1 - 1e-6) >> MapRegion.SHIFT;
		for (int rz = rz0; rz <= rz1; rz++) {
			for (int rx = rx0; rx <= rx1; rx++) {
				MapRegion region = layer.get(rx, rz, now);
				if (region == null) continue;
				TextureRef tex = e.textures().region(layer, region, now, 250);
				if (tex == null) continue;
				double bx = (double) rx * MapRegion.SIZE, bz = (double) rz * MapRegion.SIZE;
				double x0 = Math.max(wx0, bx), x1 = Math.min(wx1, bx + MapRegion.SIZE);
				double z0 = Math.max(wz0, bz), z1 = Math.min(wz1, bz + MapRegion.SIZE);
				if (x1 <= x0 || z1 <= z0) continue;
				int w = (int) Math.round((x1 - x0) * SUB), h = (int) Math.round((z1 - z0) * SUB);
				if (w <= 0 || h <= 0) continue;
				TextureRef sub = new TextureRef(tex.id, tex.width * SUB, tex.height * SUB);
				c.push();
				c.translate((float) (x0 - originX), (float) (z0 - originZ));
				c.scale(1f / SUB, 1f / SUB);
				c.image(sub, (float) ((x0 - bx) * SUB), (float) ((z0 - bz) * SUB), w, h, tint);
				c.pop();
			}
		}
	}

	/** Welt → Bildschirm (relativ zur Kartenmitte, gedreht). */
	private void toScreen(double wx, double wz, double px, double pz, float theta) {
		double dx = (wx - px) * zoom, dz = (wz - pz) * zoom;
		double cos = Math.cos(theta), sin = Math.sin(theta);
		point[0] = dx * cos - dz * sin;
		point[1] = dx * sin + dz * cos;
	}

	/** Hält einen Punkt innerhalb der Karte (Rand-Markierung); true = lag außerhalb. */
	private boolean clampToEdge(float radius, boolean round, float margin) {
		double lim = radius - margin;
		if (round) {
			double d = Math.sqrt(point[0] * point[0] + point[1] * point[1]);
			if (d <= lim) return false;
			point[0] *= lim / d;
			point[1] *= lim / d;
			return true;
		}
		boolean out = Math.abs(point[0]) > lim || Math.abs(point[1]) > lim;
		point[0] = Math.max(-lim, Math.min(lim, point[0]));
		point[1] = Math.max(-lim, Math.min(lim, point[1]));
		return out;
	}

	private boolean inside(float radius, boolean round, float margin) {
		double lim = radius - margin;
		if (round) return point[0] * point[0] + point[1] * point[1] <= lim * lim;
		return Math.abs(point[0]) <= lim && Math.abs(point[1]) <= lim;
	}

	private void drawWaypoints(Canvas c, MapEngine e, float cx, float cy, float radius, boolean round, float theta,
			double px, double pz, double scale) {
		TrsModules m = e.modules();
		List<Waypoint> list = e.waypoints();
		for (int i = 0, n = list.size(); i < n; i++) {
			Waypoint w = list.get(i);
			if (w.death ? !m.minimapDeath.get() : (!m.minimapWaypoints.get() || !w.visible)) continue;
			toScreen(w.x + 0.5, w.z + 0.5, px, pz, theta);
			boolean edge = clampToEdge(radius, round, 5f);
			float x = cx + (float) point[0], y = cy + (float) point[1];
			if (w.death) {
				e.sprites().draw(c, MapSprites.GRAVE, x, y, 9f, 0f, edge ? 0xC0FFFFFF : 0xFFFFFFFF, scale);
			} else {
				e.sprites().draw(c, MapSprites.DIAMOND, x, y, edge ? 6f : 8f, 0f, 0xFF000000 | w.color, scale);
			}
		}
	}

	private void drawEntities(Canvas c, MapEngine e, float cx, float cy, float radius, boolean round, float theta,
			double px, double pz, double scale) {
		TrsModules m = e.modules();
		float alpha = e.alpha();
		int count = e.entityCount();
		// Erst Kreaturen, dann Spieler (liegen oben).
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < count; i++) {
				MapEntity en = e.entity(i);
				boolean player = en.type == MapEntity.PLAYER;
				if ((pass == 1) != player) continue;
				if (player ? !m.minimapPlayers.get()
						: (en.type == MapEntity.HOSTILE ? !m.minimapHostile.get() : !m.minimapPassive.get())) continue;
				toScreen(en.lerpX(alpha), en.lerpZ(alpha), px, pz, theta);
				if (!inside(radius, round, 3f)) continue;
				float x = cx + (float) point[0], y = cy + (float) point[1];
				if (!player) {
					e.sprites().draw(c, MapSprites.DOT, x, y, en.type == MapEntity.HOSTILE ? 5f : 4.5f, 0f,
							en.type == MapEntity.HOSTILE ? HOSTILE : PASSIVE, scale);
					continue;
				}
				if (en.friend && m.minimapFriends.get()) {
					e.sprites().draw(c, MapSprites.HALO, x, y, 13f, 0f, FRIEND, scale);
				}
				face(c, e, en, x, y, 7f, scale);
			}
		}
	}

	/** Gesicht eines Spielers (Skin-Textur, mit Hut-Ebene) oder ein Punkt. */
	static void face(Canvas c, MapEngine e, MapEntity en, float x, float y, float size, double scale) {
		if (en.skin == null || !c.images()) {
			e.sprites().draw(c, MapSprites.DOT, x, y, size - 1f, 0f, 0xFFFFFFFF, scale);
			return;
		}
		float half = size / 2f;
		c.fill(Math.round(x - half - 1), Math.round(y - half - 1), Math.round(x + half + 1), Math.round(y + half + 1), 0xE0101014);
		c.push();
		c.translate(x - half, y - half);
		c.scale(size / 8f, size / 8f);
		c.image(en.skin, 8, 8, 8, 8, 0xFFFFFFFF);
		c.image(en.skin, 40, 8, 8, 8, 0xFFFFFFFF);
		c.pop();
	}

	private void drawCompass(Canvas c, MapEngine e, float cx, float cy, float radius, boolean round, float theta,
			double scale, Theme theme) {
		String[] letters = {I18n.tr("map.north"), I18n.tr("map.east"), I18n.tr("map.south"), I18n.tr("map.west")};
		for (int i = 0; i < 4; i++) {
			// Norden = −z; im Uhrzeigersinn O, S, W.
			double a = theta + i * Math.PI / 2;
			double dx = Math.sin(a), dy = -Math.cos(a);
			float r = round ? radius - 1f : radius;
			double x, y;
			if (round) {
				x = dx * r;
				y = dy * r;
			} else {
				double k = r / Math.max(Math.abs(dx), Math.abs(dy));
				x = dx * k;
				y = dy * k;
			}
			float lx = cx + (float) x, ly = cy + (float) y;
			e.sprites().draw(c, MapSprites.BADGE, lx, ly, 9f, 0f, 0xF0141217, scale);
			String s = letters[i];
			int color = i == 0 ? (0xFF000000 | (theme.dustOn & 0xFFFFFF)) : 0xFFE6E2DA;
			c.push();
			c.translate(lx, ly);
			c.scale(0.75f);
			c.text(s, -c.textWidth(s) / 2 + 1, -4, color, false);
			c.pop();
		}
	}

	private void squareFrame(Canvas c, int pad, int size, Theme t) {
		int x = 0, y = 0, w = size + 2 * pad, h = size + 2 * pad;
		int stone = ColorMath.lerp(0xFF3A373D, t.bevelLight, 0.2f);
		// Steinrand (oben/links hell, unten/rechts dunkel), dazwischen die Staublinie.
		c.fill(x, y, x + w, y + pad, stone);
		c.fill(x, y + h - pad, x + w, y + h, stone);
		c.fill(x, y + pad, x + pad, y + h - pad, stone);
		c.fill(x + w - pad, y + pad, x + w, y + h - pad, stone);
		c.fill(x, y, x + w, y + 1, ColorMath.lerp(stone, 0xFFFFFFFF, 0.18f));
		c.fill(x, y + h - 1, x + w, y + h, 0xFF0B0A0D);
		c.fill(x, y, x + 1, y + h, 0xFF0B0A0D);
		c.fill(x + w - 1, y, x + w, y + h, 0xFF0B0A0D);
		int dust = 0xFF000000 | (t.dustOn & 0xFFFFFF);
		int in = pad - 1;
		c.fill(in, in, w - in, in + 1, dust);
		c.fill(in, h - in - 1, w - in, h - in, dust);
		c.fill(in, in, in + 1, h - in, dust);
		c.fill(w - in - 1, in, w - in, h - in, dust);
	}

	private void drawInfo(Canvas c, MapEngine e, int width, int y, boolean preview, boolean live) {
		TrsModules m = e.modules();
		int color = m.minimap.textColor.argb();
		boolean shadow = m.minimap.shadow();
		if (m.minimapCoords.get()) {
			String s = coordsText;
			if (live) {
				long bx = (long) Math.floor(e.playerX()), by = (long) Math.floor(e.playerY()), bz = (long) Math.floor(e.playerZ());
				long key = (bx * 31 + by) * 31 + bz;
				if (key != coordsKey || coordsText.isEmpty()) {
					coordsKey = key;
					coordsText = HudFormat.coords(bx, by, bz);
				}
				s = coordsText;
			} else if (preview) {
				s = HudFormat.coords(128, 64, -256);
			}
			centered(c, s, width, y, color, shadow);
			y += LINE_H;
		}
		if (m.minimapBiome.get()) {
			String s = live ? e.biome() : (preview ? "Plains" : "");
			String level = e.caveActive() ? I18n.tr("map.cave") : (e.roofActive() ? I18n.tr("map.roof") : null);
			if (level != null) s = s.isEmpty() ? level : s + " · " + level;
			centered(c, s, width, y, color, shadow);
			y += LINE_H;
		}
		if (m.minimapTime.get()) {
			long t = live ? e.dayTime() : 6000;
			if (t != timeKey) {
				timeKey = t;
				timeText = t < 0 ? "--:--" : clock(t);
			}
			centered(c, timeText, width, y, color, shadow);
		}
	}

	private static void centered(Canvas c, String s, int width, int y, int color, boolean shadow) {
		if (s == null || s.isEmpty()) return;
		String clipped = c.clip(s, width);
		c.text(clipped, (width - c.textWidth(clipped)) / 2, y, color, shadow);
	}

	/** Tageszeit (Ticks, 0 = 6:00) als HH:MM. */
	public static String clock(long dayTime) {
		long t = ((dayTime % 24000) + 24000 + 6000) % 24000;
		int hours = (int) (t / 1000);
		int minutes = (int) ((t % 1000) * 60 / 1000);
		return (hours < 10 ? "0" : "") + hours + ":" + (minutes < 10 ? "0" : "") + minutes;
	}
}
