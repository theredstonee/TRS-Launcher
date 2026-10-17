package dev.theredstonee.trsclient.core.halloween;

import dev.theredstonee.trsclient.core.module.HalloweenModules;

import java.util.Random;

/**
 * Jumpscares zu Halloween (Vertrag „Client-only“): „Hinter dir!“, Kiste im Dunkeln, tiefe Höhle, Garderoben-Spiegel,
 * Ladebildschirm bei 99 %. Ab Werk an (Schalter im TRS-Menü → Halloween), beim ersten Start nur ein Hinweis-Toast.
 *
 * <p>Schutzregeln für ALLE Arten: nie 10 s nach Schaden (genommen oder ausgeteilt), nie unter 6 Herzen (Leben &lt; 12),
 * höchstens einer je 20 Minuten, nie in den ersten 2 Minuten der Sitzung. Ton nur mit „Grusel-Töne“.
 *
 * <p>Die Entscheidung ist rein (Uhr, Zufall und Schalter von außen) und damit testbar; die Bäume liefern je Tick ein
 * {@link Input}, gezeichnet wird in {@link ScareOverlay}.
 */
public final class Jumpscares {
	/** Abstand nach Schaden (genommen/ausgeteilt). */
	public static final long DAMAGE_PAUSE_MS = 10_000L;
	/** Höchstens ein Schreck je … */
	public static final long COOLDOWN_MS = 20 * 60_000L;
	/** Nie in den ersten … der Sitzung. */
	public static final long WARMUP_MS = 2 * 60_000L;
	/** Unter 6 Herzen nie. */
	public static final float MIN_HEALTH = 12f;

	/** Dauer der Gestalt bei „Hinter dir!“. */
	public static final long BEHIND_MS = 450L;
	/** Gesicht: „…“ vorher (Höhle) und das Gesicht selbst. */
	public static final long DOTS_MS = 1400L;
	public static final long FACE_MS = 1200L;
	/** Höhle: Flüstern, dann Augen am Rand, dann das Gesicht. */
	public static final long WHISPER_MS = 2600L;
	public static final long EYES_MS = 2600L;
	/** Garderoben-Spiegel: Grinsen. */
	public static final long GRIN_MS = 1000L;
	/** Ladebildschirm: bei 99 % stehen, Licht aus, Augen auf. */
	public static final long STALL_MS = 1200L;
	public static final long DARK_MS = 500L;
	public static final long EYES_OPEN_MS = 600L;
	public static final long EYES_HOLD_MS = 1100L;

	/** Gesicht gegen die Scheibe: hereindrücken, halten, verschwinden. */
	public static final long PRESS_MS = 950L;
	/** Entsättigung + Flüstern kriecht über die Anzeige. */
	public static final long CRAWL_MS = 2600L;
	/** Kurz ein 13. Feld mit Auge in der Schnellleiste. */
	public static final long THIRTEENTH_MS = 1300L;
	/** Kurzer, invertierter Lichtblitz. */
	public static final long FLASH_MS = 520L;
	/** Besitzer-Auslöser: eigener Abstand (Vertrag Part C, Client ist die letzte Instanz). */
	public static final long OWNER_COOLDOWN_MS = 60_000L;

	/** Registrierte, generische Grusel-Töne (assets/trsclient/sounds.json). */
	public static final String SOUND_SCREAM = "scare_scream";
	public static final String SOUND_WHISPER = "scare_whisper";
	public static final String SOUND_HEARTBEAT = "scare_heartbeat";

	/** Arten. */
	public enum Type {
		BEHIND_YOU, CHEST, CAVE, MIRROR, LOADING, FACE_PRESS, WHISPER_CRAWL, THIRTEENTH, FLASH
	}

	/** Ton zu einer Art (null = stumm, z. B. Spiegel/Laden). */
	public static String soundFor(Type type) {
		if (type == null) return null;
		switch (type) {
			case CHEST:
			case FACE_PRESS:
			case FLASH:
				return SOUND_SCREAM;
			case BEHIND_YOU:
			case CAVE:
			case WHISPER_CRAWL:
				return SOUND_WHISPER;
			case THIRTEENTH:
				return SOUND_HEARTBEAT;
			default:
				return null;
		}
	}

