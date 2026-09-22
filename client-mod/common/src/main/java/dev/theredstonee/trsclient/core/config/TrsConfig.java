package dev.theredstonee.trsclient.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wurzel der Datei {@code config/trsclient.json} (Gson-DTO).
 * <ul>
 *   <li>Version 1: nur {@code modules}.</li>
 *   <li>Version 2: zusätzlich {@code hudProfiles} (gespeicherte HUD-Layouts) und {@code keyDefaults}
 *   (Stand der Standard-Tasten, für die Zoom-Tasten-Migration C → V).</li>
 * </ul>
 * {@code modules} enthält immer den aktuellen Zustand aller Module (auch des aktiven HUD-Profils),
 * damit ältere Mod-Versionen die Datei weiter lesen können.
 */
public final class TrsConfig {
	public static final int CURRENT_VERSION = 2;

	public int configVersion = CURRENT_VERSION;
	public Map<String, ModuleConfig> modules = new LinkedHashMap<>();
	/** Gespeicherte HUD-Layouts; fehlt in Version 1. */
	public HudProfiles hudProfiles;
	/** Stand der Standard-Tastenbelegungen; fehlt in Version 1. */
	public Integer keyDefaults;

	/** HUD-Profile: Name des aktiven Profils + alle Profile. */
	public static final class HudProfiles {
		public String active;
		public List<Profile> profiles = new ArrayList<>();
	}

	/** Ein HUD-Profil: Zustand aller HUD-Module (An/Aus, Position, Größe, Aussehen). */
	public static final class Profile {
		public String name;
		public Map<String, ModuleConfig> modules = new LinkedHashMap<>();
	}
}
