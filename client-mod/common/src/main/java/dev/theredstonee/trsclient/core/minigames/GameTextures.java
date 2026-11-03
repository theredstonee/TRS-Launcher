package dev.theredstonee.trsclient.core.minigames;

import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.ui.Textures;

import java.util.HashMap;
import java.util.Map;

/**
 * Texturen der Minispiele. Sie kommen zur Laufzeit aus dem laufenden Spiel, Ressourcenpakete gewinnen also über Vanilla;
 * im Repo und in den Jars liegen keine Mojang-Grafiken. Fehlt eine Datei in dieser Minecraft-Version (Tiefenschiefer und
 * Kupfer vor 1.17, andere Pfade vor 1.13), gilt die nächste Zeile der Liste und zuletzt eine schlichte Ersatzfläche.
 * Nur im Render-Thread benutzen.
 */
public final class GameTextures {
	/** Bild in ARGB. */
	public static final class Tex {
		public final int w;
		public final int h;
		public final int[] d;

		Tex(int w, int h, int[] d) {
			this.w = w;
			this.h = h;
			this.d = d;
		}
	}

	/** Liest Rohbytes einer Spiel-Ressource; in Tests ersetzbar. */
	public interface Source {
		byte[] read(String location);
	}

	private static final Map<String, String[]> PATHS = new HashMap<String, String[]>();
	private static final Map<String, Integer> COLORS = new HashMap<String, Integer>();

	private static void add(String name, int color, String... paths) {
		PATHS.put(name, paths);
		COLORS.put(name, color);
	}

	static {
		add("stone", 0xFF8D8D8F, "block/stone", "blocks/stone");
		add("cobblestone", 0xFF6F6F70, "block/cobblestone", "blocks/cobblestone");
		add("bricks", 0xFFA5533F, "block/bricks", "blocks/brick");
		add("copper_block", 0xFFC9794A, "block/copper_block", "block/orange_terracotta", "blocks/hardened_clay_stained_orange",
				"block/bricks", "blocks/brick");
		add("oak_planks", 0xFFA57D48, "block/oak_planks", "blocks/planks_oak");
		add("obsidian", 0xFF3A2A55, "block/obsidian", "blocks/obsidian");
		add("quartz_block_side", 0xFFD9D3C8, "block/quartz_block_side", "blocks/quartz_block_side");
		add("redstone_block", 0xFFE0261C, "block/redstone_block", "blocks/redstone_block");
		add("tnt_side", 0xFFD8352A, "block/tnt_side", "blocks/tnt_side");
		add("tnt_top", 0xFFC0392B, "block/tnt_top", "blocks/tnt_top");
		add("redstone_lamp", 0xFF6B4A2B, "block/redstone_lamp", "blocks/redstone_lamp_off");
		add("redstone_lamp_on", 0xFFD9A43F, "block/redstone_lamp_on", "blocks/redstone_lamp_on");
		add("piston_top", 0xFFA58A58, "block/piston_top", "blocks/piston_top_normal");
		add("iron_block", 0xFFB5B5B8, "block/iron_block", "blocks/iron_block");
		add("dropper_front", 0xFF6F6F70, "block/dropper_front", "blocks/dropper_front_horizontal", "block/furnace_front",
				"blocks/furnace_front_off", "block/cobblestone", "blocks/cobblestone");
		add("deepslate_bricks", 0xFF3A3A3E, "block/deepslate_bricks", "block/polished_blackstone_bricks", "block/stone_bricks",
				"blocks/stonebrick");
		add("polished_blackstone_bricks", 0xFF2F2B33, "block/polished_blackstone_bricks", "block/deepslate_bricks",
				"block/stone_bricks", "blocks/stonebrick");
		add("deepslate_tiles", 0xFF2A2A2E, "block/deepslate_tiles", "block/polished_blackstone", "block/stone_bricks",
				"blocks/stonebrick");
		add("redstone_dust_line0", 0, "block/redstone_dust_line0", "blocks/redstone_dust_line0", "blocks/redstone_dust_line");
		add("lever", 0, "block/lever", "blocks/lever");
		add("deepslate", 0xFF4A4A4E, "block/deepslate", "block/andesite", "blocks/stone_andesite", "block/stone", "blocks/stone");
		add("netherrack", 0xFF6B2B2B, "block/netherrack", "blocks/netherrack");
		add("nether_bricks", 0xFF2C1518, "block/nether_bricks", "blocks/nether_brick");
		add("magma", 0xFFB5461B, "block/magma", "blocks/magma", "block/glowstone", "blocks/glowstone");
		add("lava_still", 0xFFE0701C, "block/lava_still", "blocks/lava_still");
		add("coal_ore", 0xFF5A5A5C, "block/coal_ore", "blocks/coal_ore");
		add("iron_ore", 0xFF9A8C7E, "block/iron_ore", "blocks/iron_ore");
		add("redstone_ore", 0xFF8A5656, "block/redstone_ore", "blocks/redstone_ore");
		add("deepslate_redstone_ore", 0xFF6A4044, "block/deepslate_redstone_ore", "block/redstone_ore", "blocks/redstone_ore");
		add("diamond_ore", 0xFF6FA8A8, "block/diamond_ore", "blocks/diamond_ore");
		add("oak_log", 0xFF6B5433, "block/oak_log", "blocks/log_oak");
		add("rail", 0, "block/rail", "blocks/rail_normal");
		add("powered_rail", 0, "block/powered_rail", "blocks/rail_golden");
		add("powered_rail_on", 0, "block/powered_rail_on", "blocks/rail_golden_powered");
		add("torch", 0, "block/torch", "blocks/torch_on");
		add("redstone", 0, "item/redstone", "items/redstone_dust");
		add("packed_ice", 0xFF8DB4E0, "block/packed_ice", "blocks/ice_packed", "block/ice", "blocks/ice");
		add("blue_ice", 0xFF74A8E8, "block/blue_ice", "block/packed_ice", "blocks/ice_packed", "blocks/ice");
		add("snow", 0xFFF0F6F6, "block/snow", "blocks/snow");
		add("spruce_planks", 0xFF6B4F2D, "block/spruce_planks", "blocks/planks_spruce");
		add("spruce_log", 0xFF3F2C18, "block/spruce_log", "blocks/log_spruce");
		add("stripped_spruce_log", 0xFF6F5230, "block/stripped_spruce_log", "block/spruce_log", "blocks/log_spruce");
		add("podzol_top", 0xFF5B3F1F, "block/podzol_top", "blocks/dirt_podzol_top");
	}

