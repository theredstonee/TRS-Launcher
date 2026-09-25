package dev.theredstonee.trsclient.core.wardrobe;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Liest (nur lesend) die Skin-Bibliothek des TRS Launchers, wenn das Spiel aus ihm gestartet wurde: Instanzen liegen
 * unter {@code <launcher>/instances/<id>/minecraft}, die Bibliothek unter {@code <launcher>/skins/library.json} mit
 * {@code skin-<id>.png}. So erscheinen auch Skins, die (noch) nicht synchronisiert sind – z. B. ohne TRS-Dienste.
 */
public final class LauncherLibrary {
	private static final Gson GSON = new Gson();

	private LauncherLibrary() {
	}

	/** Ein Skin der Launcher-Bibliothek. */
	public static final class Item {
		public final String id;
		public final String name;
		public final boolean slim;
		public final Path file;

		Item(String id, String name, boolean slim, Path file) {
			this.id = id;
			this.name = name;
			this.slim = slim;
			this.file = file;
		}
	}

	static final class LibraryFile {
		List<LibrarySkin> skins;
	}

	static final class LibrarySkin {
		String id;
		String name;
		String variant;
		String file;
	}

	/** Ordner der Launcher-Bibliothek zu einem Instanz-{@code config}-Ordner oder null. */
	public static Path skinsDir(Path configDir) {
		if (configDir == null) return null;
		Path game = configDir.toAbsolutePath().normalize().getParent();
		if (game == null || !"minecraft".equals(String.valueOf(game.getFileName()))) return null;
		Path instance = game.getParent();
		Path instances = instance == null ? null : instance.getParent();
		if (instances == null || !"instances".equals(String.valueOf(instances.getFileName()))) return null;
		Path root = instances.getParent();
		if (root == null) return null;
		Path dir = root.resolve("skins");
		return Files.isRegularFile(dir.resolve("library.json")) ? dir : null;
	}

	/** Liest die Bibliothek (leer, wenn es keine gibt oder sie unlesbar ist). */
	public static List<Item> read(Path configDir) {
		Path dir = skinsDir(configDir);
		if (dir == null) return Collections.emptyList();
		try {
			Path f = dir.resolve("library.json");
			if (Files.size(f) > 1024 * 1024) return Collections.emptyList();
			LibraryFile lib = GSON.fromJson(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), LibraryFile.class);
			if (lib == null || lib.skins == null) return Collections.emptyList();
			List<Item> out = new ArrayList<Item>();
			for (LibrarySkin s : lib.skins) {
				if (s == null || !SkinFiles.validId(s.id)) continue;
				String file = s.file == null ? "skin-" + s.id + ".png" : s.file;
				if (!file.matches("[A-Za-z0-9._-]{1,64}") || file.startsWith(".")) continue;
				Path p = dir.resolve(file);
				if (!Files.isRegularFile(p)) continue;
				out.add(new Item(s.id, SkinFiles.cleanName(s.name, "Skin"), "slim".equals(s.variant), p));
				if (out.size() >= SkinLibrary.MAX_SKINS * 2) break;
			}
			return out;
		} catch (IOException | JsonSyntaxException | IllegalStateException e) {
			return Collections.emptyList();
		}
	}
}
