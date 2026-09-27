package dev.theredstonee.trsclient.core.panorama;

import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.util.OpenPath;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Panorama-Screenshots: nimmt ein 360°-Panorama auf und speichert es unter {@code screenshots/panorama/<Zeit>/} –
 * sechs Würfelbilder {@code panorama_0..5.png} (wie Vanillas Titelbildschirm-Panorama, direkt als Ressourcenpaket
 * nutzbar) und/oder ein 360°-Bild {@code panorama_360.png} (equirektangular).
 *
 * <p>Zwei Wege, je nach Version:
 * <ul>
 *   <li><b>Vanilla</b> (ab Minecraft 1.17): {@code Minecraft#grabPanoramixScreenshot} rendert die sechs Seiten in
 *   einem Bild (ohne HUD, ohne Hand, 90° Sichtfeld). Der Baum ruft {@link #beginVanilla} und danach
 *   {@link #vanillaFinished}; die Dateien schreibt Minecraft im Hintergrund, das Weitere macht {@link #worker}.</li>
 *   <li><b>Eigene Kamera-Drehung</b> (ältere Versionen): {@link #beginOwn} liefert eine {@link OwnCapture}, die sechs
 *   Bilder lang Blickwinkel und Sichtfeld vorgibt; der Baum blendet HUD und Hand aus und reicht jedes fertige Bild
 *   zurück (mittleres Quadrat, {@link CubeToEquirect#cropCenterSquare}).</li>
 * </ul>
 * Danach meldet ein Toast „Panorama gespeichert“ mit „Ordner öffnen“ (Taste erneut drücken bzw. Knopf auf der
 * Modulseite). Teilen per Link liefert ein anderes Paket über {@link #setShareHandler}.
 */
public final class Panorama {
	/** Seiten in Vanilla-Reihenfolge: Gier-Versatz und Neigung (Grad). */
	static final float[] FACE_YAW = {0f, 90f, 180f, -90f, 0f, 0f};
	static final float[] FACE_PITCH = {0f, 0f, 0f, 0f, -90f, 90f};
	/** Bilder Wartezeit je Seite der eigenen Aufnahme (Chunks nachladen, Sichtbarkeit neu berechnen). */
	static final int SETTLE_FIRST = 12;
	static final int SETTLE_FACE = 5;
	/** Wie lange der Toast mit Ergebnis sichtbar bleibt. */
	static final long TOAST_MS = 9000;
	/** Größte Breite des 360°-Bildes. */
	static final int MAX_EQUIRECT_W = 8192;

	/** Teilen per Link (füllt das Sozial-/Screenshot-Paket); null = kein „Teilen“. */
	public interface ShareHandler {
		/**
		 * @param image  bevorzugtes Bild (360°-Bild, sonst {@code panorama_0.png})
		 * @param folder Ordner der Aufnahme
		 */
		void share(Path image, Path folder);
	}

	public enum State {
		IDLE, REQUESTED, CAPTURING, SAVING
	}

	private static final Panorama INSTANCE = new Panorama();

	public static Panorama get() {
		return INSTANCE;
	}

	private volatile State state = State.IDLE;
	private volatile ShareHandler share;
	private long screenFreeSince;
	private boolean wantCube = true;
	private boolean wantEquirect = true;
	private Path gameDir;
	private Path currentDir;
	// Ergebnis / Toast
	private volatile Path lastFolder;
	private volatile Path lastImage;
	private volatile String toastTitle;
	private volatile String toastText;
	private volatile boolean toastError;
	private volatile long toastUntil;
	private volatile long toastStart;
	private ExecutorService worker;

	Panorama() {
	}

	// --- Auslösen ---

	/** Taste bzw. Menü: Aufnahme anfordern (wird im nächsten passenden Tick ausgeführt). */
	public synchronized void request(boolean cube, boolean equirect) {
		if (state != State.IDLE) return;
		wantCube = cube || !equirect;
		wantEquirect = equirect;
		state = State.REQUESTED;
		screenFreeSince = 0;
	}

	/**
	 * Taste gedrückt: zeigt der Toast gerade ein Ergebnis, öffnet sie den Ordner, sonst nimmt sie auf.
	 *
	 * @return true = Ordner geöffnet
	 */
	public boolean onKey(long now, boolean cube, boolean equirect) {
		if (state == State.IDLE && !toastError && lastFolder != null && now < toastUntil) {
			openLastFolder();
			toastUntil = Math.min(toastUntil, now + 1500);
			return true;
		}
		request(cube, equirect);
		return false;
	}

	public State state() {
		return state;
	}

	/** 0 = nichts zu tun, 1 = bitte offenen Bildschirm schließen, 2 = jetzt aufnehmen. */
	public static final int POLL_NONE = 0, POLL_CLOSE_SCREEN = 1, POLL_CAPTURE = 2;

	/**
	 * Einmal je Client-Tick. Wartet nach dem Schließen eines Menüs kurz, damit das Bild ohne Menü gerendert ist.
	 *
	 * @param inWorld Spieler + Welt vorhanden
	 */
	public synchronized int poll(long now, boolean inWorld, boolean screenOpen) {
		if (state != State.REQUESTED) return POLL_NONE;
		if (!inWorld) {
			state = State.IDLE;
			fail(I18n.tr("panorama.noWorld"), now);
			return POLL_NONE;
		}
		if (screenOpen) {
			screenFreeSince = 0;
			return POLL_CLOSE_SCREEN;
		}
		if (screenFreeSince == 0) screenFreeSince = now;
		if (now - screenFreeSince < 350) return POLL_NONE;
		state = State.CAPTURING;
		return POLL_CAPTURE;
	}

	// --- Vanilla-Weg ---

	/**
	 * Legt den Ordner der Aufnahme an. Der Baum übergibt ihn als „Spielordner“ an
	 * {@code grabPanoramixScreenshot} – Minecraft schreibt dann nach {@code <ordner>/screenshots/panorama_N.png}.
	 *
	 * @return Ordner oder null (Fehler schon gemeldet)
	 */
	public synchronized Path beginVanilla(Path gameDirectory, long now) {
		gameDir = gameDirectory;
		try {
			currentDir = newFolder(gameDirectory, now);
			return currentDir;
		} catch (IOException e) {
			state = State.IDLE;
			fail(I18n.tr("panorama.failed", e.getMessage()), now);
			return null;
		}
	}

	/** Vanilla ist durch (Dateien entstehen noch im Hintergrund). {@code error} = Meldung von Minecraft oder null. */
	public synchronized void vanillaFinished(String error, long now) {
		final Path dir = currentDir;
		if (error != null || dir == null) {
			state = State.IDLE;
			fail(I18n.tr("panorama.failed", error == null ? "?" : error), now);
			return;
		}
		state = State.SAVING;
		showSaving(now);
		final boolean cube = wantCube, equirect = wantEquirect;
		worker().execute(new Runnable() {
			@Override
			public void run() {
				finishVanilla(dir, cube, equirect);
			}
		});
	}

	private void finishVanilla(Path dir, boolean cube, boolean equirect) {
		try {
			Path inner = dir.resolve("screenshots");
			int[][] faces = new int[6][];
			int size = 0;
			long deadline = System.currentTimeMillis() + 30000;
			for (int i = 0; i < 6; i++) {
				Path src = inner.resolve("panorama_" + i + ".png");
				PngDecoder.Image img = null;
				while (img == null) {
					if (Files.isRegularFile(src)) {
						try {
							img = PngDecoder.decode(Files.readAllBytes(src));
						} catch (IOException | RuntimeException notYet) {
							img = null;
						}
					}
					if (img == null) {
						if (System.currentTimeMillis() > deadline) throw new IOException("panorama_" + i + ".png fehlt");
						Thread.sleep(100);
					}
				}
				if (img.width != img.height || (size != 0 && img.width != size)) throw new IOException("Bildgröße " + img.width + "×" + img.height);
				size = img.width;
				if (equirect) faces[i] = img.argb;
				Files.move(src, dir.resolve("panorama_" + i + ".png"), StandardCopyOption.REPLACE_EXISTING);
			}
			deleteQuietly(inner);
			finish(dir, faces, size, cube, equirect, false);
		} catch (Exception e) {
			failed(e);
		}
	}

	// --- Eigene Aufnahme ---

	/** Startet die eigene Aufnahme (ältere Versionen). Ausgangsblick = aktueller Blick des Spielers. */
	public synchronized OwnCapture beginOwn(Path gameDirectory, float yaw, long now) {
		gameDir = gameDirectory;
		try {
			currentDir = newFolder(gameDirectory, now);
		} catch (IOException e) {
			state = State.IDLE;
			fail(I18n.tr("panorama.failed", e.getMessage()), now);
			return null;
		}
		state = State.CAPTURING;
		return new OwnCapture(this, currentDir, yaw, wantCube, wantEquirect);
	}

	/**
	 * Eine laufende eigene Aufnahme: je Bild {@link #yaw()}/{@link #pitch()} setzen (Sichtfeld 90°, HUD und Hand aus),
	 * nach dem Zeichnen {@link #wantsFrame()} fragen und das Bild mit {@link #frame} übergeben.
	 */
	public static final class OwnCapture {
		private final Panorama owner;
		private final Path dir;
		private final float baseYaw;
		private final boolean cube, equirect;
		private final int[][] faces = new int[6][];
		private int face;
		private int settle = SETTLE_FIRST;
		private int size;
		private boolean finished;

		OwnCapture(Panorama owner, Path dir, float baseYaw, boolean cube, boolean equirect) {
			this.owner = owner;
			this.dir = dir;
			this.baseYaw = baseYaw;
			this.cube = cube;
			this.equirect = equirect;
		}

		/** Gier der aktuellen Seite (Grad, Minecraft-Richtung). */
		public float yaw() {
			return baseYaw + FACE_YAW[Math.min(face, 5)];
		}

		public float pitch() {
			return FACE_PITCH[Math.min(face, 5)];
		}

		/** Sichtfeld während der Aufnahme (senkrecht, Grad). */
		public float fov() {
			return 90f;
		}

		public int face() {
			return face;
		}

		public boolean finished() {
			return finished;
		}

		/** Nach dem Zeichnen eines Bildes: soll dieses Bild übernommen werden? (zählt die Wartebilder herunter) */
		public boolean wantsFrame() {
			if (finished) return false;
			if (settle > 0) {
				settle--;
				return false;
			}
			return true;
		}

		/**
		 * Übergibt das gezeichnete Bild (ganzes Fenster, Zeilen von oben). Es wird auf das mittlere Quadrat
		 * beschnitten. Nach der sechsten Seite wird im Hintergrund gespeichert.
		 */
		public void frame(int[] argb, int width, int height) {
			if (finished) return;
			int[] square = CubeToEquirect.cropCenterSquare(argb, width, height);
			int s = Math.min(width, height);
			if (size != 0 && s != size) {
				// Fenstergröße während der Aufnahme geändert – Seite wiederholen.
				settle = SETTLE_FACE;
				return;
			}
			size = s;
			faces[face++] = square;
			settle = SETTLE_FACE;
			if (face < 6) return;
			finished = true;
			owner.ownFinished(this);
		}

		/** Abbrechen (Welt verlassen, Fehler). */
		public void cancel(String reason) {
			if (finished) return;
			finished = true;
			owner.ownCancelled(reason);
		}
	}

	private synchronized void ownFinished(final OwnCapture c) {
		state = State.SAVING;
		showSaving(System.currentTimeMillis());
		worker().execute(new Runnable() {
			@Override
			public void run() {
				try {
					if (c.cube) {
						for (int i = 0; i < 6; i++) PanoramaPng.write(c.dir.resolve("panorama_" + i + ".png"), c.size, c.size, c.faces[i]);
					}
					finish(c.dir, c.faces, c.size, c.cube, c.equirect, true);
				} catch (Exception e) {
					failed(e);
				}
			}
		});
	}

	private synchronized void ownCancelled(String reason) {
		state = State.IDLE;
		deleteQuietly(currentDir);
		fail(I18n.tr("panorama.failed", reason), System.currentTimeMillis());
	}

	// --- Abschluss (Hintergrund) ---

	private void finish(Path dir, int[][] faces, int size, boolean cube, boolean equirect, boolean ownFiles) throws IOException {
		Path image = dir.resolve("panorama_0.png");
		if (equirect) {
			int w = Math.min(MAX_EQUIRECT_W, Math.max(2, size * 4));
			w &= ~1;
			int[] out = CubeToEquirect.compose(faces, size, w);
			image = dir.resolve("panorama_360.png");
			PanoramaPng.write(image, w, w / 2, out);
			if (!cube && !ownFiles) {
				for (int i = 0; i < 6; i++) Files.deleteIfExists(dir.resolve("panorama_" + i + ".png"));
			}
		}
		synchronized (this) {
			lastFolder = dir;
			lastImage = image;
			state = State.IDLE;
			long now = System.currentTimeMillis();
			toastTitle = I18n.tr("panorama.saved");
			toastText = dir.getFileName().toString();
			toastError = false;
			toastStart = now;
			toastUntil = now + TOAST_MS;
		}
	}

	private void failed(Exception e) {
		synchronized (this) {
			state = State.IDLE;
			String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			fail(I18n.tr("panorama.failed", msg), System.currentTimeMillis());
		}
	}

	private void fail(String text, long now) {
		toastTitle = I18n.tr("panorama.title");
		toastText = text;
		toastError = true;
		toastStart = now;
		toastUntil = now + 5000;
	}

	private void showSaving(long now) {
		toastTitle = I18n.tr("panorama.title");
		toastText = I18n.tr("panorama.saving");
		toastError = false;
		toastStart = now;
		toastUntil = Long.MAX_VALUE;
	}

	// --- Ergebnis ---

	/** Ordner der letzten Aufnahme (null = noch keine). */
	public Path lastFolder() {
		return lastFolder;
	}

	/** Bild der letzten Aufnahme (360°-Bild bzw. panorama_0.png). */
	public Path lastImage() {
		return lastImage;
	}

	/** Öffnet den Ordner der letzten Aufnahme bzw. {@code screenshots/panorama}. */
	public boolean openLastFolder() {
		Path dir = lastFolder;
		if (dir == null || !Files.isDirectory(dir)) {
			dir = gameDir == null ? null : gameDir.resolve("screenshots").resolve("panorama");
			if (dir != null) {
				try {
					Files.createDirectories(dir);
				} catch (IOException ignored) {
					// OpenPath meldet dann false.
				}
			}
		}
		return dir != null && OpenPath.open(dir.toAbsolutePath());
	}

	/** Spielordner merken (für „Ordner öffnen“ vor der ersten Aufnahme). */
	public void setGameDir(Path dir) {
		if (gameDir == null) gameDir = dir;
	}

	public static void setShareHandler(ShareHandler handler) {
		INSTANCE.share = handler;
	}

	public boolean canShare() {
		return share != null && lastImage != null && state == State.IDLE;
	}

	/** Teilen über den eingehängten Handler (falls vorhanden). */
	public boolean share() {
		ShareHandler h = share;
		Path image = lastImage, folder = lastFolder;
		if (h == null || image == null || folder == null) return false;
		try {
			h.share(image, folder);
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	// --- Toast ---

	/** Daten des Toasts (null = keiner sichtbar). */
	public PanoramaToast.Data toast(long now) {
		String title = toastTitle;
		if (title == null || now >= toastUntil) return null;
		return new PanoramaToast.Data(title, toastText, toastError, state == State.SAVING,
				!toastError && state == State.IDLE && lastFolder != null, now - toastStart, toastUntil == Long.MAX_VALUE ? -1 : toastUntil - now);
	}

	// --- Hilfen ---

	private synchronized ExecutorService worker() {
		if (worker == null) {
			worker = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "TRS-Panorama");
				t.setDaemon(true);
				t.setPriority(Thread.NORM_PRIORITY - 1);
				return t;
			});
		}
		return worker;
	}

	/** Neuer Ordner {@code screenshots/panorama/<Datum_Uhrzeit>[_n]}. */
	static Path newFolder(Path gameDirectory, long now) throws IOException {
		Path root = gameDirectory.toAbsolutePath().normalize().resolve("screenshots").resolve("panorama");
		Files.createDirectories(root);
		String stamp = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date(now));
		Path dir = root.resolve(stamp);
		for (int i = 2; Files.exists(dir); i++) dir = root.resolve(stamp + "_" + i);
		Files.createDirectories(dir);
		return dir;
	}

	static void deleteQuietly(Path p) {
		if (p == null || !Files.exists(p)) return;
		try {
			if (Files.isDirectory(p)) {
				try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
					for (Path c : ds) deleteQuietly(c);
				}
			}
			Files.deleteIfExists(p);
		} catch (IOException ignored) {
			// Reste stören nicht.
		}
	}

	/** Nur für Tests: Zustand zurücksetzen. */
	synchronized void resetForTests() {
		state = State.IDLE;
		lastFolder = null;
		lastImage = null;
		toastTitle = null;
		toastUntil = 0;
	}
}
