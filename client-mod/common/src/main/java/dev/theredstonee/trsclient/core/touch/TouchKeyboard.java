package dev.theredstonee.trsclient.core.touch;

import dev.theredstonee.trsclient.core.link.TrsLink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Bildschirmtastatur im Touch-Modus: Hat ein Textfeld (TRS-Oberfläche oder Vanilla-Chat/Schild/Amboss/Buch) den
 * Fokus, bittet die Mod die Engine, die Tastatur zu zeigen – und blendet sie wieder aus, sobald keines mehr fokussiert
 * ist. Zwei Wege (Vertrag: {@code docs/touch-mode.md}):
 * <ol>
 *   <li><b>TRS Link</b>: kann der Launcher es ({@code challenge.features} enthält {@value #FEATURE}), geht eine Zeile
 *       {@code {"type":"keyboard.show","field":"chat"}} bzw. {@code {"type":"keyboard.hide"}} über den Link.</li>
 *   <li><b>Rückfall-Datei</b> zum Abfragen: {@code config/trsclient/touch-state.json} =
 *       {@code {"version":1,"keyboard":true,"field":"chat","seq":7}} (atomar ersetzt, {@code seq} zählt jede Änderung)
 *       – wird im Touch-Modus immer geschrieben. Dazu eine Logzeile {@code [TRS-Touch] keyboard.show chat}.</li>
 * </ol>
 * Außerhalb des Touch-Modus passiert hier nichts.
 */
public final class TouchKeyboard {
	/** Merkmal im Link (Launcher und Spiel): Tastatur-Anfragen. */
	public static final String FEATURE = "keyboard";
	public static final String FILE_NAME = "touch-state.json";

	/** Art des Feldes (die Engine kann daran z. B. eine Eingabetaste „Senden“ wählen). */
	public static final String FIELD_TEXT = "text";
	public static final String FIELD_MULTILINE = "multiline";
	public static final String FIELD_CHAT = "chat";
	public static final String FIELD_SIGN = "sign";
	public static final String FIELD_ANVIL = "anvil";
	public static final String FIELD_BOOK = "book";

	/** So viele Ticks ohne Feld, bevor ausgeblendet wird (Wechsel zwischen Bildschirmen flackert sonst). */
	static final int HIDE_DELAY_TICKS = 2;
	/** Eine Meldung der TRS-Oberfläche gilt so lange (sie kommt je Frame). */
	static final long UI_REPORT_MS = 300;

	/** Textfeld der TRS-Oberfläche. */
	public interface Field {
		boolean focused();
	}

	/** Ziel der Meldungen (für Tests austauschbar). */
	public interface Sink {
		void changed(boolean show, String field, int seq);
	}

	// --- Fokus der TRS-Textfelder (nur Spiel-Thread) ---

	private static Field focusedField;
	private static Object focusedOwner;
	private static boolean focusedMultiline;
	/** Bildschirm, der gerade Eingaben/Zeichnen bekommt (Besitzer neu fokussierter Felder). */
	private static Object currentOwner;

	// --- Gemeldeter Zustand ---

	private static volatile String uiField;
	private static volatile long uiReportAt;
	private static final State STATE = new State();
	private static Sink sink;
	private static Path configDir;

	private TouchKeyboard() {
	}

	/** Bildschirm, in dessen Eingabe/Zeichnen gerade Felder fokussiert werden (null = keiner). */
	public static Object enter(Object owner) {
		Object before = currentOwner;
		currentOwner = owner;
		return before;
	}

	public static void leave(Object before) {
		currentOwner = before;
	}

	/** Ein TRS-Textfeld hat den Fokus bekommen bzw. verloren. */
	public static void focus(Field field, boolean focused, boolean multiline) {
		if (!TouchMode.enabled() || field == null) return;
		if (focused) {
			focusedField = field;
			focusedOwner = currentOwner;
			focusedMultiline = multiline;
		} else if (focusedField == field) {
			focusedField = null;
			focusedOwner = null;
		}
	}

	/** Art des fokussierten Feldes dieses Bildschirms oder null. */
	public static String fieldOf(Object owner) {
		Field f = focusedField;
		if (f == null || owner == null) return null;
		// Im Konstruktor fokussiert (noch kein Bildschirm aktiv): gehört dem ersten, der zeichnet.
		if (focusedOwner == null) focusedOwner = owner;
		if (focusedOwner != owner || !f.focused()) return null;
		return focusedMultiline ? FIELD_MULTILINE : FIELD_TEXT;
	}

	/** Je Frame vom TRS-Bildschirm: fokussiertes Feld (oder null). */
	public static void reportUi(String field, long nowMs) {
		uiField = field;
		uiReportAt = nowMs;
	}

	/** Für den Tick: Feld der TRS-Oberfläche, falls sie gerade gezeichnet wird. */
	static String currentUiField(long nowMs) {
		return nowMs - uiReportAt <= UI_REPORT_MS ? uiField : null;
	}

	/** Ordner für die Rückfall-Datei ({@code config/trsclient} liegt darunter); Standard: der von {@code I18n}. */
	public static void init(Path config) {
		configDir = config;
	}

	/** Je Tick: gewünschtes Vanilla-Feld (null = keins) zusammen mit dem der TRS-Oberfläche auswerten. */
	public static void tick(String vanillaField, long nowMs) {
		if (!TouchMode.enabled()) return;
		String ui = currentUiField(nowMs);
		Sink s = sink != null ? sink : DEFAULT_SINK;
		STATE.update(ui != null ? ui : vanillaField, s);
	}

	/** Nur für Tests. */
	public static void setSinkForTests(Sink testSink) {
		sink = testSink;
		STATE.reset();
		focusedField = null;
		focusedOwner = null;
		currentOwner = null;
		uiField = null;
		uiReportAt = 0;
	}

	/** Zustandsmaschine (ohne Seiteneffekte außer dem Sink). */
	static final class State {
		boolean shown;
		String field;
		int seq;
		int idleTicks;

		void update(String wanted, Sink out) {
			if (wanted != null) {
				idleTicks = 0;
				if (!shown || !wanted.equals(field)) {
					shown = true;
					field = wanted;
					out.changed(true, wanted, ++seq);
				}
				return;
			}
			if (!shown) return;
			if (++idleTicks < HIDE_DELAY_TICKS) return;
			shown = false;
			field = null;
			idleTicks = 0;
			out.changed(false, null, ++seq);
		}

		void reset() {
			shown = false;
			field = null;
			seq = 0;
			idleTicks = 0;
		}
	}

	// --- Ausgabe ---

	private static final Sink DEFAULT_SINK = new Sink() {
		@Override
		public void changed(boolean show, String field, int seq) {
			sendLink(show, field);
			writeFile(show, field, seq);
			System.out.println("[TRS-Touch] " + (show ? "keyboard.show " + field : "keyboard.hide"));
		}
	};

	/** Zeile für den Link (ohne Zeilenumbruch). Feldnamen sind feste Konstanten – kein Escaping nötig. */
	static String linkLine(boolean show, String field) {
		return show ? "{\"type\":\"keyboard.show\",\"field\":\"" + safeField(field) + "\"}" : "{\"type\":\"keyboard.hide\"}";
	}

	/** Inhalt der Rückfall-Datei. */
	static String fileJson(boolean show, String field, int seq) {
		return "{\"version\":1,\"keyboard\":" + show + ",\"field\":" + (show ? "\"" + safeField(field) + "\"" : "null")
				+ ",\"seq\":" + seq + "}";
	}

	private static String safeField(String field) {
		if (FIELD_CHAT.equals(field) || FIELD_SIGN.equals(field) || FIELD_ANVIL.equals(field) || FIELD_BOOK.equals(field)
				|| FIELD_MULTILINE.equals(field)) {
			return field;
		}
		return FIELD_TEXT;
	}

	private static void sendLink(boolean show, String field) {
		TrsLink link = TrsLink.shared();
		if (link == null || !link.status().has(FEATURE)) return;
		link.send(linkLine(show, field));
	}

	private static void writeFile(boolean show, String field, int seq) {
		Path dir = configDir != null ? configDir : dev.theredstonee.trsclient.core.i18n.I18n.configDir();
		if (dir == null) return;
		try {
			Path folder = dir.resolve("trsclient");
			Files.createDirectories(folder);
			Path target = folder.resolve(FILE_NAME);
			Path tmp = folder.resolve(FILE_NAME + ".tmp");
			Files.write(tmp, fileJson(show, field, seq).getBytes(StandardCharsets.UTF_8));
			try {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException e) {
			// Nur ein Hinweis an die Engine – nie das Spiel stören.
		}
	}
}