	/**
	 * Art zu einem Fernauslöser-Namen oder null. Der Server schickt genau diese vier ({@code kind}):
	 * {@code face} (Gesicht an der Scheibe), {@code whisper} (Flüster-Kriechen), {@code eye} (13. Feld mit Auge),
	 * {@code flash} (invertierter Blitz).
	 */
	public static Type typeFromKind(String kind) {
		if (kind == null) return null;
		switch (kind.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "face":
				return Type.FACE_PRESS;
			case "whisper":
				return Type.WHISPER_CRAWL;
			case "eye":
				return Type.THIRTEENTH;
			case "flash":
				return Type.FLASH;
			default:
				return null;
		}
	}

	/** Alle {@code kind}-Namen, die der Besitzer auslösen darf (wie vom Server geschickt). */
	public static final String[] OWNER_KINDS = {"face", "whisper", "eye", "flash"};

	/** Ein Tick aus dem Spiel. */
	public static final class Input {
		public long now;
		public boolean inWorld;
		public float health = 20f;
		/** Eigener Schaden in diesem Tick (Schadens-Animation begann). */
		public boolean hurt;
		/** Nacht in der Oberwelt (Tageszeit 13000–23000). */
		public boolean night;
		public int skyLight = 15;
		public int blockLight = 15;
		/** Ansicht von vorne (F5 zweimal). */
		public boolean front;
		/** In diesem Tick wurde eine Kiste/ein Behälter geöffnet. */
		public boolean containerOpened;

		/** Wirksame Helligkeit (nachts zählt das Himmelslicht 11 Stufen weniger). */
		public int light() {
			return Math.max(blockLight, night ? skyLight - 11 : skyLight);
		}
	}

	/** Schalter (Modul an + Event aktiv + Jumpscares an) und Ton. */
	public interface Settings {
		boolean enabled();

		boolean sounds();
	}

	/** Was gerade läuft (für das Zeichnen). */
	public static final class Scene {
		public final Type type;
		/** ms seit Beginn. */
		public final long age;
		/** Zufallszahl der Szene (Flüster-Text, Lage der Augen). */
		public final int seed;

		Scene(Type type, long age, int seed) {
			this.type = type;
			this.age = age;
			this.seed = seed;
		}

		/** Gesamtdauer der Szene. */
		public long duration() {
			return Jumpscares.duration(type);
		}
	}

	/** Was der Ladebildschirm gerade zeigen soll. */
	public static final class LoadingView {
		/** Anzuzeigender Fortschritt (bleibt beim Schreck bei 0,99 stehen). */
		public final float progress;
		/** Abdunkeln „Licht aus“ 0..1. */
		public final float dark;
		/** Augen öffnen sich 0..1. */
		public final float eyes;

		public LoadingView(float progress, float dark, float eyes) {
			this.progress = progress;
			this.dark = dark;
			this.eyes = eyes;
		}
	}

	private static final Jumpscares INSTANCE = new Jumpscares(new Random(), new Settings() {
		@Override
		public boolean enabled() {
			HalloweenModules h = HalloweenOverlay.modules();
			return h != null && h.on() && h.jumpscares.get();
		}

		@Override
		public boolean sounds() {
			HalloweenModules h = HalloweenOverlay.modules();
			return h != null && h.sounds.get();
		}
	});

	private final Random random;
	private final Settings settings;

	private long sessionStart = -1;
	private long lastScare = Long.MIN_VALUE / 2;
	private long lastOwnerScare = Long.MIN_VALUE / 2;
	private long lastDamage = Long.MIN_VALUE / 2;
	private float health = 20f;
	private boolean inWorld;

	private Type active;
	private long activeStart;
	private int activeSeed;

	private long frontSince = -1;
	private boolean frontRolled;
	private long darkSince = -1;
	private long lastDarkRoll;
	private boolean chestDone;

