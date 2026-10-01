package dev.theredstonee.trsclient.core.hunger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Liest die Erschöpfung aus {@code FoodData}/{@code FoodStats} – ohne Getter in allen Versionen und ohne Namen, die
 * je Mapping anders heißen: Die Klasse hat genau zwei float-Felder in fester Reihenfolge (Sättigung, Erschöpfung).
 * Nur für den Einzelspieler (Server-Spieler); im Mehrspieler kennt der Client die Erschöpfung nicht.
 */
public final class FoodReader {
	private static volatile Class<?> type;
	private static volatile Field exhaustion;
	private static volatile boolean broken;

	private FoodReader() {
	}

	/** Erschöpfung (0–4) oder {@link Float#NaN}, wenn sie sich nicht lesen lässt. */
	public static float exhaustion(Object foodData) {
		if (foodData == null || broken) return Float.NaN;
		try {
			Field f = field(foodData.getClass());
			if (f == null) return Float.NaN;
			float v = f.getFloat(foodData);
			return v >= 0f && v <= 40f ? v : Float.NaN;
		} catch (Throwable t) {
			broken = true;
			return Float.NaN;
		}
	}

	private static Field field(Class<?> c) {
		if (c == type) return exhaustion;
		List<Field> floats = new ArrayList<Field>();
		for (Field f : c.getDeclaredFields()) {
			if (!Modifier.isStatic(f.getModifiers()) && f.getType() == float.class) floats.add(f);
		}
		Field found = null;
		if (floats.size() == 2) {
			found = floats.get(1);
			found.setAccessible(true);
		} else {
			broken = true;
		}
		exhaustion = found;
		type = c;
		return found;
	}
}
