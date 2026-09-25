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
	/** Weltkarten-Taste M einmal auf Doppelbelegung geprüft (null/false = noch nicht). */
	public Boolean worldMapKeyChecked;
	/** Leistung: ursprüngliche Werte vor „FPS-Boost“/„Beheben“ (für „Rückgängig“); optional. */
	public PerfUndo perfUndo;
	/** Einführung, „NEU“-Markierungen und im Client gewähltes Aussehen; optional (fehlt bis 0.5.x). */
	public ClientStateData clientState;

	/** Zustand von Einführung, „NEU“-Markierungen und Aussehen (siehe {@code core.intro.ClientState}). */
	public static final class ClientStateData {
		/** Einführung erledigt (fertig, übersprungen oder „schon eingerichtet“). */
		public Boolean introDone;
		/** "finished", "skipped", "existing" oder "account" (auf einem anderen PC erledigt). */
		public String introHow;
		public Long introAt;
		/** Gewähltes Modul-Paket (nur zur Anzeige). */
		public String introPack;
		/** Kurze Begrüßung „schon eingerichtet“ in dieser Instanz gezeigt. */
		public Boolean welcomeShown;
		/** Im Client gewähltes Aussehen (Thema, Akzent, Sprache) und wann. */
		public String lookTheme;
		public String lookAccent;
		public String lookLanguage;
		public Long lookAt;
		/** „NEU“: Stand, ab dem neue Module markiert werden, und bereits geöffnete Einträge. */
		public String newBaseline;
		public List<String> newSeen;
		/** Config-Modus der Optimierungs-Mods ("pretty" = schön, "max" = maximale FPS); null = nie gewählt. */
		public String fpsMode;
	}

	/** Alte Werte der Vanilla-Optionen (Name → Wert) und der Leistungs-Module vor der ersten Änderung. */
	public static final class PerfUndo {
		public Map<String, Integer> options = new LinkedHashMap<>();
		public Map<String, ModuleConfig> modules = new LinkedHashMap<>();
	}

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
