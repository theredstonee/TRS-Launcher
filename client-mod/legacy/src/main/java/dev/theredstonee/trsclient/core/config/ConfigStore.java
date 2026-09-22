package dev.theredstonee.trsclient.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.theredstonee.trsclient.core.module.ModuleRegistry;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Lädt/speichert {@code trsclient.json}. Fehlt die Datei, gelten die Standardwerte;
 * ist sie kaputt, wird sie nach {@code trsclient.json.broken} verschoben und neu angelegt.
 */
public final class ConfigStore {
	/** Ergebnis des Ladens. */
	public enum Status {
		/** Datei gelesen. */
		LOADED,
		/** Keine Datei vorhanden – Standardwerte. */
		CREATED,
		/** Datei war unlesbar/kaputt – Standardwerte, Original gesichert. */
		RECOVERED
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path file;

	public ConfigStore(Path file) {
		this.file = file;
	}

	public Path file() {
		return file;
	}

	/** Pfad der Sicherung einer kaputten Datei. */
	public Path brokenFile() {
		return file.resolveSibling(file.getFileName() + ".broken");
	}

	/** Lädt die Config in die Module und legt sie bei Bedarf (neu) an. */
	public Status load(ModuleRegistry registry) {
		Status status;
		TrsConfig config = null;
		if (!Files.exists(file)) {
			status = Status.CREATED;
		} else {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				config = GSON.fromJson(reader, TrsConfig.class);
				status = config == null ? Status.RECOVERED : Status.LOADED;
			} catch (JsonParseException | IllegalStateException | ClassCastException | IOException e) {
				status = Status.RECOVERED;
				config = null;
			}
			if (status == Status.RECOVERED) backupBroken();
		}
		registry.apply(config != null ? config : new TrsConfig());
		if (status != Status.LOADED) {
			try {
				save(registry);
			} catch (IOException ignored) {
				// Speichern wird beim nächsten Schließen erneut versucht.
			}
		}
		return status;
	}

	/** Schreibt den aktuellen Zustand atomar (erst temporäre Datei, dann verschieben). */
	public void save(ModuleRegistry registry) throws IOException {
		Path dir = file.toAbsolutePath().getParent();
		if (dir != null) Files.createDirectories(dir);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(registry.capture(), writer);
		}
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void backupBroken() {
		try {
			Files.move(file, brokenFile(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ignored) {
			// Sicherung ist nur Komfort; Standardwerte gelten trotzdem.
		}
	}
}
