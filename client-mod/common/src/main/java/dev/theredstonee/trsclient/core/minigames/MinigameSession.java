package dev.theredstonee.trsclient.core.minigames;

/**
 * Eine laufende Partie, wie {@code GameSession} in {@code app/utils/minigames/session.ts}: fester 60-Hz-Takt, höchstens acht
 * Schritte je Bild, Pause, Bild. Die Regeln stecken in {@link Stapler} und {@link Loren}. Nur im Render-Thread benutzen.
 */
public final class MinigameSession {
	private static final double STEP = 1.0 / 60.0;
	private static final int MAX_STEPS_PER_FRAME = 8;
	/** Abstand zweier Bilder im Mittel: mehr als 60 Bilder je Sekunde bringen bei einem 60-Hz-Takt nichts. */
	private static final float IMAGE_GAP = 1f / 60f;
	/** Früher als das kommt kein neues Bild; ein Bildschirm mit etwas mehr als 60 Hz bekommt so trotzdem jedes Bild. */
	private static final float MIN_IMAGE_GAP = 0.012f;
	/** Letztes Bild einer laufenden Partie (System.nanoTime), siehe {@link #running()}. */
	private static volatile long lastPlaying;

	/** Zustand der Partie. */
	public enum State {
		READY, PLAYING, PAUSED, OVER
	}

	/** Eine beendete Partie. */
	public static final class Finished {
		public final String game;
		public final long seed;
		public final String runId;
		public final int[] inputs;
		public final long score;
		public final int ticks;

		Finished(String game, long seed, String runId, int[] inputs, long score, int ticks) {
			this.game = game;
			this.seed = seed;
			this.runId = runId;
			this.inputs = inputs;
			this.score = score;
			this.ticks = ticks;
		}
	}

	public final String game;
	public final FrameMeter meter = new FrameMeter();
	public State state = State.READY;
	public Stapler stapler;
	public Loren loren;
	public final StaplerView staplerView;
	public final LorenView lorenView;
	private final Loren idle = new Loren(4242);
	private final GameSound sound;
	private final PixelFrame frame;
	private String runId;
	private int mask;
	private int latch;
	private double acc;
	private float imageAcc = IMAGE_GAP;
	private float liveAcc;
	private boolean imageKnown;
	private Finished finished;

	MinigameSession(String game, GameTextures textures, GameSkin skin, GameSound sound) {
		this.game = game;
		this.sound = sound;
		if (Replay.STAPLER.equals(game)) {
			staplerView = new StaplerView(textures, skin, sound);
			lorenView = null;
			frame = new PixelFrame("minigames/stapler", StaplerView.WIDTH, StaplerView.HEIGHT);
		} else {
			lorenView = new LorenView(textures, skin, sound);
			staplerView = null;
			frame = new PixelFrame("minigames/loren", LorenView.WIDTH, LorenView.HEIGHT);
		}
	}

	/**
	 * Läuft gerade eine Partie? Dann soll Minecraft das Bild nicht auf die Menü-Grenze drosseln (siehe
	 * {@code mixin/FrameCapMixin}). Läuft von selbst aus, wenn der Bildschirm verschwindet.
	 */
	public static boolean running() {
		long last = lastPlaying;
		return last != 0 && System.nanoTime() - last < 500_000_000L;
	}

	boolean isStapler() {
		return staplerView != null;
	}

	/** Neue Partie. {@code runId} null = nur lokal. */
	public void start(long seed, String runId) {
		this.runId = runId;
		finished = null;
		if (staplerView != null) {
			stapler = new Stapler(seed, new Stapler.Listener() {
				@Override
				public void onEvent(Stapler.Event event) {
					staplerView.onEvent(event);
				}
			});
			staplerView.reset();
		} else {
			loren = new Loren(seed, new Loren.Listener() {
				@Override
				public void onEvent(Loren.EventType type, int o) {
					lorenView.onEvent(type, loren);
				}
			});
			lorenView.reset();
		}
		mask = 0;
		latch = 0;
		acc = 0;
		state = State.PLAYING;
	}

	/** Gerade gehaltene Tasten (je Bild aus dem Spiel gelesen). */
	public void held(int heldMask) {
		if (state == State.PLAYING) mask = heldMask;
	}

