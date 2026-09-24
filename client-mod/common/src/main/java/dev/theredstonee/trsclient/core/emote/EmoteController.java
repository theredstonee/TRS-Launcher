package dev.theredstonee.trsclient.core.emote;

import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.PlayerEvent;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.online.Uuids;

import java.util.List;
import java.util.UUID;

/**
 * Emotes im Spiel – versionsunabhängig: welche Emotes freigeschaltet sind (TRS API), Abspielen mit Wartezeit
 * (höchstens 1 / 2 s wie die API), Empfang der Emotes anderer TRS-Spieler (Ereignis-Stream), Animation je Spieler
 * ({@link EmotePlayback} + {@link EmoteRig}) und die Kamera während des eigenen Emotes.
 *
 * <p>Jeder Loader liefert nur: einmal pro Tick die Spieler-Positionen und die Kamera ({@link #tick}), im
 * Modell-Hook die Teile des Spielermodells ({@link #apply}) und das Emote-Rad als Bildschirm. Alles läuft im
 * Spiel-/Render-Thread. Ohne Einwilligung im Launcher oder ohne Anmeldung gibt es keine einzige Anfrage.
 */
public final class EmoteController {
	/** Mindestabstand zwischen zwei eigenen Emotes (API: 1 / 2 s). */
	public static final long COOLDOWN_MS = 2_000L;
	/** Rad geöffnet und die Liste ist älter → neu holen. */
	static final long LIST_MAX_AGE_MS = 60_000L;
	/** Das eigene Emote kommt über den Stream zurück – so lange nach dem Abspielen ignorieren. */
	static final long SELF_ECHO_MS = 4_000L;

	/** Was das Rad anzeigen kann. */
	public enum State {
		READY, LOADING, CONNECTING, RETRY, NO_ACCOUNT, BANNED, OFF_MODULE, OFF_LAUNCHER
	}

	/** Ergebnis von {@link #play}. */
	public enum Result {
		PLAYED, COOLDOWN, LOCKED, UNAVAILABLE
	}

	/** Kamera der Version: 0 = Ich-Perspektive, 1 = von hinten, 2 = von vorn. */
	public interface Camera {
		int FIRST_PERSON = 0;
		int BEHIND = 1;
		int FRONT = 2;

		int get();

		void set(int mode);
	}

	private final TrsModules modules;
	private final TrsOnline online;
	private final EmotePlayback playback = new EmotePlayback();
	private final EmoteRig rig = new EmoteRig();
	private final float[] frame = new float[Channel.COUNT];

	/** UUID des eigenen Spieler-Objekts (aus dem letzten Tick). */
	private UUID self;
	private long lastPlay = Long.MIN_VALUE / 2;
	private long cooldownUntil;
	private String lastPlayId;
	private int cameraBefore = -1;
	private int cameraSet = -1;
	private String lastError;

	public EmoteController(TrsModules modules, TrsOnline online) {
		this.modules = modules;
		this.online = online;
	}

	private boolean enabled() {
		return modules.emotes.isEnabled() && modules.trsOnline.isEnabled();
	}

	/**
	 * Einmal pro Client-Tick (nach {@link TrsOnline#tick}): Emotes anderer Spieler übernehmen, Bewegung prüfen,
	 * Kamera steuern. {@code movers} = Spieler in Sichtweite ({@link EmotePlayback.Mover#self} markiert den eigenen),
	 * {@code camera} darf null sein (keine Kamera-Steuerung).
	 */
	public void tick(long now, List<EmotePlayback.Mover> movers, Camera camera) {
		boolean on = enabled();
		online.wantEmotes(on, on && modules.emoteOthers.get());
		self = null;
		if (movers != null) {
			for (EmotePlayback.Mover m : movers) {
				if (m.self) self = m.uuid;
			}
		}
		for (PlayerEvent e : online.pollEmoteEvents()) receive(e, now);
		if (!on) playback.clear();
		else if (!modules.emoteOthers.get()) playback.retainOnly(self);
		playback.tick(now, movers);
		if (camera != null) updateCamera(now, camera);
	}

	/** Ein Emote-Ereignis aus dem Stream (eigene UUID = Echo des eigenen Emotes). */
	void receive(PlayerEvent e, long now) {
		if (!enabled() || e == null || !"emote".equals(e.type)) return;
		EmoteDef def = Emotes.byId(e.emote);
		if (def == null) return; // unbekannte IDs ignorieren (API.md §12)
		String own = online.ownUuid();
		if (own != null && own.equals(e.uuid)) {
			if (e.emote.equals(lastPlayId) && now - lastPlay < SELF_ECHO_MS) return;
			if (self != null) playback.start(self, def, duration(e.durationMs, def), now);
			return;
		}
		if (!modules.emoteOthers.get()) return;
		UUID uuid = Uuids.toUuid(e.uuid);
		if (uuid != null) playback.start(uuid, def, duration(e.durationMs, def), now);
	}

	private static int duration(int apiMs, EmoteDef def) {
		return apiMs >= 300 && apiMs <= 30_000 ? apiMs : def.durationMs();
	}

