package dev.theredstonee.trsclient.core.hosting.share;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Was der Host je Welt teilen will – gemerkt in {@code config/trsclient/hosting-share.json} (höchstens 100 Welten,
 * zuletzt benutzte zuerst). Ab Werk ist alles AUS: „Mods teilen“, „Mods direkt vom Host übertragen“ und „Resource Pack
 * teilen“. Je Mod (SHA-1) wird die Wahl gemerkt (an/aus, Pflicht/optional).
 */
public final class ShareSettings {
	static final int MAX_WORLDS = 100;
	static final int MAX_CHOICES = 400;

	/** Wahl für eine Mod. */
	public static final class Choice {
		public final boolean on;
		public final boolean required;

		public Choice(boolean on, boolean required) {
			this.on = on;
			this.required = required;
		}
	}

	/** Einstellungen einer Welt. */
	public static final class World {
		public boolean shareMods;
		/** Mods ohne Store-Treffer direkt vom Host übertragen (sonst nur Hinweis „selbst besorgen“). */
		public boolean direct;
		public boolean sharePack;
		/** Dateiname des Packs im resourcepacks-Ordner oder null. */
		public String packFile;
		public final Map<String, Choice> choices = new LinkedHashMap<String, Choice>();

		public World copy() {
			World w = new World();
			w.shareMods = shareMods;
			w.direct = direct;
			w.sharePack = sharePack;
			w.packFile = packFile;
			w.choices.putAll(choices);
			return w;
		}
	}

	private final Path file;
	private final LinkedHashMap<String, World> worlds = new LinkedHashMap<String, World>();
	private boolean loaded;

	public ShareSettings(Path file) {
		this.file = file;
	}

	/** Einstellungen der Welt (neu = alles aus). Kopie – ändern und mit {@link #put} speichern. */
	public synchronized World get(String worldKey) {
		load();
		World w = worldKey == null ? null : worlds.get(worldKey);
		return w == null ? new World() : w.copy();
	}

	public synchronized void put(String worldKey, World w) {
		if (worldKey == null || worldKey.isEmpty() || worldKey.length() > 512) return;
		load();
		worlds.remove(worldKey);
		World copy = w.copy();
		while (copy.choices.size() > MAX_CHOICES) copy.choices.remove(copy.choices.keySet().iterator().next());
		worlds.put(worldKey, copy);
		while (worlds.size() > MAX_WORLDS) worlds.remove(worlds.keySet().iterator().next());
		save();
	}

	@SuppressWarnings("deprecation")
	private void load() {
		if (loaded) return;
		loaded = true;
		if (file == null || !Files.isRegularFile(file)) return;
		try {
			if (Files.size(file) > 4L * 1024 * 1024) return;
			JsonElement root = new JsonParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
			if (!root.isJsonObject() || !root.getAsJsonObject().has("worlds")) return;
			JsonElement ws = root.getAsJsonObject().get("worlds");
			if (!ws.isJsonObject()) return;
			for (Map.Entry<String, JsonElement> e : ws.getAsJsonObject().entrySet()) {
				if (!e.getValue().isJsonObject() || worlds.size() >= MAX_WORLDS) continue;
				JsonObject o = e.getValue().getAsJsonObject();
				World w = new World();
				w.shareMods = bool(o, "mods");
				w.direct = bool(o, "direct");
				w.sharePack = bool(o, "pack");
				JsonElement pf = o.get("packFile");
				w.packFile = pf != null && pf.isJsonPrimitive() && validPackFile(pf.getAsString()) ? pf.getAsString() : null;
				JsonElement ch = o.get("choices");
				if (ch != null && ch.isJsonObject()) {
					for (Map.Entry<String, JsonElement> c : ch.getAsJsonObject().entrySet()) {
						if (!SharedContent.SHA1.matcher(c.getKey()).matches() || !c.getValue().isJsonObject()) continue;
						JsonObject co = c.getValue().getAsJsonObject();
						w.choices.put(c.getKey(), new Choice(bool(co, "on"), bool(co, "required")));
						if (w.choices.size() >= MAX_CHOICES) break;
					}
				}
				worlds.put(e.getKey(), w);
			}
		} catch (IOException | RuntimeException e) {
			// kaputt → neu anfangen
		}
	}

	private void save() {
		if (file == null) return;
		JsonObject ws = new JsonObject();
		for (Map.Entry<String, World> e : worlds.entrySet()) {
			World w = e.getValue();
			JsonObject o = new JsonObject();
			o.addProperty("mods", w.shareMods);
			o.addProperty("direct", w.direct);
			o.addProperty("pack", w.sharePack);
			if (w.packFile != null) o.addProperty("packFile", w.packFile);
			JsonObject ch = new JsonObject();
			for (Map.Entry<String, Choice> c : w.choices.entrySet()) {
				JsonObject co = new JsonObject();
				co.addProperty("on", c.getValue().on);
				co.addProperty("required", c.getValue().required);
				ch.add(c.getKey(), co);
			}
			o.add("choices", ch);
			ws.add(e.getKey(), o);
		}
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.add("worlds", ws);
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.write(tmp, root.toString().getBytes(StandardCharsets.UTF_8));
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			// nicht schlimm – gilt dann nur bis zum Neustart
		}
	}

	/** Reiner Dateiname eines Packs (.zip, kein Pfad). */
	public static boolean validPackFile(String f) {
		return f != null && f.length() >= 5 && f.length() <= 128 && f.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
				&& !f.contains("/") && !f.contains("\\") && !f.contains("..") && !f.startsWith(".") && SharedContent.clean(f);
	}

	private static boolean bool(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
	}

	/** Für Tests. */
	synchronized List<String> keys() {
		load();
		return new ArrayList<String>(worlds.keySet());
	}
}
