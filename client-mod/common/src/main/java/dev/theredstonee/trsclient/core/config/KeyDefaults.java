package dev.theredstonee.trsclient.core.config;

/**
 * Stand der Standard-Tastenbelegungen. Ändert sich ein Standard, wird die alte Belegung
 * nur dann umgestellt, wenn der Spieler sie nie selbst geändert hat (also noch der alte
 * Standard eingestellt ist).
 * <p>Stand 2: Zoom liegt auf V statt auf C – C ist ab Minecraft 1.12 mit „Hotbar speichern“ belegt.
 */
public final class KeyDefaults implements ConfigPart {
	/** Aktueller Stand der Standardbelegungen. */
	public static final int CURRENT = 2;
	/** Stand 1: Zoom auf C. */
	public static final int ZOOM_ON_V = 2;

	private int version = CURRENT;
	private boolean worldMapChecked;

	/** Version der Standardbelegungen aus der Config. */
	public int version() {
		return version;
	}

	/** Steht die Umstellung des Zoom-Standards von C auf V noch aus? */
	public boolean needsZoomKeyMigration() {
		return version < ZOOM_ON_V;
	}

	/** Nach erfolgter Umstellung aufrufen (wird beim nächsten Speichern festgehalten). */
	public void markMigrated() {
		version = CURRENT;
	}

	/**
	 * Steht die einmalige Prüfung der Weltkarten-Taste (Standard M) noch aus? Belegt eine andere Tastenbelegung
	 * (z. B. eine andere Karten-Mod) schon M, wird die TRS-Weltkarte dann freigegeben statt doppelt belegt.
	 */
	public boolean needsWorldMapKeyCheck() {
		return !worldMapChecked;
	}

	public void markWorldMapKeyChecked() {
		worldMapChecked = true;
	}

	/**
	 * Soll die eigene Taste freigegeben werden? Nur, wenn sie noch auf dem Standard liegt und eine andere
	 * Belegung dieselbe Taste nutzt.
	 */
	public static boolean conflicts(String ourKey, String defaultKey, java.util.Collection<String> otherKeys) {
		if (ourKey == null || !ourKey.equals(defaultKey)) return false;
		for (String other : otherKeys) {
			if (ourKey.equals(other)) return true;
		}
		return false;
	}

	@Override
	public void read(TrsConfig config) {
		worldMapChecked = Boolean.TRUE.equals(config.worldMapKeyChecked);
		if (config.keyDefaults != null) {
			version = Math.max(1, Math.min(CURRENT, config.keyDefaults));
		} else {
			// Version 1 kannte das Feld nicht – dort galt noch der alte Standard.
			version = config.configVersion >= 2 ? CURRENT : 1;
		}
	}

	@Override
	public void write(TrsConfig config) {
		config.keyDefaults = version;
		config.worldMapKeyChecked = worldMapChecked ? Boolean.TRUE : null;
	}
}
