package dev.theredstonee.trsclient.core.connect;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * Für Forge 1.7.10/1.13.2 (ohne Mixins, Release-Jars mit SRG-Namen): private Felder der Mehrspieler-Liste per Typ
 * finden – die ausgewählte Zeile (erstes {@code int}-Feld der Server-Liste) und die gespeicherte Serverliste.
 */
public final class LegacyLists {
	private static final Map<String, Field> FIELDS = new HashMap<String, Field>();

	private LegacyLists() {
	}

	/** Erstes nicht statisches Feld vom Typ {@code type} in {@code owner} (gemerkt) oder null. */
	public static synchronized Field field(Class<?> owner, Class<?> type) {
		String k = owner.getName() + "|" + type.getName();
		if (FIELDS.containsKey(k)) return FIELDS.get(k);
		Field found = null;
		for (Field f : owner.getDeclaredFields()) {
			if (!Modifier.isStatic(f.getModifiers()) && f.getType() == type) {
				try {
					f.setAccessible(true);
					found = f;
				} catch (RuntimeException ignored) {
					found = null;
				}
				break;
			}
		}
		FIELDS.put(k, found);
		return found;
	}

	/** Wert des ersten Felds vom Typ {@code type} oder null. */
	public static Object value(Object instance, Class<?> owner, Class<?> type) {
		if (instance == null) return null;
		Field f = field(owner, type);
		try {
			return f == null ? null : f.get(instance);
		} catch (IllegalAccessException | RuntimeException e) {
			return null;
		}
	}

	/** Ausgewählte Zeile der Server-Liste (Liste per Typ im Bildschirm, Index = erstes int-Feld der Liste) oder -1. */
	public static int selected(Object screen, Class<?> screenClass, Class<?> listClass) {
		Object list = value(screen, screenClass, listClass);
		if (list == null) return -1;
		Field f = field(listClass, int.class);
		try {
			return f == null ? -1 : f.getInt(list);
		} catch (IllegalAccessException | RuntimeException e) {
			return -1;
		}
	}

	/** Ausgewählten Server vorab auflösen und – Java 8 – Javas Adress-Speicher füllen. */
	public static void selectedServer(String address) {
		if (address == null) return;
		FastConnect.prefetch(address);
		FastConnect.primeJvm(address);
	}
}
