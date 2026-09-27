package dev.theredstonee.trsclient.core.tooltip;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.ComfortModules;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Text-Zusätze der Tooltips (versionsunabhängig): Haltbarkeit als Zahl/Prozent und kompakte Verzauberungen. Die Bäume
 * erkennen nur die Zeilen und bauen die Komponenten; hier stehen Regeln und Formate.
 */
public final class TooltipText {
	/** Breite, ab der eine Zeile kompakter Verzauberungen umbricht (GUI-Pixel). */
	public static final int ENCHANT_LINE_WIDTH = 190;
	/** Trenner zwischen Verzauberungen in einer Zeile. */
	public static final String ENCHANT_SEPARATOR = ", ";

	private TooltipText() {
	}

	/** Verbleibende Haltbarkeit in Prozent (gerundet, 0..100). */
	public static int remainingPercent(int max, int damage) {
		if (max <= 0) return 100;
		int left = Math.max(0, max - Math.max(0, damage));
		return Math.max(0, Math.min(100, (int) Math.round(left * 100.0 / max)));
	}

	/**
	 * Wert der Haltbarkeits-Zeile, z. B. „1234 / 1561 (79 %)“; null = nichts anzeigen (aus, oder Gegenstand ohne
	 * Haltbarkeit).
	 */
	public static String durabilityValue(ComfortModules.Durability mode, int max, int damage) {
		if (mode == null || mode == ComfortModules.Durability.OFF || max <= 0) return null;
		int left = Math.max(0, max - Math.max(0, damage));
		String number = left + " / " + max;
		String percent = I18n.tr("tooltips.percent", remainingPercent(max, damage));
		switch (mode) {
			case NUMBER:
				return number;
			case PERCENT:
				return percent;
			default:
				return number + " (" + percent + ")";
		}
	}

	/** Ganze Zeile „Haltbarkeit: …“ (übersetzt) oder null. */
	public static String durabilityLine(ComfortModules.Durability mode, int max, int damage) {
		String value = durabilityValue(mode, max, damage);
		return value == null ? null : I18n.tr("tooltips.durability", value);
	}

	/** Farbcode (Minecraft-Formatierung) je Rest: grün &gt; 50 %, gelb &gt; 25 %, gold &gt; 10 %, sonst rot. */
	public static char colorCode(int max, int damage) {
		int p = remainingPercent(max, damage);
		if (p > 50) return 'a';
		if (p > 25) return 'e';
		if (p > 10) return '6';
		return 'c';
	}

	/** Ist das ein Übersetzungsschlüssel eines Verzauberungsnamens ({@code enchantment.minecraft.sharpness})? */
	public static boolean isEnchantmentKey(String key) {
		return key != null && key.startsWith("enchantment.") && !key.startsWith("enchantment.level.")
				&& !key.equals("enchantment.unknown") && key.indexOf('.', "enchantment.".length()) > 0;
	}

	/**
	 * Verteilt Einträge auf Zeilen höchstens {@code maxWidth} breit (in Reihenfolge; ein zu breiter Eintrag bekommt eine
	 * eigene Zeile).
	 */
	public static <T> List<List<T>> pack(List<T> items, ToIntFunction<T> width, int separatorWidth, int maxWidth) {
		List<List<T>> lines = new ArrayList<List<T>>();
		List<T> line = null;
		int lineWidth = 0;
		for (T item : items) {
			int w = Math.max(0, width.applyAsInt(item));
			if (line != null && lineWidth + separatorWidth + w <= maxWidth) {
				line.add(item);
				lineWidth += separatorWidth + w;
			} else {
				line = new ArrayList<T>();
				line.add(item);
				lines.add(line);
				lineWidth = w;
			}
		}
		return lines;
	}

	/**
	 * Sollen die Verzauberungen dieses Tooltips zusammengefasst werden?
	 *
	 * @param count     Anzahl der Verzauberungs-Zeilen
	 * @param shiftDown Umschalt gedrückt
	 */
	public static boolean compact(boolean enabled, boolean shiftForDetails, int count, boolean shiftDown) {
		if (!enabled || count < 2) return false;
		return !(shiftForDetails && shiftDown);
	}

	/** Hinweis-Zeile „Umschalt: Details“ (nur wenn zusammengefasst wurde und Umschalt Details zeigt). */
	public static String shiftHint() {
		return I18n.tr("tooltips.shiftHint");
	}
}