	private long mirrorLastCall = Long.MIN_VALUE / 2;
	private long mirrorAt = -1;

	private long loadingLastCall = Long.MIN_VALUE / 2;
	private boolean loadingScare;
	private long loadingStart;
	private long stallStart = -1;

	/** Zusätzliche Grusel-Stimmung nachts (Flüster-Kriechen, 13. Feld): je Sekunde würfeln. */
	private long ambientSince = -1;
	private long lastAmbientRoll;

	/** Ausstehender Ton (ID oder null) und ob er trotz „Grusel-Töne“-Schalter spielen soll (Besitzer-Auslöser). */
	private String pendingSound;
	private boolean pendingForce;

	Jumpscares(Random random, Settings settings) {
		this.random = random;
		this.settings = settings;
	}

	public static Jumpscares get() {
		return INSTANCE;
	}

	// --- Schutzregeln ---

	/**
	 * Darf jetzt ein Schreck kommen? {@code needsPlayer} = Arten im Spiel (Leben/Schaden zählen); Spiegel und
	 * Ladebildschirm prüfen nur Sitzung, Abstand und Schalter.
	 */
	public synchronized boolean allowed(long now, boolean needsPlayer) {
		if (!settings.enabled()) return false;
		if (sessionStart < 0) sessionStart = now;
		if (now - sessionStart < WARMUP_MS) return false;
		if (now - lastScare < COOLDOWN_MS) return false;
		if (now - lastDamage < DAMAGE_PAUSE_MS) return false;
		if (needsPlayer && (!inWorld || health < MIN_HEALTH)) return false;
		if (active != null) return false;
		return true;
	}

	/** Eigener Treffer (ausgeteilter Schaden) – zählt wie genommener Schaden für die 10-s-Pause. */
	public synchronized void onDamageDealt(long now) {
		lastDamage = now;
	}

	/**
	 * Besitzer-Auslöser (Part C, SSE {@code jumpscare}): einen benannten Schreck sofort zeigen – mit eigenem Ton,
	 * unabhängig vom „Grusel-Töne“-Schalter. Eigene Schutzregeln: Jumpscares an, im Spiel, mindestens 6 Herzen, kein
	 * Schaden in den letzten 10 s, nichts läuft gerade. Kein 20-Minuten-Abstand und kein 2-Minuten-Anlauf (der
	 * Besitzer-Auslöser hat laut Vertrag seinen eigenen Takt, 60 s serverseitig). @return true, wenn gestartet
	 */
	public synchronized boolean triggerOwner(Type type, long now) {
		if (type == null || type == Type.MIRROR || type == Type.LOADING) return false;
		if (!settings.enabled()) return false;
		if (!inWorld || health < MIN_HEALTH) return false;
		if (now - lastDamage < DAMAGE_PAUSE_MS) return false;
		if (now - lastOwnerScare < OWNER_COOLDOWN_MS) return false;
		if (active != null) return false;
		lastOwnerScare = now;
		start(type, now, true);
		return true;
	}

	// --- Tick ---

