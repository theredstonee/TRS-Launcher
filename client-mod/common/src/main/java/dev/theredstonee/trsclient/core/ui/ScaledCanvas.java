package dev.theredstonee.trsclient.core.ui;

/**
 * Zeichenfläche einer vergrößerten Oberfläche (Touch-Modus): Der Aufrufer hat die Fläche bereits mit
 * {@code push(); scale(factor)} vergrößert; alles Zeichnen läuft unverändert durch.
 *
 * <p>Nur der Scissor braucht Hilfe: Je nach Minecraft-Version beachtet er die Transformation (ab 1.21.6) oder
 * nicht (GL-Scissor, ältere GuiGraphics). Deshalb wird er in Bildschirmkoordinaten (× factor) gesetzt, während die
 * Vergrößerung kurz mit {@code scale(1/factor)} aufgehoben ist – das ergibt in beiden Fällen dasselbe Rechteck.
 */
public final class ScaledCanvas implements Canvas {
	private final Canvas delegate;
	private final float factor;

	private ScaledCanvas(Canvas delegate, float factor) {
		this.delegate = delegate;
		this.factor = factor;
	}

	/** Fläche für eine mit {@code factor} vergrößerte Oberfläche (1 = unverändert). */
	public static Canvas of(Canvas canvas, float factor) {
		if (Math.abs(factor - 1f) < 1e-4f) return canvas;
		return new ScaledCanvas(unwrap(canvas), factor);
	}

	/** Die echte Zeichenfläche (für versionsabhängigen Code, der seine eigene Fläche erwartet). */
	public static Canvas unwrap(Canvas canvas) {
		return canvas instanceof ScaledCanvas ? ((ScaledCanvas) canvas).delegate : canvas;
	}

	public float factor() {
		return factor;
	}

	@Override
	public void fill(int x1, int y1, int x2, int y2, int argb) {
		delegate.fill(x1, y1, x2, y2, argb);
	}

	@Override
	public void text(String text, int x, int y, int argb, boolean shadow) {
		delegate.text(text, x, y, argb, shadow);
	}

	@Override
	public int textWidth(String text) {
		return delegate.textWidth(text);
	}

	@Override
	public int lineHeight() {
		return delegate.lineHeight();
	}

	@Override
	public String clip(String text, int maxWidth) {
		return delegate.clip(text, maxWidth);
	}

	@Override
	public void flush() {
		delegate.flush();
	}

	@Override
	public void scissor(int x1, int y1, int x2, int y2) {
		int[] r = scaledRect(x1, y1, x2, y2, factor);
		delegate.push();
		delegate.scale(1f / factor);
		delegate.scissor(r[0], r[1], r[2], r[3]);
		delegate.pop();
	}

	/** Scissor-Rechteck in Bildschirmkoordinaten: außen gerundet, damit am Rand nichts abgeschnitten wird. */
	static int[] scaledRect(int x1, int y1, int x2, int y2, float factor) {
		return new int[]{(int) Math.floor(x1 * factor), (int) Math.floor(y1 * factor),
				(int) Math.ceil(x2 * factor), (int) Math.ceil(y2 * factor)};
	}

	@Override
	public void noScissor() {
		delegate.noScissor();
	}

	@Override
	public void raise(float z) {
		delegate.raise(z);
	}

	@Override
	public void push() {
		delegate.push();
	}

	@Override
	public void translate(float x, float y) {
		delegate.translate(x, y);
	}

	@Override
	public void scale(float f) {
		delegate.scale(f);
	}

	@Override
	public void pop() {
		delegate.pop();
	}

	@Override
	public boolean images() {
		return delegate.images();
	}

	@Override
	public void image(TextureRef texture, float u, float v, int w, int h, int argb) {
		delegate.image(texture, u, v, w, h, argb);
	}

	@Override
	public void rotate(float radians) {
		delegate.rotate(radians);
	}

	@Override
	public void scale(float sx, float sy) {
		delegate.scale(sx, sy);
	}

	@Override
	public void item(Object stack, int x, int y) {
		delegate.item(stack, x, y);
	}
}