	// --- Rad ---

	/** Zustand für das Rad (Hinweis statt Emotes, wenn nicht spielbar). */
	public State state() {
		if (!online.launcherEnabled()) return State.OFF_LAUNCHER;
		if (!modules.trsOnline.isEnabled()) return State.OFF_MODULE;
		switch (online.status()) {
			case LAUNCHER_OFF:
				return State.OFF_LAUNCHER;
			case OFF:
				return State.OFF_MODULE;
			case NO_ACCOUNT:
				return State.NO_ACCOUNT;
			case BANNED:
				return State.BANNED;
			case RETRY:
				return State.RETRY;
			case CONNECTING:
				return State.CONNECTING;
			default:
				return online.unlockedEmotes() == null ? State.LOADING : State.READY;
		}
	}

	/** Das Rad wurde geöffnet: veraltete Liste neu holen. */
	public void wheelOpened(long now) {
		if (enabled()) online.refreshEmotes(now, LIST_MAX_AGE_MS);
	}

	/** Alle Emotes, die das Rad zeigt (feste Reihenfolge wie in der API). */
	public List<EmoteDef> all() {
		return Emotes.ALL;
	}

	/** Darf dieses Emote abgespielt werden (laut API freigeschaltet)? */
	public boolean unlocked(String id) {
		List<String> ids = online.unlockedEmotes();
		return ids != null && ids.contains(id);
	}

	/** Verbleibende Wartezeit bis zum nächsten Emote (ms, 0 = frei). */
	public long cooldownLeft(long now) {
		return Math.max(0, Math.max(lastPlay + COOLDOWN_MS, cooldownUntil) - now);
	}

	/** Emote abspielen: sofort lokal starten, dann der API melden (die zeigt es den anderen). */
	public Result play(String id, long now) {
		EmoteDef def = Emotes.byId(id);
		if (def == null || !enabled() || state() != State.READY) return Result.UNAVAILABLE;
		if (!unlocked(id)) return Result.LOCKED;
		if (cooldownLeft(now) > 0) return Result.COOLDOWN;
		lastPlay = now;
		lastPlayId = id;
		final UUID me = self;
		if (me != null) playback.start(me, def, def.durationMs(), now);
		online.playEmote(id, new TrsOnline.PlayCallback() {
			@Override
			public void done(int durationMs, ApiException error) {
				if (error == null) return;
				lastError = error.code();
				if (error.status() == 403 || error.status() == 404) {
					// Nicht (mehr) freigeschaltet: Animation beenden, Liste neu holen.
					if (me != null) playback.stop(me, System.currentTimeMillis());
					online.refreshEmotes(System.currentTimeMillis(), 0);
				} else if (error.rateLimited()) {
					cooldownUntil = System.currentTimeMillis() + Math.max(COOLDOWN_MS, error.retryAfterMs());
				}
			}
		});
		return Result.PLAYED;
	}

	/** Eigener Angriff/Abbau-Klick: eigenes Emote beenden (API.md §12). */
	public void onAttack(long now) {
		if (self != null) playback.stop(self, now);
	}

	// --- Modell ---

	/**
	 * Wendet das Emote des Spielers {@code uuid} auf die Modellteile an ({@link EmoteRig}-Anordnung).
	 * @return true, wenn eine Pose angewendet wurde
	 */
	public boolean apply(UUID uuid, long now, float[] parts) {
		if (uuid == null || playback.size() == 0) return false;
		float w = playback.sample(uuid, now, frame);
		if (w <= 0f) return false;
		EmoteDef def = playback.current(uuid);
		if (def == null) return false;
		rig.apply(parts, frame, def.mask(), w);
		return true;
	}

	/** Hat der Spieler gerade ein Emote (inkl. Ausblenden)? */
	public boolean animating(UUID uuid) {
		return uuid != null && playback.current(uuid) != null;
	}

	/** Läuft das eigene Emote gerade (nicht abgebrochen)? */
	public boolean selfPlaying(long now) {
		return self != null && playback.playing(self, now);
	}

	/** Letzter Fehlercode beim Abspielen (Tests/Log). */
	public String lastError() {
		return lastError;
	}

	EmotePlayback playback() {
		return playback;
	}

	// --- Kamera ---

	private void updateCamera(long now, Camera camera) {
		boolean playing = selfPlaying(now);
		TrsModules.EmoteCamera mode = modules.emoteCamera.get();
		if (playing && cameraSet < 0 && mode != TrsModules.EmoteCamera.OFF) {
			int current = camera.get();
			if (current == Camera.FIRST_PERSON) {
				int target = mode == TrsModules.EmoteCamera.BACK ? Camera.BEHIND : Camera.FRONT;
				cameraBefore = current;
				cameraSet = target;
				camera.set(target);
			}
		} else if (!playing && cameraSet >= 0) {
			// Nur zurückstellen, wenn der Spieler die Kamera nicht selbst umgeschaltet hat.
			if (camera.get() == cameraSet) camera.set(cameraBefore);
			cameraSet = -1;
			cameraBefore = -1;
		}
	}
}
