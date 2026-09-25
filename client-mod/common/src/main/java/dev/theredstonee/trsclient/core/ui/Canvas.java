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

	/**
	 * Hebt die Zeichenebene um {@code z} nach vorn (zwischen {@link #push()} und {@link #pop()}).
	 * Minecraft zeichnet GUI-Text mit eigener Tiefe; ohne das Anheben lägen Fenster dahinter.
	 */
	void raise(float z);

	void push();

	void translate(float x, float y);

	void scale(float factor);

	void pop();

	// --- Texturen und freie Transformation (für Skins, Umhänge, Vorschaubilder) ---

	/**
	 * Kann diese Fläche Texturen zeichnen ({@link #image}, {@link #rotate}, {@link #scale(float, float)})?
	 * Versionen ohne diese Möglichkeit zeichnen dort nichts – Aufrufer weichen dann auf Rechtecke aus.
	 */
	default boolean images() {
		return false;
	}

	/**
	 * Zeichnet den Texturausschnitt ab Texel ({@code u}, {@code v}) mit {@code w}×{@code h} Texeln in das
	 * Rechteck (0, 0)–({@code w}, {@code h}) der aktuellen Transformation, eingefärbt mit {@code argb}
	 * (0xFFFFFFFF = unverändert; Alpha = Deckkraft). Position und Größe kommen über
	 * {@link #translate}/{@link #scale(float, float)}/{@link #rotate} bzw. {@link Affine}.
	 * Die Reihenfolge bleibt erhalten (später gezeichnet liegt oben), auch gegenüber {@link #fill}.
	 */
	default void image(TextureRef texture, float u, float v, int w, int h, int argb) {
	}

	/** Dreht die Zeichenebene um {@code radians} (positiv = im Uhrzeigersinn, da y nach unten zeigt). */
	default void rotate(float radians) {
	}

	/** Ungleichmäßige Skalierung (zwischen {@link #push()} und {@link #pop()}); beide Faktoren > 0. */
	default void scale(float sx, float sy) {
		if (sx == sy) scale(sx);
	}
}
