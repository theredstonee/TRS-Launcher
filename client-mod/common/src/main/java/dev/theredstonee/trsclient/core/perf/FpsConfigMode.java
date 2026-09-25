package dev.theredstonee.trsclient.core.perf;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Grafik-Modus der Leistungs-Einstellungen: {@link Mode#PRETTY „Schön“} (nur Leistungs-Schalter ohne Optik-Verlust –
 * die Optimierungs-Mods stehen ab Werk schon so) oder {@link Mode#MAX „Max FPS“} (Wolken aus, weniger Partikel, weiche
 * Beleuchtung aus, Grafik „Schnell“, schnelles Laub/Wetter in Sodium). Der Spieler wählt beim ersten Start und kann
 * jederzeit umschalten (TRS-Menü „Leistung“ oder im TRS Launcher).
 *
 * <p>Gespeichert in {@code config/trsclient/fps-mode.json} – der Launcher schreibt dort nur {@code mode}/{@code chosen},
 * alles andere gehört dem Mod. Geändert wird nur beim Wechsel des Modus und nur, was der Spieler nicht selbst
 * verstellt hat: jeder gesetzte Wert wird mit altem und neuem Wert gemerkt; steht beim Zurückschalten noch unser Wert
 * da, kommt der alte zurück, sonst bleibt der des Spielers.
 *
 * <p>API für andere Teile (z. B. die Einführung beim ersten Start):
 * {@code Performance#fpsMode()}, {@code Performance#chooseFpsMode(Mode, long)} und {@link #chosen()}.
 */
public final class FpsConfigMode {
	public enum Mode {
		/** Schön: Leistungs-Schalter ohne sichtbaren Unterschied (Standard). */
		PRETTY,
		/** Max FPS: sichtbar sparsamer. */
		MAX;

		public String key() {
			return name().toLowerCase(Locale.ROOT);
		}

		static Mode of(String s) {
			return "max".equalsIgnoreCase(s) ? MAX : PRETTY;
		}
	}

	static final String FILE = "trsclient/fps-mode.json";
	/** Sodium-Einstellungen (Schlüssel werden nur geändert, wenn es sie in der Datei der installierten Version gibt). */
	static final String SODIUM = "sodium-options.json";
	/** Max FPS in Sodium: schnelles Laub und Wetter (Schlüssel bis Sodium 0.8.13). */
	static final String[][] SODIUM_MAX = {{"quality", "leaves_quality", "FAST"}, {"quality", "weather_quality", "FAST"}};

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path configDir;
	private Mode mode = Mode.PRETTY;
	private boolean chosen;
	private Mode appliedGame = Mode.PRETTY;
	private Mode appliedFiles = Mode.PRETTY;
	/** Von uns gesetzte Vanilla-Optionen: Option → {alt, neu}. */
	private final Map<GameOptions.Opt, int[]> owned = new EnumMap<GameOptions.Opt, int[]>(GameOptions.Opt.class);
	/** Von uns gesetzte Mod-Einstellungen: "datei|bereich.schlüssel" → {alt, neu}. */
	private final Map<String, String[]> ownedFiles = new LinkedHashMap<String, String[]>();

	private static volatile FpsConfigMode shared;

	private FpsConfigMode(Path configDir) {
		this.configDir = configDir;
	}

	/** Beim Start des Clients (Config-Ordner des Spiels). */
	public static void init(Path configDir) {
		FpsConfigMode m = load(configDir);
		// Forge/NeoForge: keine Vorab-Phase – Mod-Dateien dann hier (wirken spätestens beim nächsten Start).
		m.applyModFiles();
		shared = m;
	}

	/** Stand dieser Sitzung (null = kein Loader hat {@link #init} aufgerufen). */
	public static FpsConfigMode shared() {
		return shared;
	}

	/** Liest den Stand (fehlt die Datei: „Schön“, noch nicht gewählt). */
	public static FpsConfigMode load(Path configDir) {
		FpsConfigMode m = new FpsConfigMode(configDir);
		if (configDir == null) return m;
		Path file = configDir.resolve(FILE);
		if (!Files.isRegularFile(file)) return m;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement root = new JsonParser().parse(r);
			if (root == null || !root.isJsonObject()) return m;
			JsonObject o = root.getAsJsonObject();
			m.mode = Mode.of(str(o, "mode"));
			m.chosen = o.has("chosen") && o.get("chosen").isJsonPrimitive() && o.get("chosen").getAsBoolean();
			m.appliedGame = Mode.of(str(o, "appliedGame"));
			m.appliedFiles = Mode.of(str(o, "appliedFiles"));
			JsonElement own = o.get("owned");
			if (own != null && own.isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : own.getAsJsonObject().entrySet()) {
					GameOptions.Opt opt = opt(e.getKey());
					if (opt == null || !e.getValue().isJsonArray() || e.getValue().getAsJsonArray().size() != 2) continue;
					JsonArray a = e.getValue().getAsJsonArray();
					m.owned.put(opt, new int[]{a.get(0).getAsInt(), a.get(1).getAsInt()});
				}
			}
			JsonElement files = o.get("ownedFiles");
			if (files != null && files.isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : files.getAsJsonObject().entrySet()) {
					if (!e.getValue().isJsonArray() || e.getValue().getAsJsonArray().size() != 2) continue;
					JsonArray a = e.getValue().getAsJsonArray();
					m.ownedFiles.put(e.getKey(), new String[]{a.get(0).getAsString(), a.get(1).getAsString()});
				}
			}
		} catch (IOException | RuntimeException e) {
			// kaputt: wie neu (nichts gehört uns)
		}
		return m;
	}

	public Mode mode() {
		return mode;
	}

	/** Hat der Spieler schon gewählt (Einführung beim ersten Start, Menü oder Launcher)? */
	public boolean chosen() {
		return chosen;
	}

	/** Modus wählen und speichern (angewendet wird mit {@link #applyGame}/{@link #applyModFiles}). */
	public void choose(Mode m) {
		mode = m == null ? Mode.PRETTY : m;
		chosen = true;
		save();
	}

	/** Muss an den Vanilla-Optionen etwas passieren (Modus seit dem letzten Anwenden gewechselt)? */
	public boolean gamePending() {
		return mode != appliedGame;
	}

	/**
	 * Vanilla-Optionen für den gewählten Modus setzen – nur beim Wechsel. Mit Shaderpack bleibt „Max FPS“ bei den
	 * Vanilla-Optionen wirkungslos (die Shader-Stufen sollen schön bleiben).
	 * @return Anzahl geänderter Optionen
	 */
	public int applyGame(GameOptions o, boolean shaders) {
		if (o == null || !gamePending()) return 0;
		int changed = 0;
		if (mode == Mode.MAX && !shaders) {
			for (GameOptions.Opt opt : new GameOptions.Opt[]{GameOptions.Opt.GRAPHICS, GameOptions.Opt.CLOUDS, GameOptions.Opt.PARTICLES,
					GameOptions.Opt.SMOOTH_LIGHTING, GameOptions.Opt.BIOME_BLEND}) {
				int cur = o.get(opt);
				int target = maxTarget(opt, cur, o.smoothLightingIsBoolean());
				if (target == GameOptions.NONE || target == cur) continue;
				if (o.set(opt, target)) {
					int[] before = owned.get(opt);
					owned.put(opt, new int[]{before != null ? before[0] : cur, target});
					changed++;
				}
			}
		} else {
			Iterator<Map.Entry<GameOptions.Opt, int[]>> it = owned.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<GameOptions.Opt, int[]> e = it.next();
				// Nur zurück, wenn noch unser Wert dasteht – sonst hat der Spieler selbst entschieden.
				if (o.get(e.getKey()) == e.getValue()[1] && o.set(e.getKey(), e.getValue()[0])) changed++;
				it.remove();
			}
		}
		if (changed > 0) o.save();
		appliedGame = mode;
		save();
		return changed;
	}

	/** Zielwert „Max FPS“ (nur senken; {@link GameOptions#NONE} = nichts tun). */
	static int maxTarget(GameOptions.Opt opt, int cur, boolean smoothIsBoolean) {
		if (cur == GameOptions.NONE) return GameOptions.NONE;
		switch (opt) {
			case GRAPHICS:
				// Benutzerdefinierte Voreinstellung (ab 1.21.11: 3) bleibt; Schön/Fabelhaft → Schnell.
				return cur == 1 || cur == 2 ? 0 : GameOptions.NONE;
			case CLOUDS:
				return cur > 0 ? 0 : GameOptions.NONE;
			case PARTICLES:
				return cur < 1 ? 1 : GameOptions.NONE;
			case SMOOTH_LIGHTING:
				return cur > 0 ? 0 : GameOptions.NONE;
			case BIOME_BLEND:
				return cur > 1 ? 1 : GameOptions.NONE;
			default:
				return GameOptions.NONE;
		}
	}

	/**
	 * Einstellungsdateien der Optimierungs-Mods für den gewählten Modus anpassen (Fabric: vor dem Laden der Mods).
	 * Nur vorhandene Schlüssel mit ihrem Standardwert oder unserem letzten Wert werden geändert.
	 * @return Anzahl geänderter Werte
	 */
	public int applyModFiles() {
		if (configDir == null || mode == appliedFiles) return 0;
		int changed = 0;
		Path file = configDir.resolve(SODIUM);
		if (Files.isRegularFile(file)) {
			try {
				JsonObject root;
				try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					JsonElement e = new JsonParser().parse(r);
					root = e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
				}
				if (root != null) {
					changed = applySodium(root);
					if (changed > 0) write(file, GSON.toJson(root));
				}
			} catch (IOException | RuntimeException e) {
				// Datei des Mods unlesbar: nicht anfassen
			}
		}
		appliedFiles = mode;
		save();
		return changed;
	}

	int applySodium(JsonObject root) {
		int changed = 0;
		for (String[] k : SODIUM_MAX) {
			String id = SODIUM + "|" + k[0] + "." + k[1];
			JsonElement section = root.get(k[0]);
			if (section == null || !section.isJsonObject()) continue;
			JsonObject s = section.getAsJsonObject();
			JsonElement v = s.get(k[1]);
			if (v == null || !v.isJsonPrimitive()) continue;
			String cur = v.getAsString();
			if (mode == Mode.MAX) {
				// Nur vom Standard („DEFAULT“) aus – eine bewusste Wahl des Spielers bleibt.
				if (!"DEFAULT".equals(cur)) continue;
				s.add(k[1], new JsonPrimitive(k[2]));
				ownedFiles.put(id, new String[]{cur, k[2]});
				changed++;
			} else {
				String[] own = ownedFiles.remove(id);
				if (own != null && own[1].equals(cur)) {
					s.add(k[1], new JsonPrimitive(own[0]));
					changed++;
				}
			}
		}
		return changed;
	}

	private void save() {
		if (configDir == null) return;
		JsonObject o = new JsonObject();
		o.addProperty("version", 1);
		o.addProperty("mode", mode.key());
		o.addProperty("chosen", chosen);
		o.addProperty("appliedGame", appliedGame.key());
		o.addProperty("appliedFiles", appliedFiles.key());
		JsonObject own = new JsonObject();
		for (Map.Entry<GameOptions.Opt, int[]> e : owned.entrySet()) {
			JsonArray a = new JsonArray();
			a.add(new JsonPrimitive(e.getValue()[0]));
			a.add(new JsonPrimitive(e.getValue()[1]));
			own.add(e.getKey().name().toLowerCase(Locale.ROOT), a);
		}
		o.add("owned", own);
		JsonObject files = new JsonObject();
		for (Map.Entry<String, String[]> e : ownedFiles.entrySet()) {
			JsonArray a = new JsonArray();
			a.add(new JsonPrimitive(e.getValue()[0]));
			a.add(new JsonPrimitive(e.getValue()[1]));
			files.add(e.getKey(), a);
		}
		o.add("ownedFiles", files);
		try {
			write(configDir.resolve(FILE), GSON.toJson(o));
		} catch (IOException | RuntimeException e) {
			// nicht speicherbar: gilt für diese Sitzung
		}
	}

	private static void write(Path file, String text) throws IOException {
		if (file.getParent() != null) Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			w.write(text);
			w.write('\n');
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	private static GameOptions.Opt opt(String name) {
		for (GameOptions.Opt o : GameOptions.Opt.values()) {
			if (o.name().equalsIgnoreCase(name)) return o;
		}
		return null;
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}
}
