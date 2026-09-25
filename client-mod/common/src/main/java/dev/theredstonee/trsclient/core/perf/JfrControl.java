package dev.theredstonee.trsclient.core.perf;

import java.lang.reflect.Method;

/**
 * Java Flight Recorder für den Benchmark – nur, wenn {@code -Dtrsclient.bench.jfr=<datei>} gesetzt ist, und nur
 * während der Messung. Per Reflection, weil common Java 8 ist und {@code jdk.jfr} nicht überall existiert
 * (fehlt es, passiert einfach nichts).
 */
public final class JfrControl {
	private static Object recording;

	private JfrControl() {
	}

	/** Aufnahme starten (Einstellung „profile“: Methoden-Stichproben alle 10–20 ms, Allokationen, GC). */
	public static synchronized String start() {
		String file = System.getProperty("trsclient.bench.jfr", "");
		if (file.isEmpty() || recording != null) return null;
		try {
			Class<?> config = Class.forName("jdk.jfr.Configuration");
			Object profile = config.getMethod("getConfiguration", String.class).invoke(null, "profile");
			Class<?> rec = Class.forName("jdk.jfr.Recording");
			Object r = rec.getConstructor(config).newInstance(profile);
			java.nio.file.Path path = java.nio.file.Paths.get(file).toAbsolutePath();
			if (path.getParent() != null) java.nio.file.Files.createDirectories(path.getParent());
			Method dest = rec.getMethod("setDestination", java.nio.file.Path.class);
			dest.invoke(r, path);
			rec.getMethod("start").invoke(r);
			recording = r;
			return file;
		} catch (java.lang.reflect.InvocationTargetException e) {
			return "nicht verfügbar: " + e.getCause();
		} catch (ReflectiveOperationException | java.io.IOException | RuntimeException | LinkageError e) {
			return "nicht verfügbar: " + e;
		}
	}

	/** Aufnahme beenden und schreiben. */
	public static synchronized void stop() {
		if (recording == null) return;
		try {
			recording.getClass().getMethod("stop").invoke(recording);
			recording.getClass().getMethod("close").invoke(recording);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			// nichts zu retten
		}
		recording = null;
	}
}
