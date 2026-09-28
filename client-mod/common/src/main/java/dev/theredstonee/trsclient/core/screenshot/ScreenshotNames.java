package dev.theredstonee.trsclient.core.screenshot;

import dev.theredstonee.trsclient.core.clips.ScreenshotShare;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Dateinamen der Bildschirmfotos: erkennt Vanilla-Namen ({@code 2026-09-28_14.03.22.png}, bei gleicher Sekunde
 * {@code …_2.png}) und baut den Namen der bearbeiteten Kopie („Kopie von &lt;name&gt;.png“) – das Original wird nie
 * überschrieben.
 */
public final class ScreenshotNames {
	/** Name, den Minecraft (und Mods, die den Vanilla-Weg nutzen, z. B. Essential) für Bildschirmfotos vergibt. */
	private static final Pattern VANILLA = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2}(_\\d{1,4})?\\.png");
	private static final Pattern FIND = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2}(_\\d{1,4})?\\.png");
	/** Harmloser Präfix für die Kopie (ASCII-Buchstaben, Ziffern, Leerzeichen, Bindestrich) – sonst „Copy of“. */
	private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9 _-]{1,24}");
	public static final String DEFAULT_PREFIX = "Copy of";
	static final int MAX_COPIES = 999;

	private ScreenshotNames() {
	}

	/** Vanilla-Name irgendwo in einem Text (z. B. Chatzeile) oder null. */
	public static String find(String text) {
		if (text == null) return null;
		java.util.regex.Matcher m = FIND.matcher(text);
		return m.find() ? m.group() : null;
	}

	/** Sieht aus wie ein frisch von Minecraft gespeichertes Bildschirmfoto? */
	public static boolean vanilla(String name) {
		return name != null && VANILLA.matcher(name).matches();
	}

	/** Präfix aus der Übersetzung, falls er als Dateiname taugt – sonst „Copy of“. */
	static String prefix(String localized) {
		String p = localized == null ? "" : localized.trim();
		return PREFIX.matcher(p).matches() ? p : DEFAULT_PREFIX;
	}

	/**
	 * Freier Name für die bearbeitete Kopie neben dem Original: {@code "<präfix> <stamm>.png"}, sonst
	 * {@code "<präfix> <stamm> (2).png"} … Liefert null, wenn kein Name frei ist oder der Name unsicher wäre.
	 */
	public static Path copyFor(Path original, String localizedPrefix) {
		if (original == null || original.getParent() == null || original.getFileName() == null) return null;
		String name = original.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String stem = dot > 0 ? name.substring(0, dot) : name;
		String prefix = prefix(localizedPrefix);
		for (int i = 1; i <= MAX_COPIES; i++) {
			String candidate = prefix + " " + stem + (i == 1 ? "" : " (" + i + ")") + ".png";
			if (!ScreenshotShare.valid(candidate)) {
				if (!prefix.equals(DEFAULT_PREFIX)) return copyFor(original, DEFAULT_PREFIX);
				return null;
			}
			Path p = original.resolveSibling(candidate);
			if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return p;
		}
		return null;
	}
}
