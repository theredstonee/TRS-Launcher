package dev.theredstonee.trsclient.core.touch;

/**
 * Ziehen zum Scrollen mit Schwung: Fingerweg (senkrecht, GUI-Pixel) wird in Mausrad-Rasten umgerechnet – die
 * TRS-Listen scrollen in Raststufen, so funktioniert jede Liste ohne eigenen Touch-Code. Nach dem Loslassen
 * läuft die Bewegung mit der zuletzt gemessenen Geschwindigkeit weiter und bremst exponentiell ab.
 *
 * <p>Vorzeichen wie beim Mausrad: Finger nach unten (dy &gt; 0) = Rad nach oben (+1) = Inhalt rückt nach unten.
 */
public final class TouchScroller {
	/** Fingerweg je Raste (GUI-Pixel). */
	public static final float STEP = 14f;
	/** Abklingzeit des Schwungs in Sekunden (Geschwindigkeit fällt je TAU auf 1/e). */
	public static final float TAU = 0.32f;
	/** Darunter endet der Schwung (Pixel/s). */
	public static final float MIN_VELOCITY = 40f;
	/** Höchste Schwung-Geschwindigkeit (Pixel/s). */
	public static final float MAX_VELOCITY = 2400f;
	/** Ruhte der Finger vor dem Loslassen länger, gibt es keinen Schwung. */
	public static final long STILL_MS = 90;

	private float accumulated;
	private float velocity;
	private boolean flinging;
	private long lastMoveMs;
	private boolean touching;

	/** Finger aufgesetzt (stoppt einen laufenden Schwung). */
	public void start(long nowMs) {
		accumulated = 0f;
		velocity = 0f;
		flinging = false;
		touching = true;
		lastMoveMs = nowMs;
	}

	/**
	 * Finger bewegt.
	 * @return Rasten zum Auslösen (Vorzeichen = Raddrehung)
	 */
	public int move(float dy, long nowMs) {
		long dtMs = Math.max(1, nowMs - lastMoveMs);
		float instant = dy * 1000f / dtMs;
		// Geglättet: kurze Ausreißer (ein langsamer Frame) zählen weniger.
		velocity = dtMs > 2 * STILL_MS ? instant : velocity * 0.4f + instant * 0.6f;
		lastMoveMs = nowMs;
		accumulated += dy;
		return drain();
	}

	/** Finger losgelassen: Schwung beginnt (falls schnell genug und nicht zuvor geruht). */
	public void release(long nowMs) {
		touching = false;
		if (nowMs - lastMoveMs > STILL_MS) velocity = 0f;
		velocity = Math.max(-MAX_VELOCITY, Math.min(MAX_VELOCITY, velocity));
		flinging = Math.abs(velocity) >= MIN_VELOCITY;
		if (!flinging) velocity = 0f;
	}

	/** Schwung sofort beenden. */
	public void stop() {
		flinging = false;
		velocity = 0f;
		accumulated = 0f;
		touching = false;
	}

	/**
	 * Einen Frame weiterrechnen ({@code dt} in Sekunden).
	 * @return Rasten zum Auslösen
	 */
	public int tick(float dt) {
		if (!flinging || touching || dt <= 0f) return 0;
		dt = Math.min(dt, 0.1f);
		// Weg unter exponentieller Bremsung genau integriert: v·τ·(1 − e^(−dt/τ)).
		float decay = (float) Math.exp(-dt / TAU);
		accumulated += velocity * TAU * (1f - decay);
		velocity *= decay;
		if (Math.abs(velocity) < MIN_VELOCITY) {
			flinging = false;
			velocity = 0f;
		}
		return drain();
	}

	public boolean flinging() {
		return flinging;
	}

	public float velocity() {
		return velocity;
	}

	private int drain() {
		int notches = (int) (accumulated / STEP);
		accumulated -= notches * STEP;
		return notches;
	}
}