	/** Einmal je Client-Tick. */
	public synchronized void tick(Input in) {
		long now = in.now;
		if (sessionStart < 0) sessionStart = now;
		inWorld = in.inWorld;
		health = in.health;
		if (in.hurt) lastDamage = now;
		// Laufende Szene beenden bzw. abbrechen (Welt verlassen, Schaden mitten in der Höhle).
		if (active != null) {
			boolean done = now - activeStart >= duration(active);
			boolean abort = active != Type.LOADING && active != Type.MIRROR && (!in.inWorld || in.hurt);
			if (done || abort) active = null;
		}
		if (!in.inWorld) {
			frontSince = -1;
			darkSince = -1;
			return;
		}
		// „Hinter dir!“: nachts von vorne, nach 1,2 s einmal je Umschalten würfeln (1 zu 4).
		if (in.front && in.night) {
			if (frontSince < 0) {
				frontSince = now;
				frontRolled = false;
			}
			if (!frontRolled && now - frontSince >= 1200) {
				frontRolled = true;
				// Von vorne nachts: meist die Schattengestalt, manchmal ein Gesicht gegen die Scheibe.
				if (allowed(now, true) && random.nextInt(4) == 0) {
					start(random.nextInt(3) == 1 ? Type.FACE_PRESS : Type.BEHIND_YOU, now);
				}
			}
		} else {
			frontSince = -1;
		}
		// Zusätzliche Grusel-Stimmung nachts (nicht in der Vorne-Ansicht): sehr selten das Flüster-Kriechen
		// über die Anzeige oder ein 13. Feld mit Auge – je Sekunde 1 zu 90.
		if (in.night && !in.front) {
			if (ambientSince < 0) {
				ambientSince = now;
				lastAmbientRoll = now;
			}
			if (now - ambientSince >= 5_000L && now - lastAmbientRoll >= 1000L) {
				lastAmbientRoll = now;
				if (allowed(now, true) && random.nextInt(90) == 0) {
					start(random.nextInt(2) == 0 ? Type.WHISPER_CRAWL : Type.THIRTEENTH, now);
				}
			}
		} else {
			ambientSince = -1;
		}
		// Kiste im Dunkeln: selten (1 zu 12), höchstens einmal je Sitzung.
		if (in.containerOpened && !chestDone && in.light() <= 3) {
			if (allowed(now, true) && random.nextInt(12) == 0) {
				chestDone = true;
				start(Type.CHEST, now);
			}
		}
		// Tiefe Höhle: Lichtstufe 0 seit 10 s, dann je Sekunde 1 zu 40.
		if (in.skyLight == 0 && in.blockLight == 0) {
			if (darkSince < 0) {
				darkSince = now;
				lastDarkRoll = now;
			}
			if (now - darkSince >= 10_000L && now - lastDarkRoll >= 1000L) {
				lastDarkRoll = now;
				if (allowed(now, true) && random.nextInt(40) == 0) start(Type.CAVE, now);
			}
		} else {
			darkSince = -1;
		}
	}

	private void start(Type type, long now) {
		start(type, now, false);
	}

	private void start(Type type, long now, boolean forceSound) {
		active = type;
		activeStart = now;
		activeSeed = random.nextInt(1 << 20);
		lastScare = now;
		String sound = soundFor(type);
		if (sound != null) {
			pendingSound = sound;
			pendingForce = forceSound;
		}
	}

	static long duration(Type type) {
		switch (type) {
			case BEHIND_YOU:
				return BEHIND_MS;
			case CHEST:
				return FACE_MS;
			case CAVE:
				return WHISPER_MS + EYES_MS + DOTS_MS / 2 + FACE_MS;
			case MIRROR:
				return GRIN_MS;
			case FACE_PRESS:
				return PRESS_MS;
			case WHISPER_CRAWL:
				return CRAWL_MS;
			case THIRTEENTH:
				return THIRTEENTH_MS;
			case FLASH:
				return FLASH_MS;
			default:
				return STALL_MS + DARK_MS + EYES_OPEN_MS + EYES_HOLD_MS;
		}
	}

	/** Laufende Szene im Spiel (Hinter dir, Kiste, Höhle) oder null. */
	public synchronized Scene current(long now) {
		if (active == null || active == Type.MIRROR || active == Type.LOADING) return null;
		long age = now - activeStart;
		if (age >= duration(active)) {
			active = null;
			return null;
		}
		return new Scene(active, age, activeSeed);
	}

	/**
	 * Holt den ausstehenden Grusel-Ton (einmal je Schreck) oder null. Normale Schrecke tönen nur mit „Grusel-Töne“;
	 * der Besitzer-Auslöser (und MiyakoMCs Timer, eigener Pfad) tönt immer.
	 */
	public synchronized String takeSound() {
		String sound = pendingSound;
		if (sound == null) return null;
		boolean force = pendingForce;
		pendingSound = null;
		pendingForce = false;
		return force || settings.sounds() ? sound : null;
	}

	// --- Ladebildschirm ---

