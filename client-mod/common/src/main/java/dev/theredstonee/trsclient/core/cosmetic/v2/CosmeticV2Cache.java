package dev.theredstonee.trsclient.core.cosmetic.v2;

import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.HatInfo;
import dev.theredstonee.trsclient.core.online.TrsApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * v2-Kosmetik auf der Platte ({@code config/trsclient/cosmetics/<id>-<hash>.json|.png|-glow.png}): Die Adressen
 * tragen {@code ?v=<hash>} und ändern sich mit dem Inhalt – gleiche Kennung = gleicher Inhalt, also ohne Netz aus dem
 * Cache. Ältere Stände desselben Teils werden beim Speichern gelöscht. Alles wird nach dem Lesen (auch von der
 * Platte) streng geprüft und dekodiert; nur Hintergrund-Threads.
 */
public final class CosmeticV2Cache {
	/** Höchstens so groß (Modell / je Bild). */
	static final int MAX_MODEL_BYTES = CosmeticV2.MAX_JSON_BYTES;
	static final int MAX_PNG_BYTES = TrsApi.MAX_TEXTURE_BYTES;

	/** Fertig geladen und zerlegt: Modell, Bilder der Grundtextur und der Leucht-Schicht (vormultipliziert). */
	public static final class Loaded {
		public final CosmeticV2 model;
		public final int[][] base;
		/** null = keine Leucht-Schicht. */
		public final int[][] glow;
		/** Entfernungs-Stufen je Bild: {@code baseLevels[bild][stufe]}, Stufe 0 = {@link #base} (siehe {@link V2Images#levels}). */
		public final int[][][] baseLevels;
		/** Wie {@link #baseLevels} für die Leucht-Schicht; null = keine. */
		public final int[][][] glowLevels;

		public Loaded(CosmeticV2 model, int[][] base, int[][] glow) {
			this.model = model;
			this.base = base;
			this.glow = glow;
			int levels = CosmeticV2Renderer.maxLod(model.scale);
			this.baseLevels = new int[base.length][][];
			for (int f = 0; f < base.length; f++) {
				baseLevels[f] = V2Images.levels(base[f], model.pixelWidth(), model.pixelHeight(), levels, false);
			}
			if (glow == null) {
				this.glowLevels = null;
			} else {
				this.glowLevels = new int[glow.length][][];
				for (int f = 0; f < glow.length; f++) {
					glowLevels[f] = V2Images.levels(glow[f], model.pixelWidth(), model.pixelHeight(), levels, true);
				}
			}
		}

		/** Anzahl der Stufen über der vollen Auflösung. */
		public int levels() {
			return baseLevels.length == 0 ? 0 : baseLevels[0].length - 1;
		}
	}

	private final Path dir;

	public CosmeticV2Cache(Path dir) {
		this.dir = dir;
	}

	/** Lädt Modell + Bilder (Platte oder Netz), prüft und zerlegt sie. Blockierend. */
	public Loaded load(HatInfo hat, TrsApi api, String token) throws IOException, ApiException {
		if (hat == null || !hat.v2()) throw new IOException("kein v2-Teil");
		String base = safe(hat.id) + "-" + safe(hat.hash);
		Path modelFile = dir.resolve(base + ".json");
		Path texFile = dir.resolve(base + ".png");
		Path glowFile = dir.resolve(base + "-glow.png");
		byte[] json = read(modelFile, MAX_MODEL_BYTES);
		byte[] tex = read(texFile, MAX_PNG_BYTES);
		byte[] glow = hat.glowUrl == null ? null : read(glowFile, MAX_PNG_BYTES);
		if (json != null && tex != null && (hat.glowUrl == null || glow != null)) {
			try {
				return decode(new String(json, StandardCharsets.UTF_8), tex, glow);
			} catch (IOException | RuntimeException e) {
				// kaputte Cache-Datei: neu laden
			}
		}
		json = api.asset(hat.modelUrl, "application/json", MAX_MODEL_BYTES, token);
		tex = api.asset(hat.textureUrl, "image/png", MAX_PNG_BYTES, token);
		glow = hat.glowUrl == null ? null : api.asset(hat.glowUrl, "image/png", MAX_PNG_BYTES, token);
		Loaded loaded = decode(new String(json, StandardCharsets.UTF_8), tex, glow);
		try {
			Files.createDirectories(dir);
			removeOld(safe(hat.id) + "-", base);
			write(modelFile, json);
			write(texFile, tex);
			if (glow != null) write(glowFile, glow);
		} catch (IOException ignored) {
			// Cache ist nur eine Beschleunigung
		}
		return loaded;
	}

	/** Modell prüfen (inklusive Bildmaße) und Streifen zerlegen. */
	public static Loaded decode(String json, byte[] texPng, byte[] glowPng) throws IOException {
		PngDecoder.Image tex = PngDecoder.decode(texPng);
		PngDecoder.Image glow = glowPng == null ? null : PngDecoder.decode(glowPng);
		CosmeticV2.Check check = CosmeticV2.check(json, tex.width, tex.height, glow == null ? -1 : glow.width,
				glow == null ? -1 : glow.height);
		if (!check.ok()) throw new IOException(check.errors.isEmpty() ? "ungültig" : check.errors.get(0));
		CosmeticV2 m = check.model;
		int[][] base = V2Images.split(tex.argb, tex.width, tex.height, m.pixelWidth(), m.pixelHeight(), m.frames);
		int[][] glowFrames = null;
		if (m.glow && glow != null) {
			glowFrames = V2Images.split(glow.argb, glow.width, glow.height, m.pixelWidth(), m.pixelHeight(), m.glowFrames);
			for (int[] f : glowFrames) V2Images.premultiplyGlow(f);
		}
		return new Loaded(m, base, glowFrames);
	}

	private void removeOld(String prefix, String keep) {
		if (!Files.isDirectory(dir)) return;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
			for (Path p : ds) {
				String n = p.getFileName().toString();
				if (n.startsWith(prefix) && !n.startsWith(keep + ".") && !n.startsWith(keep + "-glow.")) Files.deleteIfExists(p);
			}
		} catch (IOException ignored) {
			// egal
		}
	}

	private static String safe(String s) {
		String t = s == null ? "" : s.replaceAll("[^a-z0-9_]", "");
		return t.isEmpty() ? "x" : t;
	}

	private static byte[] read(Path file, int max) {
		try {
			if (!Files.isRegularFile(file) || Files.size(file) > max) return null;
			return Files.readAllBytes(file);
		} catch (IOException e) {
			return null;
		}
	}

	private static void write(Path file, byte[] data) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(tmp, data);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}
}
