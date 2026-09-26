package dev.theredstonee.trsclient.core.cosmetic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Die Kopf-Vorlagen, die diese Mod zeichnet – mitgeliefert unter {@code assets/trsclient/cosmetics/<id>.json}
 * (Kopien aus der API; Vorlagen ändern sich nie). Bewusst eine feste Liste: andere Kopf-Kosmetik der API bleibt
 * unsichtbar, bis sie hier freigegeben wird.
 */
public final class CosmeticModels {
	private static final String[] SUPPORTED = { "duck" };
	private static final Map<String, CosmeticModel> CACHE = new HashMap<>();

	private CosmeticModels() {
	}

	public static boolean supported(String template) {
		for (String s : SUPPORTED) if (s.equals(template)) return true;
		return false;
	}

	/** Vorlage laden (einmal, dann zwischengespeichert); unbekannt oder kaputt → null. */
	public static synchronized CosmeticModel get(String template) {
		if (!supported(template)) return null;
		if (CACHE.containsKey(template)) return CACHE.get(template);
		CosmeticModel model = null;
		try (InputStream in = CosmeticModels.class.getResourceAsStream("/assets/trsclient/cosmetics/" + template + ".json")) {
			if (in != null) model = CosmeticModel.parse(read(in));
		} catch (IOException | RuntimeException e) {
			model = null;
		}
		CACHE.put(template, model);
		return model;
	}

	private static String read(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		int n;
		while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}
}
