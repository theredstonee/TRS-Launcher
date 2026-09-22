package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.MapSampler;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.format.HudFormat;
import dev.theredstonee.trsclient.core.minimap.MinimapCache;
import dev.theredstonee.trsclient.core.minimap.MinimapGrid;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * Kleine Karte der Umgebung. Gezeichnet wird nur aus dem Chunk-Speicher ({@link MinimapCache}),
 * und zwar als waagerechte Farbstreifen – das ist in jeder Minecraft-Version gleich und günstig.
 * Fair Play: nur geladene Chunks, keine Höhlenansicht, Spielerpunkte standardmäßig aus.
 */
public final class MinimapHud extends HudElement {
	/** Chunks rund um den Spieler, die gelesen werden. */
	private static final int CHUNK_RADIUS = 6;
	/** Chunks je Tick (verteilt die Arbeit). */
	private static final int CHUNK_BUDGET = 3;
	/** Nach dieser Zeit wird ein Chunk erneut gelesen (Gelände ändert sich). */
	private static final long MAX_AGE_MS = 10_000;
	private static final int FOOTER_H = 10;

	private final TrsModules modules;
	private final MinimapCache cache = new MinimapCache();
	private final MapSampler sampler = new MapSampler();
	private final MinimapGrid grid = new MinimapGrid();
	private final double[] cell = new double[2];
	private String lastDimension = "";
	private long lastBuild;
	private double lastCenterX = Double.NaN;
	private double lastCenterZ = Double.NaN;
	private double lastRotation;

	public MinimapHud(HudModule module, TrsModules modules) {
		super(module);
		this.modules = modules;
	}

	/** Größe der Karte in (unskalierten) Pixeln. */
	private int mapSize() {
		return modules.minimapSize.getInt();
	}

	private int cellPixels() {
		return modules.minimapZoom.get().pixels();
	}

	private int blocksPerCell() {
		return modules.minimapZoom.get().blocks();
	}

	private int cells() {
		return Math.max(8, mapSize() / cellPixels());
	}

	@Override
	public boolean visible() {
		return mc.level != null && mc.player != null;
	}

	@Override
	public int width(Font font, boolean preview) {
		return cells() * cellPixels() + 2;
	}

	@Override
	public int height(Font font, boolean preview) {
		return cells() * cellPixels() + 2 + (modules.minimapCoords.get() ? FOOTER_H : 0);
	}

	/** Einmal je Client-Tick: fehlende Chunks nachladen (Budget). */
	public void tick() {
		if (!module.isEnabled() || mc.level == null || mc.player == null) return;
		String dimension = Mc.dimensionId();
		if (!dimension.equals(lastDimension)) {
			cache.clear();
			lastDimension = dimension;
		}
		int cx = (int) Math.floor(Mc.x(mc.player)) >> 4;
		int cz = (int) Math.floor(Mc.z(mc.player)) >> 4;
		cache.update(sampler, cx, cz, CHUNK_RADIUS, System.currentTimeMillis(), MAX_AGE_MS, CHUNK_BUDGET);
	}

	public void onWorldChange() {
		cache.clear();
		lastCenterX = Double.NaN;
	}

	@Override
	public void draw(Gfx g, Font font, boolean preview) {
		int cells = cells();
		int px = cellPixels();
		int size = cells * px;
		g.fill(0, 0, size + 2, size + 2, Brand.HUD_BG);
		Brand.outline(g, 0, 0, size + 2, size + 2, Brand.BORDER);

		Player player = mc.player;
		if (player == null) return;
		double centerX = Mc.x(player);
		double centerZ = Mc.z(player);
		double rotation = modules.minimapRotate.get() ? Mc.yRot(player) + 180.0 : 0.0;
		rebuildIfNeeded(cells, centerX, centerZ, rotation);

		// Karte als waagerechte Streifen gleicher Farbe (deutlich weniger Zeichenbefehle).
		for (int row = 0; row < cells; row++) {
			int runStart = 0;
			int runColor = grid.cell(0, row);
			for (int col = 1; col <= cells; col++) {
				int color = col < cells ? grid.cell(col, row) : ~runColor;
				if (color != runColor) {
					g.fill(1 + runStart * px, 1 + row * px, 1 + col * px, 1 + (row + 1) * px, 0xFF000000 | runColor);
					runStart = col;
					runColor = color;
				}
			}
		}

		if (modules.minimapPlayers.get()) drawPlayers(g, cells, px, centerX, centerZ, rotation);
		if (modules.minimapWaypoints.get()) drawWaypoints(g, cells, px, centerX, centerZ, rotation);
		drawSelf(g, cells, px, rotation, Mc.yRot(player));

		if (modules.minimapCoords.get()) {
			String text = HudFormat.coords(Mc.x(player), Mc.y(player), Mc.z(player));
			g.text(font, Gfx.clip(font, text, size), 1, size + 3, textColor(), false);
		}
	}

