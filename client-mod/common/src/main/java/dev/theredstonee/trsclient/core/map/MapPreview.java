package dev.theredstonee.trsclient.core.map;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Der eine Schalter für die Karten-Vorschau „Karte v3“ (Relief aus allen Nachbarhöhen, Wassertiefe beim Zeichnen,
 * lebendigere Biomfarben, gestochen scharfe Weltkarte in allen Zoomstufen, schnelleres Laden). Zuerst nur in
 * Vorschau-Builds für Creator (ab Partner-Stufe) und das TRS-Team – in normalen Builds aus, dann bleibt alles wie bisher.
 *
 * <p>Woher der Schalter kommt (einmal beim Start gelesen):
 * <ol>
 *   <li>System-Eigenschaft {@code trsclient.preview} (Komma-Liste): {@code map-v3} schaltet ein, {@code -map-v3} aus –
 *   für Tests und zum Ausprobieren ({@code -Dtrsclient.preview=map-v3});</li>
 *   <li>sonst die Datei {@value #RESOURCE} im Mod-Jar: Vorschau-Builds ({@code ./gradlew … -PtrsPreview=map-v3})
 *   schreiben dort die eingeschalteten Vorschau-Funktionen hinein, normale Builds haben sie nicht.</li>
 * </ol>
 *
 * <p>Die neue Karte speichert in einem eigenen Ordner ({@code config/trsclient/maps-v3}) und liest die bisherigen
 * Karten ({@code maps}) nur – an-/ausschalten beschädigt also nie die Karten der anderen Fassung.
 */
public final class MapPreview {
	/** Name der Vorschau-Funktion. */
	public static final String FEATURE = "map-v3";
	/** Liste der Vorschau-Funktionen im Mod-Jar (nur in Vorschau-Builds vorhanden). */
	public static final String RESOURCE = "/assets/trsclient/preview-features.txt";

	private static volatile Boolean enabled;

	private MapPreview() {
	}

	/** Ist die Karte v3 an? */
	public static boolean enabled() {
		Boolean b = enabled;
		if (b == null) {
			b = decide(System.getProperty("trsclient.preview"), readResource());
			enabled = b;
		}
		return b;
	}

	/** Nur für Tests: Schalter festlegen (null = neu bestimmen). */
	public static void setForTests(Boolean on) {
		enabled = on;
	}

	/**
	 * Entscheidung aus System-Eigenschaft und Build-Liste: die Eigenschaft gewinnt ({@code map-v3} an,
	 * {@code -map-v3}/{@code !map-v3} aus), sonst zählt die Build-Liste.
	 */
	static boolean decide(String property, String buildList) {
		Boolean prop = find(property);
		if (prop != null) return prop;
		Boolean build = find(buildList);
		return build != null && build;
	}

	private static Boolean find(String list) {
		if (list == null) return null;
		Boolean out = null;
		for (String raw : list.split("[,;\\s]+")) {
			String s = raw.trim().toLowerCase(Locale.ROOT);
			if (s.isEmpty()) continue;
			if (s.equals(FEATURE)) out = Boolean.TRUE;
			else if (s.equals("-" + FEATURE) || s.equals("!" + FEATURE)) out = Boolean.FALSE;
		}
		return out;
	}

	private static String readResource() {
		try (InputStream in = MapPreview.class.getResourceAsStream(RESOURCE)) {
			if (in == null) return null;
			ByteArrayOutputStream out = new ByteArrayOutputStream(256);
			byte[] buf = new byte[256];
			int n;
			while ((n = in.read(buf)) > 0 && out.size() < 4096) out.write(buf, 0, n);
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
