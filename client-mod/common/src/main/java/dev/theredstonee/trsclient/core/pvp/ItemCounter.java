package dev.theredstonee.trsclient.core.pvp;

import dev.theredstonee.trsclient.core.module.ChoiceSetting;

/**
 * Zähler-HUD: Pfeile, Totems, Heiltränke, Wurftränke, Goldäpfel, Enderperlen und Blöcke im Inventar. Der Loader meldet
 * je Stapel die Gegenstands-ID (ohne "minecraft:") und ein paar Merkmale; hier wird nur einsortiert und gezählt.
 */
public final class ItemCounter {
	public enum Kind {
		ARROWS("arrow"),
		TOTEMS("totem_of_undying"),
		HEALING("potion"),
		SPLASH("splash_potion"),
		GAPPLES("golden_apple"),
		PEARLS("ender_pearl"),
		BLOCKS("cobblestone");

		/** Gegenstand für das Symbol, wenn gerade keiner im Inventar ist (Editor-Vorschau). */
		public final String iconItem;

		Kind(String iconItem) {
			this.iconItem = iconItem;
		}
	}

	/** Anordnung im HUD. */
	public enum Layout implements ChoiceSetting.Option {
		VERTICAL("Vertical"),
		HORIZONTAL("Horizontal");

		private final String label;

		Layout(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	private final int[] counts = new int[Kind.values().length];
	/** Erster gefundener Stapel je Art (für das Symbol, z. B. der richtige Trank), vom Loader gesetzt. */
	private final Object[] icons = new Object[Kind.values().length];

	public void clear() {
		for (int i = 0; i < counts.length; i++) {
			counts[i] = 0;
			icons[i] = null;
		}
	}

	/**
	 * Arten eines Stapels als Bitmaske ({@code 1 << kind.ordinal()}).
	 *
	 * @param id       Gegenstands-ID ohne Namensraum ("arrow", "splash_potion", "potion" …; 1.8.9: "potion" mit
	 *                 {@code splash}-Merkmal)
	 * @param isBlock  der Gegenstand ist ein Block (platzierbar)
	 * @param healing  Trank mit Sofortheilung
	 * @param splash   Wurftrank (ab 1.9 eigener Gegenstand, 1.8.9 über den Schadenswert)
	 */
	public static int classify(String id, boolean isBlock, boolean healing, boolean splash) {
		if (id == null) return 0;
		String s = id.startsWith("minecraft:") ? id.substring(10) : id;
		if (s.equals("arrow") || s.equals("spectral_arrow") || s.equals("tipped_arrow")) return bit(Kind.ARROWS);
		if (s.equals("totem_of_undying") || s.equals("totem")) return bit(Kind.TOTEMS);
		if (s.equals("golden_apple") || s.equals("enchanted_golden_apple")) return bit(Kind.GAPPLES);
		if (s.equals("ender_pearl")) return bit(Kind.PEARLS);
		boolean potion = s.equals("potion") || s.equals("splash_potion") || s.equals("lingering_potion");
		if (potion) {
			boolean isSplash = splash || s.equals("splash_potion");
			int mask = 0;
			if (healing) mask |= bit(Kind.HEALING);
			if (isSplash) mask |= bit(Kind.SPLASH);
			return mask;
		}
		if (isBlock) return bit(Kind.BLOCKS);
		return 0;
	}

	public static int bit(Kind kind) {
		return 1 << kind.ordinal();
	}

	/** Stapel dazuzählen. {@code stack} = Gegenstand fürs Symbol (erster seiner Art gewinnt). */
	public void add(int mask, int count, Object stack) {
		if (mask == 0 || count <= 0) return;
		Kind[] kinds = Kind.values();
		for (int i = 0; i < kinds.length; i++) {
			if ((mask & (1 << i)) == 0) continue;
			counts[i] += count;
			if (icons[i] == null) icons[i] = stack;
		}
	}

	/** Fehlt für eine der Arten noch ein Symbol? (Dann lohnt sich eine Kopie des Stapels mit Anzahl 1.) */
	public boolean needsIcon(int mask) {
		Kind[] kinds = Kind.values();
		for (int i = 0; i < kinds.length; i++) {
			if ((mask & (1 << i)) != 0 && icons[i] == null) return true;
		}
		return false;
	}

	public int count(Kind kind) {
		return counts[kind.ordinal()];
	}

	/** Gefundener Stapel fürs Symbol oder null. */
	public Object icon(Kind kind) {
		return icons[kind.ordinal()];
	}
}