	/** Das Gitter nur neu aufbauen, wenn sich Position, Drehung oder Zeit wirklich geändert haben. */
	private void rebuildIfNeeded(int cells, double centerX, double centerZ, double rotation) {
		long now = System.currentTimeMillis();
		boolean moved = Math.abs(centerX - lastCenterX) >= blocksPerCell()
				|| Math.abs(centerZ - lastCenterZ) >= blocksPerCell()
				|| Math.abs(rotation - lastRotation) >= 2.0
				|| grid.cols() != cells;
		if (!moved && now - lastBuild < 500) return;
		grid.build(cache, centerX, centerZ, cells, cells, blocksPerCell(), rotation);
		lastCenterX = centerX;
		lastCenterZ = centerZ;
		lastRotation = rotation;
		lastBuild = now;
	}

	private void drawWaypoints(Gfx g, int cells, int px, double centerX, double centerZ, double rotation) {
		List<Waypoint> list = TrsClient.get().waypoints().visible();
		for (int i = 0, n = list.size(); i < n; i++) {
			Waypoint waypoint = list.get(i);
			MinimapGrid.toCell(centerX, centerZ, cells, cells, blocksPerCell(), rotation,
					waypoint.x + 0.5, waypoint.z + 0.5, cell);
			MinimapGrid.clamp(cell, cells, cells, 1);
			int x = 1 + (int) (cell[0] * px);
			int y = 1 + (int) (cell[1] * px);
			g.fill(x - 2, y - 2, x + 2, y + 2, 0xFF000000 | waypoint.color);
			g.outline(x - 3, y - 3, 6, 6, 0xC0000000);
		}
	}

	/**
	 * Andere Spieler: nur die, die das Spiel ohnehin kennt (normale Sichtweite) –
	 * kein Radar durch Wände. Standardmäßig ausgeschaltet.
	 */
	private void drawPlayers(Gfx g, int cells, int px, double centerX, double centerZ, double rotation) {
		if (mc.level == null) return;
		List<AbstractClientPlayer> players = mc.level.players();
		for (int i = 0, n = players.size(); i < n; i++) {
			Player other = players.get(i);
			if (other == mc.player) continue;
			MinimapGrid.toCell(centerX, centerZ, cells, cells, blocksPerCell(), rotation,
					Mc.x(other), Mc.z(other), cell);
			if (cell[0] < 0 || cell[0] >= cells || cell[1] < 0 || cell[1] >= cells) continue;
			int x = 1 + (int) (cell[0] * px);
			int y = 1 + (int) (cell[1] * px);
			g.fill(x - 1, y - 1, x + 2, y + 2, 0xFFFFFFFF);
			g.fill(x, y, x + 1, y + 1, 0xFF000000);
		}
	}

	/** Eigene Position: Pfeil in Blickrichtung (bei gedrehter Karte immer nach oben). */
	private void drawSelf(Gfx g, int cells, int px, double rotation, float yaw) {
		int cx = 1 + cells * px / 2;
		int cy = 1 + cells * px / 2;
		// Blickrichtung auf dem Bildschirm (bei gedrehter Karte immer nach oben).
		double angle = Math.toRadians(rotation - yaw);
		double dirX = Math.sin(angle);
		double dirY = Math.cos(angle);
		int tipX = (int) Math.round(cx + dirX * 4);
		int tipY = (int) Math.round(cy + dirY * 4);
		g.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xFFE0281E);
		g.fill(tipX - 1, tipY - 1, tipX + 1, tipY + 1, 0xFFFFFFFF);
	}
}
