package dev.theredstonee.trsclient.core.ui;

/**
 * Versionsunabhängiger Bildschirm. Der Minecraft-Bildschirm der jeweiligen Version reicht nur
 * Zeichnen und Eingaben hierher weiter (siehe {@code screen/TrsUiScreen}); die gesamte Oberfläche
 * (Menü, HUD-Editor) steht in {@code core.ui} und kennt Minecraft nicht.
 */
public abstract class UiScreen {
	/** Dauer der Öffnen-/Schließen-Animation in Sekunden. */
	protected static final float OPEN_TIME = 0.16f;

	protected final Hits hits = new Hits();
	/** Öffnungsfortschritt 0..1 (Animation). */
	protected float open;
	private boolean closing;
	private boolean closed;
	private long lastNanos;

	/** Zeichnet den Bildschirm. */
	public final void render(Canvas c, int width, int height, int mouseX, int mouseY) {
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 1f / 60f : Math.min(0.25f, (now - lastNanos) / 1_000_000_000f);
		lastNanos = now;
		open = Anim.approach(open, closing ? 0f : 1f, dt, OPEN_TIME / 2.2f);
		if (closing && open < 0.02f && !closed) {
			closed = true;
			onClosed();
			return;
		}
		hits.clear();
		draw(c, width, height, mouseX, mouseY, dt);
	}

	/** Zeichnen der Unterklasse; {@code dt} = Sekunden seit dem letzten Frame. */
	protected abstract void draw(Canvas c, int width, int height, int mouseX, int mouseY, float dt);

	/** Wird nach der Schließ-Animation aufgerufen (Bildschirm wirklich schließen). */
	protected abstract void onClosed();

	/** Schließen anstoßen (Esc oder Knopf) – schließt nach der Animation. */
	public void requestClose() {
		closing = true;
	}

	public boolean isClosing() {
		return closing;
	}

	/** Wie stark das Menü eingeblendet ist (für Ein-/Ausblenden der Farben). */
	public float alpha() {
		return Anim.easeOut(open);
	}

	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return hits.click(mouseX, mouseY, button);
	}

	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		boolean was = hits.dragging();
		hits.release();
		return was;
	}

	public boolean mouseDragged(double mouseX, double mouseY, int button) {
		return hits.drag(mouseX, mouseY);
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		return false;
	}

	/**
	 * Taste gedrückt.
	 * @param rawKey versionsabhängiger Tastencode (für das Belegen von Tasten)
	 * @param key logische Taste
	 */
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		return false;
	}

	/** Zeichen eingegeben (Textfelder). */
	public boolean charTyped(char c) {
		return false;
	}

	/** Pausiert das Spiel nicht. */
	public boolean pausesGame() {
		return false;
	}

	/**
	 * Kleine Einblendung über dem laufenden Spiel (Schnellantwort): kein abgedunkelter Hintergrund, das HUD bleibt
	 * sichtbar, das Spiel pausiert nicht.
	 */
	public boolean overlay() {
		return false;
	}
}
