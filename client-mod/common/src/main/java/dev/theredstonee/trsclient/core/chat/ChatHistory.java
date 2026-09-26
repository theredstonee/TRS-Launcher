package dev.theredstonee.trsclient.core.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * Längerer Chat-Verlauf. Vanilla behält 100 Zeilen. Wo es einen Mixin gibt, wird die Grenze direkt ersetzt
 * ({@link #limit}); ohne Mixins (Forge 1.8.9–1.12.2) hält {@link Keeper} eine Schattenliste und hängt die Zeilen,
 * die Vanilla hinten abgeschnitten hat, wieder an – Vanilla fügt neue Zeilen immer vorne ein und kürzt hinten.
 */
public final class ChatHistory {
	public static final int VANILLA = 100;
	public static final int MAX = 1000;

	private ChatHistory() {
	}

	/** Grenze aus der Einstellung (100–1000), Vanilla wenn das Chat-Modul aus ist. */
	public static int limit(boolean enabled, double setting) {
		if (!enabled) return VANILLA;
		int n = (int) Math.round(setting);
		return Math.max(VANILLA, Math.min(MAX, n));
	}

	/** Schattenliste für eine Vanilla-Liste (neueste Zeile vorne). Nur aus dem Spiel-Thread benutzen. */
	public static final class Keeper<T> {
		private final List<T> shadow = new ArrayList<T>();

		/**
		 * Gleicht einmal je Tick ab: neue Zeilen (vorne) übernehmen, Schatten auf {@code limit} kürzen und alles,
		 * was Vanilla hinten abgeschnitten hat, wieder anhängen. Mitten gelöschte Zeilen (z. B. „(x2)“) bleiben weg.
		 *
		 * @return Anzahl wieder angehängter Zeilen
		 */
		public int keep(List<T> live, int limit) {
			if (live == null) return 0;
			if (live.isEmpty() || limit <= VANILLA) {
				// Chat geleert (F3+D, Weltwechsel) oder Vanilla-Grenze: nichts zu merken.
				shadow.clear();
				return 0;
			}
			int known = shadow.isEmpty() ? -1 : indexOf(live, shadow.get(0));
			if (known < 0) {
				// Erster Abgleich oder alles neu (Chat neu aufgebaut): Schatten = aktueller Stand.
				shadow.clear();
				shadow.addAll(live);
				return 0;
			}
			// Neue Zeilen vorne übernehmen.
			if (known > 0) shadow.addAll(0, new ArrayList<T>(live.subList(0, known)));
			// Stimmt der Rest nicht mehr überein (mitten gelöschte Zeile), Schatten ab der letzten gemeinsamen Zeile
			// neu zusammensetzen: live + was im Schatten nach dessen letzter Zeile kommt.
			if (!prefix(live, shadow)) {
				T last = live.get(live.size() - 1);
				int at = indexOf(shadow, last);
				List<T> tail = at >= 0 ? new ArrayList<T>(shadow.subList(at + 1, shadow.size())) : new ArrayList<T>();
				shadow.clear();
				shadow.addAll(live);
				shadow.addAll(tail);
			}
			while (shadow.size() > limit) shadow.remove(shadow.size() - 1);
			int added = 0;
			if (live.size() < shadow.size()) {
				int from = live.size();
				live.addAll(new ArrayList<T>(shadow.subList(from, shadow.size())));
				added = shadow.size() - from;
			} else {
				while (live.size() > limit) live.remove(live.size() - 1);
			}
			return added;
		}

		public int size() {
			return shadow.size();
		}

		public void clear() {
			shadow.clear();
		}

		private static <T> boolean prefix(List<T> live, List<T> shadow) {
			if (live.size() > shadow.size()) return false;
			for (int i = 0; i < live.size(); i++) {
				if (live.get(i) != shadow.get(i)) return false;
			}
			return true;
		}

		private static <T> int indexOf(List<T> list, T item) {
			for (int i = 0; i < list.size(); i++) {
				if (list.get(i) == item) return i;
			}
			return -1;
		}
	}
}
