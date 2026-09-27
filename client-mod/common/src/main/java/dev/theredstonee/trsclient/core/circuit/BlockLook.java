package dev.theredstonee.trsclient.core.circuit;

import java.util.HashMap;
import java.util.Map;

/**
 * Wie ein Bibliotheks-Block in Vorschau und Geisterblock aussieht: Grundfarbe, ungefähre Form (Kasten in
 * Blockkoordinaten 0–1) und ob ein Richtungspfeil gezeichnet wird. Keine Texturen – in allen Versionen gleich.
 */
public final class BlockLook {
	private static final Map<String, Integer> COLORS = new HashMap<String, Integer>();

	static {
		COLORS.put("solid", 0xFF8C8C8C);
		COLORS.put("glass", 0x90C8E6EE);
		COLORS.put("sand", 0xFFDCD09E);
		COLORS.put("soul_sand", 0xFF5A4432);
		COLORS.put("sugar_cane", 0xFF8CC05A);
		COLORS.put("water", 0xB03F76E4);
		COLORS.put("redstone_wire", 0xFFC01212);
		COLORS.put("repeater", 0xFFB9B1A8);
		COLORS.put("comparator", 0xFFC7BFB6);
		COLORS.put("redstone_torch", 0xFFFF4A24);
		COLORS.put("redstone_wall_torch", 0xFFFF4A24);
		COLORS.put("lever", 0xFF8A6A44);
		COLORS.put("stone_button", 0xFFA2A2A2);
		COLORS.put("stone_pressure_plate", 0xFFA8A8A8);
		COLORS.put("redstone_lamp", 0xFFC2843E);
		COLORS.put("redstone_block", 0xFFC41A12);
		COLORS.put("piston", 0xFFB39462);
		COLORS.put("sticky_piston", 0xFF86B45E);
		COLORS.put("observer", 0xFF626262);
		COLORS.put("hopper", 0xFF4C4C50);
		COLORS.put("chest", 0xFFAA7A32);
		COLORS.put("furnace", 0xFF747474);
		COLORS.put("dropper", 0xFF7E7E7E);
		COLORS.put("copper_bulb", 0xFFC6724E);
		COLORS.put("daylight_detector", 0xFFD9C9A0);
		COLORS.put("oak_sign", 0xFFB88F5C);
		COLORS.put("glowstone", 0xFFEFD27E);
	}

	private BlockLook() {
	}

	public static int color(BlockDef def) {
		Integer c = COLORS.get(def.key);
		return c == null ? 0xFF9A9A9A : c;
	}

	/** Wird der Block als ganzer Würfel gezeichnet (verdeckt Nachbarflächen)? */
	public static boolean fullCube(BlockSpec spec) {
		float[] b = box(spec, null);
		return b[0] <= 0f && b[1] <= 0f && b[2] <= 0f && b[3] >= 1f && b[4] >= 1f && b[5] >= 1f;
	}

