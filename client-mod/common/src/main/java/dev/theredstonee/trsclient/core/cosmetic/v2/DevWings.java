package dev.theredstonee.trsclient.core.cosmetic.v2;

import dev.theredstonee.trsclient.core.online.HatInfo;
import dev.theredstonee.trsclient.core.online.TrsOnline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Selbsttest-Flügel ohne TRS API: {@code -Dtrsclient.devWings=<id>} (oder Pfad zu {@code <id>.json}) lässt den eigenen
 * Spieler ein Flügel-Modell aus {@code -Dtrsclient.devWingsDir} (Standard {@code F:/trs-wt/halloween26-wings}) tragen –
 * {@code <id>.json}, {@code <id>.png}, optional {@code <id>-glow.png}. Nur im Autotest
 * ({@code -Dtrsclient.autotest=true}) oder gegen eine lokale API-Attrappe ({@code http://}) aktiv.
 *
 * <p>Die Dateien werden einmal in den Platten-Cache der v2-Kosmetik gelegt; der normale Lade-Weg
 * ({@code CosmeticV2Cache#load}) findet sie dort und fragt kein Netz.
 */
public final class DevWings {
	/** Fester Inhalts-Schlüssel der Selbsttest-Dateien im Cache. */
	static final String HASH = "de0001";
	private static volatile HatInfo info;
	private static volatile boolean tried;

	private DevWings() {
	}

	/** Selbsttest-Flügel (eigener Spieler) oder null. */
	public static HatInfo info(TrsOnline online) {
		if (tried) return info;
		String raw = System.getProperty("trsclient.devWings");
		if (raw == null || raw.trim().isEmpty() || online == null) {
			tried = true;
			return null;
		}
		boolean dev = Boolean.getBoolean("trsclient.autotest") || online.apiBase().startsWith("http://");
		tried = true;
		if (!dev) return null;
		try {
			info = prepare(raw.trim(), online);
		} catch (IOException | RuntimeException e) {
			info = null;
		}
		return info;
	}

	private static HatInfo prepare(String raw, TrsOnline online) throws IOException {
		Path json;
		String id;
		if (raw.endsWith(".json")) {
			json = Paths.get(raw);
			String n = json.getFileName().toString();
			id = n.substring(0, n.length() - 5);
		} else {
			id = raw;
			json = Paths.get(System.getProperty("trsclient.devWingsDir", "F:/trs-wt/halloween26-wings")).resolve(id + ".json");
		}
		if (!id.matches("[a-z][a-z0-9_]{0,39}")) return null;
		Path dir = json.getParent();
		Path png = dir.resolve(id + ".png");
		Path glow = dir.resolve(id + "-glow.png");
		if (!Files.isRegularFile(json) || !Files.isRegularFile(png)) return null;
		Path cache = online.cosmeticDir();
		Files.createDirectories(cache);
		Files.copy(json, cache.resolve(id + "-" + HASH + ".json"), StandardCopyOption.REPLACE_EXISTING);
		Files.copy(png, cache.resolve(id + "-" + HASH + ".png"), StandardCopyOption.REPLACE_EXISTING);
		boolean hasGlow = Files.isRegularFile(glow);
		if (hasGlow) Files.copy(glow, cache.resolve(id + "-" + HASH + "-glow.png"), StandardCopyOption.REPLACE_EXISTING);
		return HatInfo.v2(id, "/v1/cosmetics/" + id + "/model.json", "/v1/cosmetics/" + id + ".png",
				hasGlow ? "/v1/cosmetics/" + id + "-glow.png" : null, HASH, null, null, online.config());
	}
}
