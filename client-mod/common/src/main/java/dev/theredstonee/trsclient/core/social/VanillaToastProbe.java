package dev.theredstonee.trsclient.core.social;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.BitSet;

/**
 * Liest aus der Toast-Komponente des Spiels, wie weit die Vanilla-Toasts oben rechts gerade nach unten reichen.
 * Die Felder werden einmal über ihren <em>Typ</em> gesucht (Namen sind je Loader/Version verschieden – Mojmap,
 * Intermediary, SRG, MCP):
 * <ul>
 * <li>ab 1.19.1 ({@code ToastComponent}/{@code ToastManager}): {@code BitSet occupiedSlots} – belegte Plätze bleiben
 * gesetzt, bis ein Toast fertig hinausgefahren ist;</li>
 * <li>1.12–1.19 ({@code GuiToast}/{@code ToastComponent}): Feld {@code ToastInstance[] visible} – Index = Platz.</li>
 * </ul>
 * Je Bild nur Feldzugriffe, keine Speicheranforderung. Unbekannter Aufbau → dauerhaft 0 (kein Ausweichen).
 */
public final class VanillaToastProbe {
	private static final int UNKNOWN = 0;
	private static final int BITSET = 1;
	private static final int ARRAY = 2;
	private static final int BROKEN = 3;

	private Class<?> owner;
	private Field field;
	private int mode = UNKNOWN;

	/** Unterkante (GUI-Pixel) der belegten Vanilla-Plätze oder 0. Nie Ausnahmen. */
	public int bottom(Object toasts) {
		if (toasts == null || mode == BROKEN) return 0;
		try {
			if (mode == UNKNOWN || owner != toasts.getClass()) {
				if (!locate(toasts.getClass())) {
					mode = BROKEN;
					return 0;
				}
			}
			Object v = field.get(toasts);
			if (v == null) return 0;
			if (mode == BITSET) return ToastAvoid.slotsBottom(((BitSet) v).length());
			Object[] slots = (Object[]) v;
			for (int i = slots.length - 1; i >= 0; i--) {
				if (slots[i] != null) return ToastAvoid.slotsBottom(i + 1);
			}
			return 0;
		} catch (RuntimeException | IllegalAccessException | LinkageError e) {
			mode = BROKEN;
			return 0;
		}
	}

	/** Aufbau erkannt? (Selbsttest) */
	public boolean working() {
		return mode == BITSET || mode == ARRAY;
	}

	private boolean locate(Class<?> type) {
		Field bits = null;
		Field array = null;
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers())) continue;
				Class<?> t = f.getType();
				if (t == BitSet.class) {
					if (bits != null) return false;
					bits = f;
				} else if (t.isArray() && !t.getComponentType().isPrimitive()) {
					if (array != null) return false;
					array = f;
				}
			}
			if (bits != null || array != null) break;
		}
		Field f = bits != null ? bits : array;
		if (f == null) return false;
		f.setAccessible(true);
		field = f;
		owner = type;
		mode = bits != null ? BITSET : ARRAY;
		return true;
	}

	/**
	 * Erfolgs-Fenster bis 1.11.2 ({@code GuiAchievement}): Anzeigezeit ({@code long notificationTime}, 0 = aus) und
	 * „dauerhaft“ ({@code boolean permanentNotification}) – je genau ein Feld dieses Typs.
	 */
	public static final class Achievement {
		private Class<?> owner;
		private Field time;
		private Field permanent;
		private boolean broken;

		/** Unterkante des Fensters oder 0; {@code nowMs} in Minecrafts Systemzeit ({@code Minecraft.getSystemTime()}). */
		public int bottom(Object gui, long nowMs) {
			if (gui == null || broken) return 0;
			try {
				if (time == null || owner != gui.getClass()) {
					if (!locate(gui.getClass())) {
						broken = true;
						return 0;
					}
				}
				long shown = time.getLong(gui);
				if (shown == 0) return 0;
				return ToastAvoid.achievementBottom(nowMs - shown, permanent.getBoolean(gui));
			} catch (RuntimeException | IllegalAccessException | LinkageError e) {
				broken = true;
				return 0;
			}
		}

		public boolean working() {
			return time != null && !broken;
		}

		private boolean locate(Class<?> type) {
			Field l = null;
			Field b = null;
			for (Field f : type.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers())) continue;
				if (f.getType() == long.class) {
					if (l != null) return false;
					l = f;
				} else if (f.getType() == boolean.class) {
					if (b != null) return false;
					b = f;
				}
			}
			if (l == null || b == null) return false;
			l.setAccessible(true);
			b.setAccessible(true);
			time = l;
			permanent = b;
			owner = type;
			return true;
		}
	}
}