	private final Source source;
	private final Map<String, Tex> blocks = new HashMap<String, Tex>();
	private final Map<String, PngDecoder.Image> sheets = new HashMap<String, PngDecoder.Image>();

	public GameTextures(Source source) {
		this.source = source;
	}

	/** Liest über den Texturspeicher der Version. */
	public static GameTextures fromGame() {
		final Textures.Store store = Textures.store();
		return new GameTextures(new Source() {
			@Override
			public byte[] read(String location) {
				return store == null ? null : store.gameBytes(location);
			}
		});
	}

	private PngDecoder.Image png(String path) {
		byte[] bytes;
		try {
			bytes = source.read("minecraft:textures/" + path + ".png");
		} catch (RuntimeException e) {
			return null;
		}
		if (bytes == null) return null;
		try {
			return PngDecoder.decode(bytes);
		} catch (java.io.IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Block- oder Gegenstandstextur, immer 16×16. Bei einer Animation gilt das erste Bild, hochauflösende Pakete werden
	 * verkleinert. Nie null.
	 */
	public Tex block(String name) {
		Tex known = blocks.get(name);
		if (known != null) return known;
		Tex out = null;
		String[] paths = PATHS.get(name);
		if (paths != null) {
			for (String path : paths) {
				PngDecoder.Image image = png(path);
				if (image != null && image.width > 0 && image.height >= image.width) {
					out = square(image);
					break;
				}
			}
		}
		if (out == null) out = replacement(name);
		blocks.put(name, out);
		return out;
	}

	private static Tex square(PngDecoder.Image image) {
		int size = image.width;
		int[] d = new int[256];
		for (int y = 0; y < 16; y++) {
			int sy = y * size / 16;
			for (int x = 0; x < 16; x++) d[y * 16 + x] = image.argb[sy * size + x * size / 16];
		}
		return new Tex(16, 16, d);
	}

	/** Ersatz, wenn keine Datei da ist: durchsichtige Formen zeichnen die Grundform, alles andere ist eine leicht gemusterte Fläche. */
	private static Tex replacement(String name) {
		int[] d = new int[256];
		if ("redstone_dust_line0".equals(name)) {
			for (int y = 6; y < 10; y++) {
				for (int x = 0; x < 16; x++) d[y * 16 + x] = 0xFFC8C8C8;
			}
		} else if ("lever".equals(name)) {
			for (int y = 2; y < 12; y++) {
				d[y * 16 + 7] = 0xFF8A6A3F;
				d[y * 16 + 8] = 0xFF6B5433;
			}
		} else if ("torch".equals(name)) {
			for (int y = 7; y < 16; y++) {
				d[y * 16 + 7] = 0xFF6B5433;
				d[y * 16 + 8] = 0xFF6B5433;
			}
			for (int y = 4; y < 7; y++) {
				d[y * 16 + 7] = 0xFFFFD27A;
				d[y * 16 + 8] = 0xFFFFD27A;
			}
		} else if ("redstone".equals(name)) {
			for (int y = 5; y < 11; y++) {
				for (int x = 5; x < 11; x++) d[y * 16 + x] = 0xFFE0261C;
			}
		} else if (name.endsWith("rail") || name.endsWith("rail_on")) {
			int rail = "powered_rail".equals(name) ? 0xFFC9A23F : "powered_rail_on".equals(name) ? 0xFFFF4A30 : 0xFF9A9A9E;
			for (int y = 0; y < 16; y++) {
				d[y * 16 + 2] = rail;
				d[y * 16 + 13] = rail;
				if (y % 4 == 1) {
					for (int x = 1; x < 15; x++) d[y * 16 + x] = 0xFF6B5433;
				}
			}
		} else {
			Integer color = COLORS.get(name);
			int base = color == null || color == 0 ? 0xFF808080 : color;
			for (int y = 0; y < 16; y++) {
				for (int x = 0; x < 16; x++) d[y * 16 + x] = ((x ^ y) & 3) == 0 ? scale(base, 0.86f) : base;
			}
		}
		return new Tex(16, 16, d);
	}

	private static int scale(int argb, float k) {
		int r = (int) (((argb >> 16) & 255) * k);
		int g = (int) (((argb >> 8) & 255) * k);
		int b = (int) ((argb & 255) * k);
		return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
	}

	/**
	 * Ausschnitt einer Wesen-Textur ({@code creeper}, {@code steve}, {@code minecart}) in Vanilla-Maßen, auch aus
	 * hochauflösenden Paketen. Fehlt die Datei, ist es eine Fläche in {@code color}. Nie null.
	 */
	public Tex entity(String name, int sx, int sy, int w, int h, int color) {
		PngDecoder.Image image = sheet(name);
		int[] d = new int[w * h];
		if (image == null) {
			java.util.Arrays.fill(d, color);
			return new Tex(w, h, d);
		}
		int scale = Math.max(1, image.width / 64);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int px = (sx + x) * scale + scale / 2;
				int py = (sy + y) * scale + scale / 2;
				d[y * w + x] = px < image.width && py < image.height ? image.argb[py * image.width + px] : color;
			}
		}
		return new Tex(w, h, d);
	}

	private PngDecoder.Image sheet(String name) {
		if (sheets.containsKey(name)) return sheets.get(name);
		PngDecoder.Image image = null;
		for (String path : entityPaths(name)) {
			image = png(path);
			if (image != null) break;
		}
		sheets.put(name, image);
		return image;
	}

	private static String[] entityPaths(String name) {
		if ("creeper".equals(name)) return new String[] {"entity/creeper/creeper"};
		if ("steve".equals(name)) return new String[] {"entity/player/wide/steve", "entity/steve"};
		if ("minecart".equals(name)) return new String[] {"entity/minecart", "entity/minecart/minecart"};
		return new String[0];
	}
}
