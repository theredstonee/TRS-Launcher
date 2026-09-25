package dev.theredstonee.trsclient.core.wardrobe;

import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.skin.SkinImage;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Prüfen, Lesen und Kennungen von Skin-Dateien – dieselben Regeln wie Launcher und TRS API (API.md §17.2):
 * PNG 64×64 oder 64×32, höchstens 128 KiB, Kennung = 12 kleine Hex-Ziffern, Name 1–48 Zeichen ohne Steuerzeichen.
 */
public final class SkinFiles {
	/** Größte Skin-Datei (wie die API). */
	public static final int MAX_BYTES = 128 * 1024;
	public static final int MAX_NAME = 48;
	private static final SecureRandom RANDOM = new SecureRandom();

	private SkinFiles() {
	}

	/** Ergebnis von {@link #decode}: Pixel im 64×64-Format + vermutete Armform. */
	public static final class Decoded {
		public final int[] pixels;
		public final boolean slim;
		/** War das Bild im alten 64×32-Format? */
		public final boolean legacy;

		Decoded(int[] pixels, boolean slim, boolean legacy) {
			this.pixels = pixels;
			this.slim = slim;
			this.legacy = legacy;
		}
	}

	/** Fehler beim Lesen mit stabilem Code (für die Oberfläche: {@code wardrobe.error.<code>}). */
	public static final class SkinException extends Exception {
		public final String code;

		public SkinException(String code) {
			super(code);
			this.code = code;
		}
	}

	/** Hat die Datei die PNG-Signatur? */
	public static boolean isPng(byte[] data) {
		return data != null && data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N'
				&& data[3] == 'G' && data[4] == '\r' && data[5] == '\n' && data[6] == 0x1A && data[7] == '\n';
	}

	/**
	 * Liest und prüft eine Skin-Datei.
	 *
	 * @throws SkinException {@code too_large}, {@code not_png}, {@code invalid_png}, {@code invalid_size}
	 */
	public static Decoded decode(byte[] png) throws SkinException {
		if (png == null || png.length == 0) throw new SkinException("invalid_png");
		if (png.length > MAX_BYTES) throw new SkinException("too_large");
		if (!isPng(png)) throw new SkinException("not_png");
		PngDecoder.Image img;
		try {
			img = PngDecoder.decode(png);
		} catch (IOException | RuntimeException e) {
			throw new SkinException("invalid_png");
		}
		if (!SkinImage.validSize(img.width, img.height)) throw new SkinException("invalid_size");
		boolean legacy = img.height == 32;
		int[] px;
		if (legacy) {
			px = SkinImage.normalize(img.width, img.height, img.argb);
		} else {
			px = new int[64 * 64];
			System.arraycopy(img.argb, 0, px, 0, px.length);
		}
		return new Decoded(px, !legacy && looksSlim(px), legacy);
	}

	/**
	 * Schmale Arme? Bei Slim-Skins ist die vierte Spalte der Arme (rechter Arm: x 54–55, y 20–31 und x 50–51,
	 * y 16–19) leer – Vanilla-Heuristik wie im Launcher.
	 */
	public static boolean looksSlim(int[] px) {
		int transparent = 0;
		int total = 0;
		for (int y = 20; y < 32; y++) {
			for (int x = 54; x < 56; x++) {
				total++;
				if ((px[y * 64 + x] >>> 24) == 0) transparent++;
			}
		}
		for (int y = 16; y < 20; y++) {
			for (int x = 50; x < 52; x++) {
				total++;
				if ((px[y * 64 + x] >>> 24) == 0) transparent++;
			}
		}
		return transparent == total;
	}

	/** Neue Bibliotheks-Kennung (12 Hex-Ziffern). */
	public static String newId() {
		byte[] b = new byte[6];
		RANDOM.nextBytes(b);
		StringBuilder s = new StringBuilder(12);
		for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
		return s.toString();
	}

	public static boolean validId(String id) {
		return id != null && id.matches("[0-9a-f]{12}");
	}

	/**
	 * Säubert einen Namen: Steuer-/Formatzeichen raus, getrimmt, höchstens {@link #MAX_NAME} Zeichen.
	 *
	 * @return der Name oder {@code fallback}, wenn nichts übrig bleibt
	 */
	public static String cleanName(String name, String fallback) {
		if (name == null) return fallback;
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isISOControl(c) || c == 0x2028 || c == 0x2029 || c == 0xA7) continue;
			if (c >= 0x202A && c <= 0x202E) continue;
			if (c >= 0x2066 && c <= 0x2069) continue;
			if (c == 0x200E || c == 0x200F || c == 0x061C) continue;
			b.append(c);
		}
		String s = b.toString().trim();
		if (s.isEmpty()) return fallback;
		if (s.length() > MAX_NAME) {
			int end = MAX_NAME;
			if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
			s = s.substring(0, end).trim();
		}
		return s;
	}

	/** Dateiname ohne Endung als Skin-Name ("mein_skin.png" → "mein_skin"). */
	public static String nameFromFile(String fileName, String fallback) {
		if (fileName == null) return fallback;
		String n = fileName.replace('\\', '/');
		n = n.substring(n.lastIndexOf('/') + 1);
		int dot = n.lastIndexOf('.');
		if (dot > 0) n = n.substring(0, dot);
		return cleanName(n, fallback);
	}

	/** SHA-256 als Hex (klein). */
	public static String sha256(byte[] data) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
			StringBuilder s = new StringBuilder(64);
			for (byte x : d) s.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
			return s.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
