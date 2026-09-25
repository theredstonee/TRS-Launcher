package dev.theredstonee.trsclient.core.ui;

/**
 * Zeichenfläche, die alles mit einer gemeinsamen Deckkraft zeichnet – damit blendet das Menü
 * beim Öffnen und Schließen weich ein bzw. aus, ohne dass jede Zeichenstelle das wissen muss.
 * (Minecraft zeichnet Text mit Alpha 0 voll deckend; sehr durchsichtiger Text wird deshalb
 * gar nicht erst gezeichnet.)
 */
public final class FadeCanvas implements Canvas {
	private final Canvas delegate;
	private float factor = 1f;

	private FadeCanvas(Canvas delegate) {
		this.delegate = delegate;
	}

	/** Liefert eine Fläche mit der Deckkraft {@code factor} (1 = unverändert). */
	public static Canvas of(Canvas canvas, float factor) {
		if (factor >= 0.999f) return canvas;
		FadeCanvas fade = canvas instanceof FadeCanvas ? (FadeCanvas) canvas : new FadeCanvas(canvas);
		fade.factor = ColorMath.clamp01(factor);
		return fade;
	}

	/** Die darunterliegende Fläche (z. B. um kurzzeitig ohne Blende zu zeichnen). */
	public Canvas raw() {
		return delegate;
	}

	/** Liefert die echte Zeichenfläche hinter einer möglichen Blende. */
	public static Canvas unwrap(Canvas canvas) {
		return canvas instanceof FadeCanvas ? ((FadeCanvas) canvas).delegate : canvas;
	}

	private int color(int argb) {
		return ColorMath.fade(argb, factor);
	}

	@Override
	public void fill(int x1, int y1, int x2, int y2, int argb) {
		delegate.fill(x1, y1, x2, y2, color(argb));
	}

	@Override
	public void text(String text, int x, int y, int argb, boolean shadow) {
		int c = color(argb);
		if ((c >>> 24) < 8) return;
		delegate.text(text, x, y, c, shadow);
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
		delegate.scissor(x1, y1, x2, y2);
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
	public void scale(float factor) {
		delegate.scale(factor);
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
		int c = color(argb);
		if ((c >>> 24) < 4) return;
		delegate.image(texture, u, v, w, h, c);
	}

	@Override
	public void rotate(float radians) {
		delegate.rotate(radians);
	}

	@Override
	public void scale(float sx, float sy) {
		delegate.scale(sx, sy);
	}
}
