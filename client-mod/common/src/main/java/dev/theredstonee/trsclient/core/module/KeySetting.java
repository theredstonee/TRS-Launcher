package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.config.ModuleConfig;

/**
 * Tastenbelegung eines Moduls. Gespeichert wird der versionsunabhängige Tastenname im
 * Vanilla-Format ({@code "key.keyboard.v"}, {@code "key.mouse.4"}), damit die Config zwischen
 * Minecraft-Versionen (GLFW, SDL, LWJGL 2) gleich bleibt. Ob die Taste gedrückt ist, fragt der
 * versionsabhängige Code ab (z. B. {@code compat.Keys#isDown(KeySetting)}).
 */
public final class KeySetting extends Setting {
	/** Keine Taste belegt. */
	public static final String NONE = "key.keyboard.unknown";

	/**
	 * Verbindung zu einer Vanilla-Tastenbelegung (Zoom, Freelook): die Taste gehört dann den
	 * Minecraft-Steuerungen (options.txt) und ist dort wie im TRS-Menü änderbar – eine Quelle.
	 */
	public interface Link {
		/** Aktuelle Belegung als Tastenname ({@code "key.keyboard.v"}). */
		String get();

		/** Neue Belegung ({@link #NONE} = unbelegt); speichert die Minecraft-Optionen. */
		void set(String keyName);
	}

	private final String defaultKey;
	private String keyName;
	private Link link;

	public KeySetting(String key, String label, String defaultKey) {
		super(key, label);
		this.defaultKey = valid(defaultKey) ? defaultKey : NONE;
		this.keyName = this.defaultKey;
	}

	/** Unbelegte Tastenbelegung. */
	public KeySetting(String key, String label) {
		this(key, label, NONE);
	}

	/**
	 * Verbindet die Einstellung mit einer Vanilla-Tastenbelegung (vom Loader beim Start gesetzt).
	 * Ohne Verbindung (z. B. in Tests) speichert die Einstellung die Taste selbst.
	 */
	public void link(Link link) {
		this.link = link;
	}

	public boolean isLinked() {
		return link != null;
	}

	/** Standard-Taste. */
	public String defaultKey() {
		return defaultKey;
	}

	/** Tastenname, z. B. {@code "key.keyboard.v"}; {@link #NONE} = unbelegt. */
	public String get() {
		if (link != null) {
			try {
				String v = link.get();
				return valid(v) ? v : NONE;
			} catch (RuntimeException e) {
				return NONE;
			}
		}
		return keyName;
	}

	public boolean isBound() {
		return !NONE.equals(get());
	}

	public void set(String keyName) {
		this.keyName = valid(keyName) ? keyName : NONE;
		if (link != null) link.set(this.keyName);
	}

	public void unbind() {
		set(NONE);
	}

	/** Nur Namen der Form {@code key.keyboard.*} / {@code key.mouse.*} mit harmlosen Zeichen. */
	static boolean valid(String name) {
		if (name == null || name.length() > 64) return false;
		if (!name.startsWith("key.keyboard.") && !name.startsWith("key.mouse.")) return false;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '_')) return false;
		}
		return true;
	}

	@Override
	public void read(ModuleConfig config) {
		// Verbundene Tasten stehen in Minecrafts options.txt, nicht in der TRS-Config.
		if (link != null) return;
		String v = config.keys.get(key());
		keyName = valid(v) ? v : defaultKey;
	}

	@Override
	public void write(ModuleConfig config) {
		if (link != null) return;
		config.keys.put(key(), keyName);
	}

	@Override
	public void reset() {
		keyName = defaultKey;
		if (link != null) link.set(defaultKey);
	}
}
