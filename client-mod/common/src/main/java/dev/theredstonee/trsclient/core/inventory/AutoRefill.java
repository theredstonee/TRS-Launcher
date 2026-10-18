package dev.theredstonee.trsclient.core.inventory;

/**
 * Automatisch nachlegen: merkt sich je Tick den Gegenstand im ausgewählten Hotbar-Slot. Wird dieser Slot
 * in diesem Tick leer (letzter Block gesetzt/gegessen/geworfen oder ein Werkzeug gerade zerbrochen), sucht
 * das Modul im übrigen Inventar (Hauptfeld 9–35, dann die anderen Hotbar-Slots) einen Stapel desselben
 * Gegenstands und legt ihn in den leeren Slot – serverseitig über den normalen Inventar-Klick, damit es auch
 * auf echten Servern funktioniert.
 *
 * <p>Die eigentliche Spiel-Anbindung ({@link Game}) kommt je Minecraft-Version aus dem Loader; die Logik
 * hier ist versionsunabhängig und testbar.
 */
public final class AutoRefill {
	/** Erster/letzter Index des Hauptinventars (9..35) und Anzahl der Hotbar-Slots. */
	public static final int MAIN_START = 9;
	public static final int MAIN_END = 36;
	public static final int HOTBAR = 9;
	/** Kleine Pause nach einem Tausch (Ticks), gegen Paket-Flut. */
	public static final int COOLDOWN_TICKS = 3;

	/** Zugriff auf das Spiel (je Minecraft-Version). */
	public interface Game {
		/**
		 * Darf gerade nachgelegt werden? True nur, wenn ein lokaler Spieler da ist, es Überleben/Abenteuer
		 * (nicht Kreativ) ist, kein Bildschirm/Behälter offen ist und Fair Play nicht aktiv ist.
		 */
		boolean active();

		/** Ausgewählter Hotbar-Slot (0..8), oder -1, wenn unbekannt. */
		int selectedSlot();

		/** Gegenstands-Kennung im Inventar-Slot (0..35) oder null/leerer Text, wenn der Slot leer ist. */
		String itemKey(int slot);

		/** Tauscht den Inhalt des Inventar-Slots {@code from} in den Hotbar-Slot {@code toHotbar} (serverseitig). */
		void swap(int from, int toHotbar);
	}

	private int lastSlot = -1;
	private String lastKey;
	private int cooldown;

	/** Merker zurücksetzen (Weltwechsel, Modul aus, Bildschirm offen). */
	public void reset() {
		lastSlot = -1;
		lastKey = null;
		cooldown = 0;
	}

	/** Ein Client-Tick. */
	public void tick(Game game) {
		if (game == null || !game.active()) {
			reset();
			return;
		}
		if (cooldown > 0) cooldown--;
		int sel = game.selectedSlot();
		String cur = sel >= 0 && sel < HOTBAR ? empty(game.itemKey(sel)) : null;
		// Nur nachlegen, wenn derselbe Slot von „voll“ auf „leer“ gewechselt ist (nicht beim Wechsel auf
		// einen schon leeren Slot) und die kurze Pause abgelaufen ist.
		if (sel == lastSlot && cur == null && lastKey != null && cooldown <= 0) {
			int from = findRefillSlot(lastKey, sel, game);
			if (from >= 0) {
				game.swap(from, sel);
				cooldown = COOLDOWN_TICKS;
			}
		}
		lastSlot = sel;
		lastKey = cur;
	}

	/**
	 * Erster Inventar-Slot mit demselben Gegenstand wie {@code wantedKey} – zuerst das Hauptfeld (9..35),
	 * dann die anderen Hotbar-Slots (0..8, ohne den ausgewählten); -1, wenn nichts passt.
	 */
	public static int findRefillSlot(String wantedKey, int selected, Game game) {
		if (wantedKey == null || wantedKey.isEmpty()) return -1;
		for (int i = MAIN_START; i < MAIN_END; i++) {
			if (wantedKey.equals(empty(game.itemKey(i)))) return i;
		}
		for (int i = 0; i < HOTBAR; i++) {
			if (i != selected && wantedKey.equals(empty(game.itemKey(i)))) return i;
		}
		return -1;
	}

	/** Leeren Text als null behandeln. */
	private static String empty(String key) {
		return key == null || key.isEmpty() ? null : key;
	}
}