	/**
	 * Ladebildschirm (Mondlauf): {@code progress} wie vom Spiel (−1 = unbestimmt). Liefert, was gezeichnet werden soll.
	 * Ein neuer Ladevorgang beginnt, wenn länger als 2 s nicht gefragt wurde; dann sehr selten (1 zu 15) der Schreck:
	 * bei 99 % stehen bleiben, Licht aus, zwei Augen öffnen sich.
	 */
	public synchronized LoadingView loading(float progress, long nowMs) {
		if (nowMs - loadingLastCall > 2000L) {
			loadingScare = allowed(nowMs, false) && random.nextInt(15) == 0;
			stallStart = -1;
			loadingStart = nowMs;
		}
		loadingLastCall = nowMs;
		if (!loadingScare) return new LoadingView(progress, 0f, 0f);
		if (stallStart < 0) {
			// Unbestimmter Fortschritt (−1): nach 1,5 s so tun, als wäre er gleich fertig.
			boolean nearlyDone = progress >= 0.9f || (progress < 0 && nowMs - loadingStart >= 1500L);
			if (!nearlyDone) return new LoadingView(progress, 0f, 0f);
			stallStart = nowMs;
			lastScare = nowMs;
			active = Type.LOADING;
			activeStart = nowMs;
		}
		long t = nowMs - stallStart;
		long total = STALL_MS + DARK_MS + EYES_OPEN_MS + EYES_HOLD_MS;
		if (t >= total) {
			loadingScare = false;
			if (active == Type.LOADING) active = null;
			return new LoadingView(progress, 0f, 0f);
		}
		float dark = t < STALL_MS ? 0f : Math.min(1f, (t - STALL_MS) / (float) DARK_MS);
		float eyes = t < STALL_MS + DARK_MS ? 0f : Math.min(1f, (t - STALL_MS - DARK_MS) / (float) EYES_OPEN_MS);
		return new LoadingView(0.99f, dark, eyes);
	}

	// --- Garderobe ---

	/**
	 * Garderoben-Spiegel: jetzt das Grinsen über das Gesicht der Vorschau zeichnen? (jedes Bild fragen, solange die
	 * Vorschau zu sehen ist). Je Öffnen einmal würfeln (1 zu 6), das Grinsen kommt nach 2,5–6 s für 1 s.
	 */
	public synchronized boolean mirrorGrin(long nowMs) {
		if (nowMs - mirrorLastCall > 1500L) {
			// Neu geöffnet.
			mirrorAt = allowed(nowMs, false) && random.nextInt(6) == 0 ? nowMs + 2500L + random.nextInt(3500) : -1;
		}
		mirrorLastCall = nowMs;
		if (mirrorAt < 0 || nowMs < mirrorAt) return false;
		if (active == null) {
			active = Type.MIRROR;
			activeStart = mirrorAt;
			lastScare = mirrorAt;
		}
		if (nowMs - mirrorAt < GRIN_MS) return true;
		mirrorAt = -1;
		if (active == Type.MIRROR) active = null;
		return false;
	}

	// --- Selbsttest ---

	/** Autotest: Art sofort starten (ohne Schutzregeln). */
	public synchronized void forceForTest(Type type, long now) {
		if (type == Type.LOADING) {
			loadingScare = true;
			stallStart = -1;
			loadingLastCall = now;
			loadingStart = now;
			return;
		}
		if (type == Type.MIRROR) {
			mirrorAt = now;
			mirrorLastCall = now;
			return;
		}
		active = type;
		activeStart = now;
		activeSeed = random.nextInt(1 << 20);
	}

	/** Zustand für Protokolle/Autotest. */
	public synchronized String info(long now) {
		return "active=" + active + (active != null ? " age=" + (now - activeStart) : "") + " sinceScare="
				+ (now - lastScare) + " sinceDamage=" + (now - lastDamage) + " health=" + health
				+ " session=" + (sessionStart < 0 ? -1 : now - sessionStart);
	}

	/** Nur Tests: Sitzungsbeginn setzen. */
	synchronized void sessionStartForTest(long at) {
		sessionStart = at;
	}
}
