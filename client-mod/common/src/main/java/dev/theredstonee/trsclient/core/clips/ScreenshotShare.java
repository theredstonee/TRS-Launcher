package dev.theredstonee.trsclient.core.clips;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.social.SharedImage;
import dev.theredstonee.trsclient.core.social.Social;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * „Als Link teilen“ für Bildschirmfotos (API.md §23): Datei sicher auflösen (nur unter {@code screenshots/}, keine
 * Pfad-Tricks, keine Verknüpfungen), hochladen über {@link Social#shareImage}, Texte für Hinweise. Der Marker
 * {@code trs-share:<datei>} hängt an der Vanilla-Chatzeile „Bildschirmfoto gespeichert …“.
 */
public final class ScreenshotShare {
	public static final String MARKER = "trs-share:";
	/** Bloßer Dateiname oder {@code panorama/<name>} – nur harmlose Zeichen. */
	private static final Pattern NAME = Pattern.compile("(panorama/)?[A-Za-z0-9 _.()\\[\\]-]{1,120}\\.(png|jpg|jpeg)");
	/**
	 * Geheimnis dieser Sitzung im Marker: Nur Zeilen, die der Client selbst ergänzt hat, lösen einen Upload aus.
	 * Ein Server kann Chat-Text mit beliebigem Einfüge-Text schicken – ohne dieses Geheimnis würde ein Klick darauf
	 * sonst einen lokalen Screenshot hochladen.
	 */
	private static final String SESSION = newSession();

	private ScreenshotShare() {
	}

	private static String newSession() {
		byte[] b = new byte[8];
		new java.security.SecureRandom().nextBytes(b);
		StringBuilder s = new StringBuilder();
		for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
		return s.toString();
	}

	/** Marker für den Chat-Stil. */
	public static String marker(String relativeName) {
		return MARKER + SESSION + ":" + relativeName;
	}

	/** Dateiname aus dem Marker oder null (fremde Einfüge-Texte, fremdes/fehlendes Sitzungsgeheimnis, verdächtige Namen). */
	public static String parseMarker(String insertion) {
		String prefix = MARKER + SESSION + ":";
		if (insertion == null || !insertion.startsWith(prefix)) return null;
		String name = insertion.substring(prefix.length());
		return valid(name) ? name : null;
	}

	/** Harmloser relativer Name (keine Pfadtrenner außer {@code panorama/}, kein {@code ..}). */
	public static boolean valid(String name) {
		return name != null && !name.contains("..") && NAME.matcher(name).matches();
	}

	/**
	 * Datei unter {@code <spiel>/screenshots} oder null: Name geprüft, normalisiert, muss im Ordner bleiben, reguläre
	 * Datei ohne Verknüpfung (auch der Unterordner nicht).
	 */
	public static Path resolve(Path gameDir, String relativeName) {
		if (gameDir == null || !valid(relativeName)) return null;
		try {
			Path root = gameDir.resolve("screenshots").toAbsolutePath().normalize();
			Path file = root.resolve(relativeName).normalize();
			if (!file.startsWith(root) || file.equals(root)) return null;
			for (Path p = file; p != null && !p.equals(root); p = p.getParent()) {
				if (Files.isSymbolicLink(p)) return null;
			}
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
			return file;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Relativer Name einer Datei unter {@code screenshots/} (für den Marker) oder null. */
	public static String relative(Path gameDir, Path file) {
		if (gameDir == null || file == null) return null;
		Path root = gameDir.resolve("screenshots").toAbsolutePath().normalize();
		Path f = file.toAbsolutePath().normalize();
		if (!f.startsWith(root)) return null;
		String rel = root.relativize(f).toString().replace('\\', '/');
		return valid(rel) ? rel : null;
	}

	/** Laufende Sozial-Verbindung (angemeldet) oder null. */
	public static Social social() {
		TrsOnline online = TrsOnline.current();
		Social s = online == null ? null : online.social();
		return s != null && s.signedIn() ? s : null;
	}

	/** Hochladen; {@code done} im Spiel-Thread. false = nicht angemeldet (Hinweis {@code clips.share.offline}). */
	public static boolean share(Path file, Social.Done<SharedImage> done) {
		Social s = social();
		if (s == null || file == null) return false;
		try {
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) <= 0) {
				done.done(null, "social.error.image_unreadable");
				return true;
			}
		} catch (IOException e) {
			done.done(null, "social.error.image_unreadable");
			return true;
		}
		s.shareImage(file, done);
		return true;
	}

	// --- „An Freunde senden“: Bild vormerken, der Sozial-Bildschirm zeigt die Zielauswahl ---

	private static volatile Path pendingImage;

	/** Bild für die Zielauswahl im Sozial-Bildschirm vormerken. */
	public static void requestSend(Path file) {
		pendingImage = file;
	}

	/** Vorgemerktes Bild abholen (Sozial-Bildschirm) – danach leer. */
	public static Path takePendingImages() {
		Path p = pendingImage;
		pendingImage = null;
		return p;
	}

	/** „Link kopiert – gültig bis …“ bzw. „Link: …“, wenn die Zwischenablage nicht ging. */
	public static String copiedText(SharedImage s, boolean copied) {
		String until = date(s.expiresAt > 0 ? s.expiresAt : System.currentTimeMillis() + SharedImage.LIFETIME_MS);
		return copied ? I18n.tr("clips.share.copied", until) : I18n.tr("clips.share.link", s.url, until);
	}

	/** Kurzes Datum in der Spielsprache. */
	public static String date(long ms) {
		Locale locale = I18n.locale();
		return DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(new Date(ms));
	}
}