	/**
	 * Ungefähre Form {minX, minY, minZ, maxX, maxY, maxZ} (0–1).
	 *
	 * @param placement für Wandfackel/Hebel/Knopf an der Wand (Richtung nach dem Drehen), null = Schaltungs-Lage
	 */
	public static float[] box(BlockSpec spec, Placement placement) {
		String key = spec.def.key;
		if ("redstone_wire".equals(key)) return new float[] {0f, 0f, 0f, 1f, 0.0625f, 1f};
		if ("repeater".equals(key) || "comparator".equals(key)) return new float[] {0f, 0f, 0f, 1f, 0.125f, 1f};
		if ("stone_pressure_plate".equals(key)) return new float[] {0.0625f, 0f, 0.0625f, 0.9375f, 0.0625f, 0.9375f};
		if ("daylight_detector".equals(key)) return new float[] {0f, 0f, 0f, 1f, 0.375f, 1f};
		if ("redstone_torch".equals(key)) return new float[] {0.4375f, 0f, 0.4375f, 0.5625f, 0.625f, 0.5625f};
		if ("sugar_cane".equals(key)) return new float[] {0.125f, 0f, 0.125f, 0.875f, 1f, 0.875f};
		if ("oak_sign".equals(key)) return new float[] {0.25f, 0f, 0.4375f, 0.75f, 1f, 0.5625f};
		if ("redstone_wall_torch".equals(key) || "lever".equals(key) || "stone_button".equals(key)) {
			String face = spec.prop("face");
			String facing = spec.prop("facing");
			if (placement != null && facing != null) facing = placement.direction(facing);
			if ("redstone_wall_torch".equals(key)) face = "wall";
			float s = "stone_button".equals(key) ? 0.1875f : 0.25f;
			if (face == null || "floor".equals(face)) return new float[] {0.5f - s, 0f, 0.5f - s, 0.5f + s, 0.1875f, 0.5f + s};
			if ("ceiling".equals(face)) return new float[] {0.5f - s, 0.8125f, 0.5f - s, 0.5f + s, 1f, 0.5f + s};
			// an der Wand: am Block gegenüber von facing
			float lo = "redstone_wall_torch".equals(key) ? 0.2f : 0.3f;
			float hi = "redstone_wall_torch".equals(key) ? 0.85f : 0.7f;
			if ("north".equals(facing)) return new float[] {0.5f - s, lo, 0.8125f, 0.5f + s, hi, 1f};
			if ("south".equals(facing)) return new float[] {0.5f - s, lo, 0f, 0.5f + s, hi, 0.1875f};
			if ("west".equals(facing)) return new float[] {0.8125f, lo, 0.5f - s, 1f, hi, 0.5f + s};
			return new float[] {0f, lo, 0.5f - s, 0.1875f, hi, 0.5f + s};
		}
		return new float[] {0f, 0f, 0f, 1f, 1f, 1f};
	}

	/**
	 * Richtung des Pfeils auf der Oberseite ({@code north}/…/{@code up}/{@code down}) – Signal- bzw. Schieberichtung –
	 * oder null. Richtungen in Schaltungs-Lage.
	 */
	public static String arrow(BlockSpec spec) {
		String key = spec.def.key;
		String f = spec.prop("facing");
		if (f == null) return null;
		if ("repeater".equals(key) || "comparator".equals(key) || "observer".equals(key)) return opposite(f);
		if ("piston".equals(key) || "sticky_piston".equals(key) || "hopper".equals(key) || "dropper".equals(key)) return f;
		return null;
	}

	static String opposite(String d) {
		if ("north".equals(d)) return "south";
		if ("south".equals(d)) return "north";
		if ("east".equals(d)) return "west";
		if ("west".equals(d)) return "east";
		if ("up".equals(d)) return "down";
		if ("down".equals(d)) return "up";
		return d;
	}

	/** Richtung als Vektor {dx, dy, dz}. */
	static int[] vector(String d) {
		if ("north".equals(d)) return new int[] {0, 0, -1};
		if ("south".equals(d)) return new int[] {0, 0, 1};
		if ("east".equals(d)) return new int[] {1, 0, 0};
		if ("west".equals(d)) return new int[] {-1, 0, 0};
		if ("up".equals(d)) return new int[] {0, 1, 0};
		if ("down".equals(d)) return new int[] {0, -1, 0};
		return null;
	}

	/** Kurzer Zusatz zum Blocknamen: Verzögerung, Modus (für Beschriftungen). */
	public static String detail(BlockSpec spec, CircuitTexts texts) {
		String key = spec.def.key;
		if ("repeater".equals(key) && spec.prop("delay") != null) return texts.text("detail.delay").replace("{0}", spec.prop("delay"));
		if ("comparator".equals(key)) return texts.text("subtract".equals(spec.prop("mode")) ? "detail.subtract" : "detail.compare");
		if ("daylight_detector".equals(key) && "true".equals(spec.prop("inverted"))) return texts.text("detail.inverted");
		return null;
	}
}
