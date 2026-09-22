package dev.theredstonee.trsclient.core.ui;

/**
 * Zeichenfläche des TRS-Menüs – die einzige Verbindung der versionsunabhängigen Oberfläche
 * zu Minecraft. Jede Loader-/Versionsvariante liefert eine kleine Implementierung
 * (siehe {@code ui/GfxCanvas}); alle Menüs, der HUD-Editor und die Bedienelemente stehen in
 * {@code core.ui} und kennen Minecraft nicht.
 */
public interface Canvas {
	/** Rechteck von (x1, y1) bis ausschließlich (x2, y2) in ARGB. */
	void fill(int x1, int y1, int x2, int y2, int argb);

	/** Text an der linken oberen Ecke (Schriftgröße 9, Grundlinie wie Vanilla). */
	void text(String text, int x, int y, int argb, boolean shadow);

	/** Breite des Textes in GUI-Pixeln. */
	int textWidth(String text);

	/** Zeilenhöhe der Schrift (Vanilla: 9). */
	int lineHeight();

	/** Schneidet Text auf {@code maxWidth} ab (ohne "…"). */
	String clip(String text, int maxWidth);

	/**
	 * Zeichnet gepufferten Text sofort. Minecraft sammelt Text bis 1.21.5 und zeichnet ihn
	 * erst am Ende – ohne diesen Aufruf läge er über später gezeichneten Flächen.
	 */
	void flush();

	/** Zeichnen auf ein Rechteck begrenzen; mit {@link #noScissor()} beenden. */
	void scissor(int x1, int y1, int x2, int y2);

	void noScissor();

	void push();

	void translate(float x, float y);

	void scale(float factor);

	void pop();
}
