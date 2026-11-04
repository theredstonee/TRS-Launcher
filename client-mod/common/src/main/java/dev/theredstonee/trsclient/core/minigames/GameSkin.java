package dev.theredstonee.trsclient.core.minigames;

import dev.theredstonee.trsclient.core.ui.Theme;

/**
 * Unterschiede je Design, wie {@code GameSkin} in {@code app/utils/minigames/textures.ts}. Redstone ist der volle Auftritt,
 * Winternacht und Fuchsbau sind ein Anstrich derselben Maschine. Tönungen sind ARGB, die Alpha-Stufe ist die Deckkraft.
 */
public final class GameSkin {
	/** Erste Gegend im Loren-Rausch; die übrigen bleiben Tiefenschiefer und Nether. */
	public static final class ZoneDef {
		public final String name;
		public final String floor;
		public final String wall;
		public final String[] ores;
		public final String wood;
		public final String roof;
		public final int[] amb;
		public final int[] torch;

		ZoneDef(String name, String floor, String wall, String[] ores, String wood, String roof, int[] amb, int[] torch) {
			this.name = name;
			this.floor = floor;
			this.wall = wall;
			this.ores = ores;
			this.wood = wood;
			this.roof = roof;
			this.amb = amb;
			this.torch = torch;
		}
	}

	public final String id;
	public final String wall;
	public final int wallTint;
	public final String frame;
	public final String well;
	public final int wellTint;
	public final float[] wireOff;
	public final float[] wireOn;
	public final int ghostFill;
	public final int ghostStroke;
	/** Farbe des Aufblitzens (RGB). */
	public final int glow;
	/** Hintergrund des Bildschirms außerhalb des Spielfelds. */
	public final int backdrop;
	public final int backdropEdge;
	/** Null = Standard-Gegend „Mine“. */
	public final ZoneDef zone;

	private GameSkin(String id, String wall, int wallTint, String frame, String well, int wellTint, float[] wireOff, float[] wireOn,
			int ghostFill, int ghostStroke, int glow, int backdrop, int backdropEdge, ZoneDef zone) {
		this.id = id;
		this.wall = wall;
		this.wallTint = wallTint;
		this.frame = frame;
		this.well = well;
		this.wellTint = wellTint;
		this.wireOff = wireOff;
		this.wireOn = wireOn;
		this.ghostFill = ghostFill;
		this.ghostStroke = ghostStroke;
		this.glow = glow;
		this.backdrop = backdrop;
		this.backdropEdge = backdropEdge;
		this.zone = zone;
	}

	private static int rgba(int r, int g, int b, double alpha) {
		return ((int) Math.round(alpha * 255) << 24) | (r << 16) | (g << 8) | b;
	}

	public static final GameSkin REDSTONE = new GameSkin("redstone", "polished_blackstone_bricks", rgba(0, 0, 0, 0.46),
			"deepslate_bricks", "deepslate_tiles", rgba(4, 3, 3, 0.8),
			new float[] {0.42f, 0.06f, 0.05f}, new float[] {1.1f, 0.2f, 0.1f},
			rgba(255, 70, 50, 0.17), rgba(255, 90, 70, 0.72), 0xFFBE82, 0xFF2A1210, 0xFF0C0908, null);

	public static final GameSkin WINTER = new GameSkin("winter", "packed_ice", rgba(4, 16, 36, 0.62),
			"blue_ice", "deepslate_tiles", rgba(3, 8, 18, 0.82),
			new float[] {0.1f, 0.22f, 0.42f}, new float[] {0.45f, 0.85f, 1.15f},
			rgba(120, 200, 255, 0.17), rgba(150, 220, 255, 0.72), 0xAADCFF, 0xFF12233A, 0xFF070C14,
			new ZoneDef("frost", "snow", "packed_ice", new String[] {"blue_ice", "iron_ore", "diamond_ore", "blue_ice"},
					"spruce_log", "spruce_planks", new int[] {52, 62, 78}, new int[] {250, 225, 190}));

	public static final GameSkin FOX = new GameSkin("fox", "spruce_planks", rgba(16, 6, 4, 0.6),
			"stripped_spruce_log", "deepslate_tiles", rgba(10, 5, 6, 0.8),
			new float[] {0.45f, 0.2f, 0.05f}, new float[] {1.15f, 0.55f, 0.16f},
			rgba(255, 150, 70, 0.17), rgba(255, 175, 100, 0.72), 0xFFBE8C, 0xFF33190F, 0xFF0E0806,
			new ZoneDef("den", "podzol_top", "stone", new String[] {"coal_ore", "iron_ore", "coal_ore", "redstone_ore"},
					"spruce_log", "spruce_planks", new int[] {50, 42, 34}, new int[] {300, 190, 110}));

	/** Gegend für {@link LorenView}. */
	static ZoneDef zoneDef(String name, String floor, String wall, String[] ores, String wood, String roof, int[] amb, int[] torch) {
		return new ZoneDef(name, floor, wall, ores, wood, roof, amb, torch);
	}

	/** Anstrich zum Design des Clients. */
	public static GameSkin of(Theme theme) {
		if (theme.winter) return WINTER;
		if (theme.fox) return FOX;
		return REDSTONE;
	}
}
