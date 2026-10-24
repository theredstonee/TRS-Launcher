package dev.theredstonee.trsclient.core.cosmetic.pet;

import dev.theredstonee.trsclient.core.online.HatInfo;
import dev.theredstonee.trsclient.core.online.TrsOnline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Selbsttest-Begleiter ohne TRS API (wie {@code DevWings}): {@code -Dtrsclient.devPet=<pfad zu <id>.json>} lässt dem
 * eigenen Spieler einen Begleiter am Boden folgen ({@code <id>.png} daneben), Anzahl {@code -Dtrsclient.devPetCount}
 * (1–4). Nur im Autotest ({@code -Dtrsclient.autotest=true}) oder gegen eine lokale API-Attrappe ({@code http://}).
 * Die Dateien werden einmal in den Platten-Cache der v2-Kosmetik gelegt; danach läuft der normale Lade-Weg.
 */
public final class DevPets {
	static final String HASH = "de0002";
	private static volatile HatInfo info;
	private static volatile boolean tried;
	/** Nur Selbsttests: Anzahl der eigenen Begleiter erzwingen (0 = wie vom Server). */
	public static volatile int forcedCount;

	private DevPets() {
	}

	/** Selbsttest-Begleiter (eigener Spieler) oder null. */
	public static HatInfo info(TrsOnline online) {
		if (tried) return info;
		String raw = System.getProperty("trsclient.devPet");
		tried = true;
		if (raw == null || raw.trim().isEmpty() || online == null) return null;
		boolean dev = Boolean.getBoolean("trsclient.autotest") || online.apiBase().startsWith("http://");
		if (!dev) return null;
		try {
			info = prepare(Paths.get(raw.trim()), online);
		} catch (IOException | RuntimeException e) {
			info = null;
		}
		return info;
	}

	private static HatInfo prepare(Path json, TrsOnline online) throws IOException {
		String n = json.getFileName().toString();
		if (!n.endsWith(".json")) return null;
		String id = n.substring(0, n.length() - 5);
		if (!id.matches("[a-z][a-z0-9_]{0,39}")) return null;
		Path png = json.resolveSibling(id + ".png");
		Path glow = json.resolveSibling(id + "-glow.png");
		if (!Files.isRegularFile(json) || !Files.isRegularFile(png)) return null;
		Path cache = online.cosmeticDir();
		Files.createDirectories(cache);
		Files.copy(json, cache.resolve(id + "-" + HASH + ".json"), StandardCopyOption.REPLACE_EXISTING);
		Files.copy(png, cache.resolve(id + "-" + HASH + ".png"), StandardCopyOption.REPLACE_EXISTING);
		boolean hasGlow = Files.isRegularFile(glow);
		if (hasGlow) Files.copy(glow, cache.resolve(id + "-" + HASH + "-glow.png"), StandardCopyOption.REPLACE_EXISTING);
		HatInfo h = HatInfo.v2(id, "/v1/cosmetics/" + id + "/model.json", "/v1/cosmetics/" + id + ".png",
				hasGlow ? "/v1/cosmetics/" + id + "-glow.png" : null, HASH, null, null, online.config());
		return h == null ? null : h.with(null, Integer.getInteger("trsclient.devPetCount", 1));
	}
}
