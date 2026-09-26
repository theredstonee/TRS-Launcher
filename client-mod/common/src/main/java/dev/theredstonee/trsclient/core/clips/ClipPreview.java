package dev.theredstonee.trsclient.core.clips;

import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.link.TrsLink;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Clip-Vorschau im Spiel und „Im Launcher öffnen“ – beides über den TRS-Link (Protokoll 2).
 *
 * <p>Die Mod dekodiert kein Video: Der Launcher erzeugt mit seinem FFmpeg eine Vorschau-Leiste (PNG-Raster aus
 * kleinen Bildern, etwa eins pro Sekunde) in seinem Cache und nennt Pfad und Aufbau ({@code clips.preview}). Die
 * Mod liest nur diese eine Datei, prüft sie streng, lädt sie als eine Textur hoch und spielt die Bilder als
 * Zeitraffer ab ({@link PreviewAnimation}). Angefragt wird immer nur ein bloßer Dateiname aus dem Clip-Ordner –
 * der Launcher prüft, ob es den Clip für genau diese Instanz gibt.
 */
public final class ClipPreview {
	public static final String OP_PREVIEW = "clips.preview";
	public static final String OP_OPEN = "clips.open";
	/** FFmpeg braucht für lange Clips eine Weile (danach kommt die Leiste aus dem Cache). */
	static final long PREVIEW_TIMEOUT_MS = 120_000L;
	static final long OPEN_TIMEOUT_MS = 10_000L;
	static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
	static final int MAX_FRAMES = 400;
	static final int MAX_FRAME_SIZE = 640;
	static final int MAX_SHEET_SIDE = 4096;

	public enum State {
		/** Noch nichts angefragt. */
		IDLE,
		LOADING,
		READY,
		/** Anfrage gescheitert ({@link #code()}). */
		FAILED,
		/** Geht hier gar nicht ({@link #code()}: {@code no_launcher}, {@code old_launcher}, {@code offline}). */
		UNAVAILABLE
	}

	/** Zugang zum Launcher – in Tests die echte {@link TrsLink} gegen eine Launcher-Attrappe. */
	public interface Link {
		/** Wurde das Spiel über den TRS Launcher (Protokoll 2) gestartet? */
		boolean launchedByLauncher();

		TrsLink.Status status();

		void request(String op, Map<String, String> args, long timeoutMs, TrsLink.Callback callback);
	}

	/** Ergebnis von „Im Launcher öffnen“ (Netz-Thread). {@code code} = null bei Erfolg. */
	public interface OpenResult {
		void done(String code);
	}

	/** Die gemeinsame Verbindung des Spiels (wird erst beim Aufruf nachgeschlagen). */
	public static Link shared() {
		return new Link() {
			@Override
			public boolean launchedByLauncher() {
				TrsLink l = TrsLink.shared();
				return l != null && l.launchedByLauncher();
			}

			@Override
			public TrsLink.Status status() {
				TrsLink l = TrsLink.shared();
				return l == null ? null : l.status();
			}

			@Override
			public void request(String op, Map<String, String> args, long timeoutMs, TrsLink.Callback callback) {
				TrsLink l = TrsLink.shared();
				if (l == null) callback.failed("offline");
				else l.request(op, args, timeoutMs, callback);
			}
		};
	}

	public static Link of(final TrsLink link) {
		return new Link() {
			@Override
			public boolean launchedByLauncher() {
				return link.launchedByLauncher();
			}

			@Override
			public TrsLink.Status status() {
				return link.status();
			}

			@Override
			public void request(String op, Map<String, String> args, long timeoutMs, TrsLink.Callback callback) {
				link.request(op, args, timeoutMs, callback);
			}
		};
	}

	/** Dekodiertes Raster (noch ohne Textur). */
	public static final class Sheet {
		public final int width;
		public final int height;
		final int[] argb;
		public final int frames;
		public final int cols;
		public final int rows;
		public final int frameWidth;
		public final int frameHeight;
		public final int intervalMs;
		public final long durationMs;

