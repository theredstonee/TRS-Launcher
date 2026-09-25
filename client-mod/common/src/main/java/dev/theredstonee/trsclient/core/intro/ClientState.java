package dev.theredstonee.trsclient.core.intro;

import dev.theredstonee.trsclient.core.config.ConfigPart;
import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.module.NewMarkers;
import dev.theredstonee.trsclient.core.module.NewSince;

import java.util.ArrayList;

/**
 * Zustand rund um die Einführung (in {@code trsclient.json}, Teil {@code clientState}):
 * <ul>
 *   <li>Einführung erledigt? – je Instanz; mit TRS-Konto zusätzlich im {@code client}-Sync (einmal je Konto).</li>
 *   <li>„NEU“-Markierungen ({@link NewMarkers}).</li>
 *   <li>Im Client gewähltes Aussehen (Thema, Akzent, Sprache) mit Zeitpunkt – gilt, wenn es neuer ist als die
 *   Datei des Launchers und das Konto.</li>
 * </ul>
 * Neuinstallation (noch keine {@code trsclient.json}): Einführung offen, nichts „NEU“. Update von 0.5.x (Datei da,
 * aber ohne diesen Teil): Einführung gilt als erledigt („schon eingerichtet“), Neues seit 0.5.0 wird markiert.
 */
public final class ClientState implements ConfigPart {
	public static final String FINISHED = "finished";
	public static final String SKIPPED = "skipped";
	public static final String EXISTING = "existing";
	/** Auf einem anderen PC/in einer anderen Instanz mit demselben TRS-Konto erledigt. */
	public static final String ACCOUNT = "account";

	private boolean introDone;
	private String introHow;
	private long introAt;
	private String introPack;
	private boolean welcomeShown;
	private String lookTheme;
	private String lookAccent;
	private String lookLanguage;
	private long lookAt;
	private String fpsMode;
	private final NewMarkers news = new NewMarkers(NewSince.latest());
	private int revision;

	// --- Einführung ---

	public boolean introDone() {
		return introDone;
	}

	public String introHow() {
		return introHow;
	}

	public long introAt() {
		return introAt;
	}

	public String introPack() {
		return introPack;
	}

	/** Einführung abgeschlossen ({@link #FINISHED}), übersprungen ({@link #SKIPPED}) … */
	public void markIntro(String how, String pack, long at) {
		introDone = true;
		introHow = how;
		introPack = pack;
		introAt = at;
		revision++;
	}

	/** Nur zum erneuten Starten/Testen: Einführung wieder offen. */
	public void resetIntro() {
		introDone = false;
		introHow = null;
		introAt = 0;
		introPack = null;
		welcomeShown = false;
		revision++;
	}

	public boolean welcomeShown() {
		return welcomeShown;
	}

	public void markWelcomeShown() {
		welcomeShown = true;
		revision++;
	}

	// --- Aussehen ---

	public String lookTheme() {
		return lookTheme;
	}

	public String lookAccent() {
		return lookAccent;
	}

	public String lookLanguage() {
		return lookLanguage;
	}

	/** Zeitpunkt der letzten Wahl im Client (0 = nie). */
	public long lookAt() {
		return lookAt;
	}

	public void chooseLook(String theme, String accent, String language, long at) {
		lookTheme = theme;
		lookAccent = accent;
		lookLanguage = language;
		lookAt = at;
		revision++;
	}

	// --- Leistung ---

	/** Gewählter Config-Modus der Optimierungs-Mods ({@link FpsModeChooser}), null = nie gewählt. */
	public String fpsMode() {
		return fpsMode;
	}

	public void setFpsMode(String mode) {
		fpsMode = clean(mode, 16);
		revision++;
	}

	// --- NEU ---

	public NewMarkers news() {
		return news;
	}

	/** Steigt bei jeder Änderung (auch der NEU-Markierungen). */
	public int revision() {
		return revision + news.revision();
	}

	// --- Config ---

	@Override
	public void read(TrsConfig config) {
		TrsConfig.ClientStateData d = config.clientState;
		if (d == null) {
			boolean upgrade = config.modules != null && !config.modules.isEmpty();
			introDone = upgrade;
			introHow = upgrade ? EXISTING : null;
			introAt = 0;
			introPack = null;
			welcomeShown = upgrade;
			lookTheme = lookAccent = lookLanguage = null;
			lookAt = 0;
			fpsMode = null;
			news.set(upgrade ? NewSince.LEGACY_BASELINE : NewSince.latest(), null);
			revision++;
			return;
		}
		introDone = Boolean.TRUE.equals(d.introDone);
		introHow = clean(d.introHow, 16);
		introAt = d.introAt == null ? 0 : Math.max(0, d.introAt);
		introPack = clean(d.introPack, 24);
		welcomeShown = Boolean.TRUE.equals(d.welcomeShown);
		lookTheme = clean(d.lookTheme, 32);
		lookAccent = clean(d.lookAccent, 32);
		lookLanguage = clean(d.lookLanguage, 16);
		lookAt = d.lookAt == null ? 0 : Math.max(0, d.lookAt);
		fpsMode = clean(d.fpsMode, 16);
		news.set(NewSince.valid(d.newBaseline) ? d.newBaseline : NewSince.LEGACY_BASELINE, d.newSeen);
		revision++;
	}

	@Override
	public void write(TrsConfig config) {
		TrsConfig.ClientStateData d = new TrsConfig.ClientStateData();
		d.introDone = introDone;
		d.introHow = introHow;
		d.introAt = introAt == 0 ? null : introAt;
		d.introPack = introPack;
		d.welcomeShown = welcomeShown;
		d.lookTheme = lookTheme;
		d.lookAccent = lookAccent;
		d.lookLanguage = lookLanguage;
		d.lookAt = lookAt == 0 ? null : lookAt;
		d.fpsMode = fpsMode;
		d.newBaseline = news.baseline();
		d.newSeen = new ArrayList<String>(news.seen());
		config.clientState = d;
	}

	/** Nur harmlose Kennungen ({@code [A-Za-z0-9_-]}), sonst null. */
	static String clean(String s, int max) {
		if (s == null || s.isEmpty() || s.length() > max) return null;
		return s.matches("[A-Za-z0-9_-]+") ? s : null;
	}
}
