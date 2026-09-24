package dev.theredstonee.trsclient.core.perf;

import java.lang.reflect.Method;

/**
 * Läuft gerade ein Shaderpack (Iris/Oculus oder OptiFine)? Ohne harte Abhängigkeit: die öffentliche
 * Iris-API ({@code net.irisshaders.iris.api.v0.IrisApi#isShaderPackInUse}) bzw. OptiFines
 * {@code net.optifine.Config#isShaders} werden per Reflection gefragt – höchstens einmal je Sekunde.
 *
 * <p>Mit Shadern nimmt der TRS Client zurück, was doppelt oder falsch wäre: der Farb-Durchgang („Farben“)
 * pausiert (das Shaderpack macht seine eigene Nachbearbeitung und würde sonst doppelt gefärbt), und das
 * Ausblenden hinter Wänden ruht (der Schatten-Durchgang braucht auch Wesen, die die Kamera nicht sieht –
 * sonst fehlen ihre Schatten).
 */
public final class ShaderPacks {
	private static final long CHECK_MS = 1000;
	private static volatile boolean active;
	private static volatile long checkedAt = Long.MIN_VALUE / 2;
	private static boolean resolved;
	private static Object iris;
	private static Method irisInUse;
	private static Method optifineShaders;
	/** Nur für den Autotest: Shaderpack vortäuschen. */
	private static volatile boolean forced;

	private ShaderPacks() {
	}

	/** Ist ein Shaderpack aktiv? (billig: Ergebnis gilt eine Sekunde) */
	public static boolean active() {
		if (forced) return true;
		long now = System.currentTimeMillis();
		if (now - checkedAt < CHECK_MS && now >= checkedAt) return active;
		checkedAt = now;
		active = query();
		return active;
	}

	/** Nur für den Autotest. */
	public static void force(boolean on) {
		forced = on;
		checkedAt = Long.MIN_VALUE / 2;
	}

	private static synchronized boolean query() {
		try {
			if (!resolved) {
				resolved = true;
				resolve();
			}
			if (irisInUse != null && Boolean.TRUE.equals(irisInUse.invoke(iris))) return true;
			if (optifineShaders != null && Boolean.TRUE.equals(optifineShaders.invoke(null))) return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			// API anders als erwartet → wie ohne Shader weitermachen.
			irisInUse = null;
			optifineShaders = null;
		}
		return false;
	}

	private static void resolve() {
		ClassLoader loader = ShaderPacks.class.getClassLoader();
		try {
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi", false, loader);
			iris = api.getMethod("getInstance").invoke(null);
			irisInUse = api.getMethod("isShaderPackInUse");
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			iris = null;
			irisInUse = null;
		}
		for (String name : new String[]{"net.optifine.Config", "Config"}) {
			try {
				Class<?> config = Class.forName(name, false, loader);
				Method m = config.getMethod("isShaders");
				if (m.getReturnType() == boolean.class && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
					optifineShaders = m;
					break;
				}
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				// kein OptiFine
			}
		}
	}
}
