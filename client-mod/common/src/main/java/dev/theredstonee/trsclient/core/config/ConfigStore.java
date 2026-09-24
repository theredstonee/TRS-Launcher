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

	/** Schreibt den aktuellen Zustand sofort und atomar (erst temporäre Datei, dann verschieben). */
	public void save(ModuleRegistry registry) throws IOException {
		write(registry.capture(), sequence.incrementAndGet());
	}

	/**
	 * Speichert im Hintergrund: der Zustand wird hier (im Spiel-Thread) festgehalten, geschrieben wird im Thread
	 * „TRS-Config“ – ein Tastendruck oder Menüklick wartet nie auf die Festplatte. Mehrere Aufrufe kurz
	 * hintereinander schreiben nur den neuesten Stand; beim Beenden des Spiels wird Ausstehendes noch geschrieben.
	 *
	 * @throws IOException der Fehler eines früheren Schreibens im Hintergrund (zum Loggen beim Aufrufer)
	 */
	public void saveLater(ModuleRegistry registry) throws IOException {
		TrsConfig snapshot = registry.capture();
		synchronized (pendingLock) {
			pending = snapshot;
			pendingSeq = sequence.incrementAndGet();
		}
		if (!scheduled.getAndSet(true)) worker().execute(this::flushPending);
		IOException e = lastError;
		if (e != null) {
			lastError = null;
			throw e;
		}
	}

	/** Schreibt einen noch ausstehenden Stand sofort (z. B. beim Beenden). */
	public void flush() {
		flushPending();
	}

	private final java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();
	private final java.util.concurrent.atomic.AtomicBoolean scheduled = new java.util.concurrent.atomic.AtomicBoolean();
	private final Object pendingLock = new Object();
	private final Object writeLock = new Object();
	private TrsConfig pending;
	private long pendingSeq;
	private long writtenSeq;
	private volatile IOException lastError;
	private java.util.concurrent.ExecutorService worker;

	private synchronized java.util.concurrent.ExecutorService worker() {
		if (worker == null) {
			worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "TRS-Config");
				t.setDaemon(true);
				return t;
			});
			// Beim Beenden (System.exit) noch Ausstehendes schreiben.
			try {
				Runtime.getRuntime().addShutdownHook(new Thread(this::flushPending, "TRS-Config-Exit"));
			} catch (IllegalStateException | SecurityException ignored) {
				// JVM fährt schon herunter – dann schreibt der Aufrufer selbst.
			}
		}
		return worker;
	}

	private void flushPending() {
		scheduled.set(false);
		TrsConfig snapshot;
		long seq;
		synchronized (pendingLock) {
			snapshot = pending;
			seq = pendingSeq;
			pending = null;
		}
		if (snapshot == null) return;
		try {
			write(snapshot, seq);
		} catch (IOException e) {
			lastError = e;
		}
	}

	/** Schreibt {@code config}, außer ein neuerer Stand ist schon geschrieben. */
	private void write(TrsConfig config, long seq) throws IOException {
		synchronized (writeLock) {
			if (seq <= writtenSeq) return;
			Path dir = file.toAbsolutePath().getParent();
			if (dir != null) Files.createDirectories(dir);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			writtenSeq = seq;
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
