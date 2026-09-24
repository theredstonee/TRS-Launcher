package dev.theredstonee.trsclient.core.perf;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Läuft das Spiel auf der Onboard-Grafik, obwohl eine dedizierte Grafikkarte eingebaut ist?
 * Der Name der benutzten Karte kommt vom Spiel (GL_RENDERER bzw. Geräte-Name); die eingebauten
 * Grafikkarten liest ein Hintergrund-Thread einmal aus der Windows-Registry (reg.exe, nur lesen).
 * Umstellen kann das der TRS Launcher – die Mod zeigt nur den Hinweis.
 */
public final class GpuInfo {
	public enum Kind {
		UNKNOWN, INTEGRATED, DEDICATED
	}

	private static volatile List<String> adapters;
	private static volatile boolean probing;

	private GpuInfo() {
	}

	/** Ordnet einen Grafikkarten-Namen ein (GL_RENDERER oder Treiberbeschreibung). */
	public static Kind classify(String name) {
		if (name == null) return Kind.UNKNOWN;
		String n = name.toLowerCase(Locale.ROOT);
		if (n.isEmpty()) return Kind.UNKNOWN;
		// Software-/Remote-Renderer zählen nicht.
		if (n.contains("llvmpipe") || n.contains("software") || n.contains("microsoft basic") || n.contains("gdi generic")) {
			return Kind.UNKNOWN;
		}
		if (n.contains("nvidia") || n.contains("geforce") || n.contains("quadro") || n.contains("rtx") || n.contains("gtx")) {
			return Kind.DEDICATED;
		}
		if (n.contains("intel")) {
			// Intel Arc A-/B-Serie sind eigene Karten; „Arc Graphics“ (ohne Nummer) ist Onboard (Core Ultra).
			if (n.contains("arc") && (n.matches(".*arc\\(tm\\) [ab]\\d.*") || n.matches(".*arc [ab]\\d.*"))) return Kind.DEDICATED;
			return Kind.INTEGRATED;
		}
		if (n.contains("amd") || n.contains("radeon") || n.contains("ati ")) {
			// „AMD Radeon(TM) Graphics“, „Radeon Vega 8“, „Radeon 780M“ → Onboard; RX/Pro/R9 → dediziert.
			if (n.contains("radeon(tm) graphics") || n.contains("radeon graphics") || n.contains("vega")
					|| n.matches(".*radeon(\\(tm\\))? \\d{3}m.*")) {
				return n.contains("rx vega") ? Kind.DEDICATED : Kind.INTEGRATED;
			}
			if (n.contains(" rx") || n.contains("radeon pro") || n.contains("r9 ") || n.contains("r7 ") || n.contains("hd ")) {
				return Kind.DEDICATED;
			}
			return Kind.UNKNOWN;
		}
		return Kind.UNKNOWN;
	}

	/** Eingebaute Grafikkarten (leer = unbekannt/noch nicht gelesen). Startet beim ersten Aufruf das Auslesen. */
	public static List<String> adapters() {
		List<String> a = adapters;
		if (a != null) return a;
		startProbe();
		return Collections.emptyList();
	}

	/** Name der ersten dedizierten Karte unter den eingebauten, sonst null. */
	public static String dedicatedAdapter() {
		for (String name : adapters()) {
			if (classify(name) == Kind.DEDICATED) return name;
		}
		return null;
	}

	/** Für Tests: eingebaute Karten vorgeben. */
	public static void setAdapters(List<String> list) {
		adapters = list == null ? null : Collections.unmodifiableList(new ArrayList<String>(list));
	}

	private static synchronized void startProbe() {
		if (probing || adapters != null) return;
		probing = true;
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			adapters = Collections.emptyList();
			return;
		}
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				adapters = readWindowsAdapters();
			}
		}, "TRS GPU probe");
		t.setDaemon(true);
		t.start();
	}

	/** Liest die Treiberbeschreibungen der Grafik-Geräteklasse aus der Registry. */
	static List<String> readWindowsAdapters() {
		List<String> out = new ArrayList<String>();
		Process p = null;
		try {
			p = new ProcessBuilder("reg", "query",
					"HKLM\\SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}",
					"/s", "/v", "DriverDesc").redirectErrorStream(true).start();
			BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.defaultCharset()));
			String line;
			int lines = 0;
			while ((line = in.readLine()) != null && lines++ < 400) {
				String name = parseRegLine(line);
				if (name != null && !out.contains(name)) out.add(name);
			}
			p.waitFor(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			return Collections.emptyList();
		} finally {
			if (p != null) p.destroy();
		}
		return Collections.unmodifiableList(out);
	}

	/** "    DriverDesc    REG_SZ    NVIDIA GeForce RTX 3060" → Name, sonst null. */
	static String parseRegLine(String line) {
		if (line == null) return null;
		int at = line.indexOf("REG_SZ");
		if (at < 0 || !line.trim().startsWith("DriverDesc")) return null;
		String name = line.substring(at + "REG_SZ".length()).trim();
		return name.isEmpty() ? null : name;
	}
}