		Sheet(int width, int height, int[] argb, int frames, int cols, int rows, int frameWidth, int frameHeight, int intervalMs,
				long durationMs) {
			this.width = width;
			this.height = height;
			this.argb = argb;
			this.frames = frames;
			this.cols = cols;
			this.rows = rows;
			this.frameWidth = frameWidth;
			this.frameHeight = frameHeight;
			this.intervalMs = intervalMs;
			this.durationMs = durationMs;
		}

		/** Linke obere Ecke von Bild {@code i} im Raster (Pixel). */
		public int u(int i) {
			return (i % cols) * frameWidth;
		}

		public int v(int i) {
			return (i / cols) * frameHeight;
		}
	}

	/** Prüft Pfad und Datei aus der Antwort des Launchers: absolut, {@code .png}, echte Datei, nicht zu groß. */
	static Path checkedPath(String raw) {
		if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0) return null;
		try {
			Path p = Paths.get(raw);
			if (!p.isAbsolute() || !p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) return null;
			if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return null;
			long size = Files.size(p);
			return size > 0 && size <= MAX_FILE_BYTES ? p : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** PNG + Angaben des Launchers → Raster. Unstimmige Angaben → {@link IOException}. */
	static Sheet decode(byte[] png, TrsLink.PreviewDto meta) throws IOException {
		if (meta == null || meta.frames == null || meta.cols == null || meta.rows == null || meta.frameWidth == null
				|| meta.frameHeight == null) {
			throw new IOException("Vorschau ohne Aufbau");
		}
		int frames = meta.frames;
		int cols = meta.cols;
		int rows = meta.rows;
		int fw = meta.frameWidth;
		int fh = meta.frameHeight;
		if (frames < 1 || frames > MAX_FRAMES || cols < 1 || rows < 1 || fw < 1 || fh < 1 || fw > MAX_FRAME_SIZE
				|| fh > MAX_FRAME_SIZE || (long) cols * rows < frames || (long) cols * fw > MAX_SHEET_SIDE
				|| (long) rows * fh > MAX_SHEET_SIDE) {
			throw new IOException("Unstimmiger Aufbau der Vorschau");
		}
		PngDecoder.Image img = PngDecoder.decode(png);
		if (img.width < cols * fw || img.height < rows * fh) throw new IOException("Vorschau kleiner als angegeben");
		int interval = meta.intervalMs == null ? 1000 : Math.max(1, meta.intervalMs);
		long duration = meta.durationMs == null ? (long) interval * frames : Math.max(0L, meta.durationMs);
		return new Sheet(img.width, img.height, img.argb, frames, cols, rows, fw, fh, interval, duration);
	}

	private static int counter;

	private final Link link;
	private final Executor worker;
	private final PreviewAnimation animation = new PreviewAnimation();
	private final String texturePrefix;
	private volatile State state = State.IDLE;
	private volatile String code;
	private volatile String clip;
	/** Fertig dekodiert, wartet auf den Render-Thread. */
	private volatile Sheet pending;
	private volatile int generation;
	private Sheet sheet;
	private TextureRef texture;
	private int uploads;
	private long lastFrame;

	public ClipPreview(Link link, Executor worker) {
		this.link = link;
		this.worker = worker;
		synchronized (ClipPreview.class) {
			texturePrefix = "trs_clip_preview/" + (counter++) + "_";
		}
	}

	public State state() {
		return state;
	}

	/** Fehler- bzw. Grund-Code bei {@link State#FAILED} / {@link State#UNAVAILABLE}. */
	public String code() {
		return code;
	}

	public String clip() {
		return clip;
	}

	public PreviewAnimation animation() {
		return animation;
	}

	/** Raster mit Textur (Render-Thread), sonst null. */
	public Sheet sheet() {
		return texture == null ? null : sheet;
	}

	public TextureRef texture() {
		return texture;
	}

	/** Geht es hier überhaupt (über den Launcher gestartet, Launcher kann es)? null = ja, sonst der Grund. */
	public String unavailableReason() {
		if (!link.launchedByLauncher()) return "no_launcher";
		TrsLink.Status s = link.status();
		if (s == null || !s.connected) return "offline";
		if (!s.has(TrsLink.FEATURE_CLIPS_PREVIEW)) return "old_launcher";
		return null;
	}

	/** Vorschau für diesen Clip (Dateiname aus dem Clip-Ordner) anfordern; gleicher Clip → nichts Neues. */
	public void load(final String clipName) {
		if (clipName == null) return;
		if (clipName.equals(clip) && (state == State.LOADING || state == State.READY)) return;
		releaseTexture();
		final int gen = ++generation;
		clip = clipName;
		pending = null;
		String reason = unavailableReason();
		if (reason != null) {
			state = State.UNAVAILABLE;
			code = reason;
			return;
		}
		state = State.LOADING;
		code = null;
		link.request(OP_PREVIEW, Collections.singletonMap("clip", clipName), PREVIEW_TIMEOUT_MS, new TrsLink.Callback() {
			@Override
			public void done(final TrsLink.Line response) {
				if (gen != generation) return;
				try {
					worker.execute(new Runnable() {
						@Override
						public void run() {
							read(gen, response.preview);
						}
					});
				} catch (RuntimeException e) {
					fail(gen, "error");
				}
			}

			@Override
			public void failed(String error) {
				fail(gen, error);
			}
		});
	}

	/** Neu anfordern (z. B. nach „Launcher beschäftigt“). */
	public void retry() {
		String c = clip;
		clip = null;
		state = State.IDLE;
		load(c);
	}

	private void read(int gen, TrsLink.PreviewDto meta) {
		Path path = meta == null ? null : checkedPath(meta.path);
		if (path == null) {
			fail(gen, "error");
			return;
		}
		try {
			Sheet s = decode(Files.readAllBytes(path), meta);
			if (gen == generation) pending = s;
		} catch (IOException | RuntimeException | OutOfMemoryError e) {
			fail(gen, "error");
		}
	}

	private void fail(int gen, String error) {
		if (gen != generation) return;
		code = error == null ? "error" : error;
		state = State.FAILED;
	}

	/** Im Render-Thread je Bild aufrufen: fertige Vorschau hochladen, Animation weiterlaufen lassen. */
	public void frame(long nowMs) {
		long dt = lastFrame == 0 ? 0 : nowMs - lastFrame;
		lastFrame = nowMs;
		Sheet ready = pending;
		if (ready != null) {
			pending = null;
			Textures.Store store = Textures.store();
			if (store == null) {
				state = State.FAILED;
				code = "unsupported";
				return;
			}
			releaseTexture();
			texture = store.upload(texturePrefix + (uploads++), ready.width, ready.height, ready.argb);
			if (texture == null) {
				state = State.FAILED;
				code = "error";
				return;
			}
			sheet = ready;
			animation.reset(ready.frames, ready.intervalMs);
			state = State.READY;
			return;
		}
		if (state == State.READY) animation.advance(dt);
	}

	/**
	 * „Im Launcher öffnen“: der Launcher kommt nach vorn und spielt den Clip im Player ab. {@code result} bekommt null
	 * (geklappt) oder einen Code ({@code no_launcher}, {@code old_launcher}, {@code offline}, {@code unknown_clip} …).
	 */
	public void openInLauncher(String clipName, final OpenResult result) {
		if (!link.launchedByLauncher()) {
			result.done("no_launcher");
			return;
		}
		TrsLink.Status s = link.status();
		if (s == null || !s.connected) {
			result.done("offline");
			return;
		}
		if (!s.has(TrsLink.FEATURE_CLIPS_OPEN)) {
			result.done("old_launcher");
			return;
		}
		link.request(OP_OPEN, Collections.singletonMap("clip", clipName), OPEN_TIMEOUT_MS, new TrsLink.Callback() {
			@Override
			public void done(TrsLink.Line response) {
				result.done(null);
			}

			@Override
			public void failed(String error) {
				result.done(error == null ? "error" : error);
			}
		});
	}

	private void releaseTexture() {
		if (texture != null) {
			Textures.Store store = Textures.store();
			if (store != null) store.release(texture);
			texture = null;
		}
		sheet = null;
	}

	/** Alles freigeben (Bildschirm geschlossen). */
	public void release() {
		generation++;
		pending = null;
		releaseTexture();
		state = State.IDLE;
		clip = null;
	}
}