	/** Tastendruck seit dem letzten Bild: auch ein kurzer Tipp zwischen zwei Bildern zählt für einen Takt. */
	public void pressed(int bit) {
		if (state == State.PLAYING) latch |= bit;
	}

	public void pause(boolean on) {
		if (on && state == State.PLAYING) {
			mask = 0;
			latch = 0;
			state = State.PAUSED;
		} else if (!on && state == State.PAUSED) {
			acc = 0;
			state = State.PLAYING;
		}
	}

	/** Fenster hat den Fokus verloren: Sonst liefe das Spiel mit gedrückter Taste weiter. */
	public void blur() {
		mask = 0;
		latch = 0;
		pause(true);
	}

	public long score() {
		return stapler != null ? stapler.score : loren != null ? loren.score : 0;
	}

	public int signal() {
		return stapler != null ? stapler.signal : loren != null ? loren.signal : 0;
	}

	/** Ein Bild des Spiels: Takte nachholen, Bild rechnen und hochladen. {@code dt} in Sekunden. */
	public void frame(float dt) {
		meter.frame();
		float clamped = Math.min(0.1f, dt);
		if (state == State.PLAYING) {
			lastPlaying = System.nanoTime();
			acc += clamped;
			int steps = 0;
			while (acc >= STEP && steps < MAX_STEPS_PER_FRAME) {
				acc -= STEP;
				steps++;
				int input = mask | latch;
				latch = 0;
				if (stapler != null) stapler.step(input);
				else loren.step(input);
				if (stapler != null ? stapler.finished() : loren.finished()) {
					finish();
					break;
				}
			}
			if (steps >= MAX_STEPS_PER_FRAME) acc = 0;
		}
		if (state != State.PAUSED) sound.update(clamped);
		if (lorenView != null) lorenView.updateSound(clamped, loren, state == State.PLAYING);
		if (state == State.PAUSED && frame.ready()) return;
		imageAcc += clamped;
		liveAcc += clamped;
		if (imageAcc < MIN_IMAGE_GAP && imageKnown) return;
		float live = state == State.PAUSED ? 0f : liveAcc;
		liveAcc = 0f;
		// Rest mitnehmen: Bei 100 Bildern je Sekunde gäbe es sonst nur jedes zweite, also 50 statt 60.
		imageAcc = imageAcc >= IMAGE_GAP ? Math.min(IMAGE_GAP, imageAcc - IMAGE_GAP) : 0f;
		long t0 = System.nanoTime();
		int[] pixels;
		if (staplerView != null) {
			staplerView.update(live, stapler);
			staplerView.draw(stapler);
			pixels = staplerView.pixels;
		} else {
			lorenView.update(live, loren);
			if (loren == null) {
				// Vor der ersten Fahrt rollt der Stollen im Hintergrund.
				idle.pos += 18;
				idle.tick++;
			}
			lorenView.draw(loren != null ? loren : idle);
			pixels = lorenView.pixels;
		}
		long t1 = System.nanoTime();
		frame.upload(pixels);
		long t2 = System.nanoTime();
		imageKnown = true;
		meter.record(t1 - t0, t2 - t1);
	}

	private void finish() {
		// Erst nach dem ganzen Takt: Loren-Rausch zählt die letzte Strecke noch nach dem Zusammenstoß mit.
		state = State.OVER;
		finished = stapler != null
				? new Finished(game, stapler.seed, runId, stapler.inputs.toArray(), stapler.score, stapler.tick)
				: new Finished(game, loren.seed, runId, loren.inputs.toArray(), loren.score, loren.tick);
	}

	/** Beendete Partie, einmalig; sonst null. */
	Finished takeFinished() {
		Finished out = finished;
		finished = null;
		return out;
	}

	/** Zeichnet das letzte Bild ins Rechteck. */
	void present(dev.theredstonee.trsclient.core.ui.Canvas c, int x, int y, int w, int h) {
		frame.draw(c, x, y, w, h);
	}

	/** Nach einem Ressourcenpaket-Wechsel. */
	void reload(GameTextures textures) {
		if (staplerView != null) staplerView.reload(textures);
		if (lorenView != null) lorenView.reload(textures);
		imageKnown = false;
	}

	void dispose() {
		frame.release();
	}
}
