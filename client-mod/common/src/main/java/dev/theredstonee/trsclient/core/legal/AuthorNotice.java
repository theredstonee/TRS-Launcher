package dev.theredstonee.trsclient.core.legal;

import dev.theredstonee.trsclient.core.i18n.I18n;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Urheberhinweis des TRS Clients. Additional terms under GPL-3.0 section 7(b) apply: see the file NOTICE.
 *
 * <p>Der Text steht in den Sprachdateien ({@link #KEY}); hier liegen das Original und sein SHA-256. Fehlt oder ändert
 * sich der Text, zeigt das TRS-Menü eine deutliche Warnung und trotzdem das Original. Absichtlich ohne Startsperre,
 * ohne Eingriff ins Spiel und ohne Meldung nach außen. Bei freier Software lässt sich das alles umbauen; die Prüfung
 * macht eine Änderung nur sichtbar, sie verhindert sie nicht.
 *
 * <p>Beta-Sprachen zeigen den englischen Text. Änderungen an Text oder Hashes: siehe AGENTS.md und NOTICE.
 */
public final class AuthorNotice {
	/** Schlüssel des Hinweises in den Sprachdateien. */
	public static final String KEY = "about.notice";

	static final String EN = "Original TRS Client by Ohev Tamerin (theredstonee), TRS Launcher. This notice must remain visible in every copy and every modified version. It must not be removed under any circumstances, except with an additional written, signed confirmation from the author.";
	static final String DE = "Original TRS Client von Ohev Tamerin (theredstonee), TRS Launcher. Dieser Hinweis muss in jeder Kopie und jeder veränderten Fassung sichtbar bleiben. Er darf unter keinen Umständen entfernt werden, außer mit einer zusätzlichen schriftlichen, unterschriebenen Bestätigung des Urhebers.";
	static final String ES = "TRS Client original de Ohev Tamerin (theredstonee), TRS Launcher. Este aviso debe permanecer visible en cada copia y en cada versión modificada. No puede eliminarse bajo ninguna circunstancia, salvo con una confirmación adicional, escrita y firmada por el autor.";

	private static final String SHA_EN = "ea4cd56952cb8eb4c07e32b1b099b45418282de15bb5e97efd1abff01143184a";
	private static final String SHA_DE = "ee863b7cbf5fe7657bc6e35dfc89042d8b364ba6f24ebda4e75cc33421abe23d";
	private static final String SHA_ES = "566f6bc25a3524b89c8e2e0b6e4b1e0ca516325ab08875b9612307242381a61e";

	/** Warnung, falls auch ihre Übersetzung fehlt. */
	private static final String WARNING = "Warning: the author notice of this copy is missing or has been changed, so this copy may not be the original. The original text is shown here. The game keeps running normally and nothing is sent anywhere.";
	private static final String WARNING_SHORT = "Warning: author notice missing or changed. Click for details.";

	private static volatile int checkedGeneration = -1;
	private static volatile boolean checkedResult;

	private AuthorNotice() {
	}

	/** Original für eine Sprache; Beta-Sprachen zeigen Englisch. */
	public static String original(String language) {
		if ("de".equals(language)) return DE;
		if ("es".equals(language)) return ES;
		return EN;
	}

	/** Stimmt {@code text} mit dem hinterlegten Hash dieser Sprache überein? */
	public static boolean matches(String language, String text) {
		if (text == null) return false;
		String expected = "de".equals(language) ? SHA_DE : "es".equals(language) ? SHA_ES : SHA_EN;
		return expected.equals(sha256(text));
	}

	/** Sind Sprachdatei und Original der aktiven Sprache unverändert? Je Sprachwechsel einmal gerechnet. */
	public static boolean intact() {
		int generation = I18n.generation();
		if (generation != checkedGeneration) {
			String language = I18n.code();
			checkedResult = matches(language, I18n.tr(KEY)) && matches(language, original(language));
			checkedGeneration = generation;
		}
		return checkedResult;
	}

	/** Anzuzeigender Text: die Sprachdatei, bei Abweichung das Original aus dem Code. */
	public static String text() {
		return intact() ? I18n.tr(KEY) : original(I18n.code());
	}

	public static String warning() {
		return I18n.trOr("about.warning", WARNING);
	}

	public static String warningShort() {
		return I18n.trOr("about.warningShort", WARNING_SHORT);
	}

	static String sha256(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) hex.append(String.format("%02x", b & 0xFF));
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			// Jede Java-Laufzeit muss SHA-256 mitbringen.
			throw new IllegalStateException(e);
		}
	}
}
