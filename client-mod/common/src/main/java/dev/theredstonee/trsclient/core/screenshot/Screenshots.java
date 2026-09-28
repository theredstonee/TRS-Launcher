package dev.theredstonee.trsclient.core.screenshot;

import dev.theredstonee.trsclient.core.clips.ScreenshotShare;
import dev.theredstonee.trsclient.core.clips.Thumbnails;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.ComfortModules;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.social.Social;
import dev.theredstonee.trsclient.core.social.SocialOverlay;
import dev.theredstonee.trsclient.core.social.SocialPlatform;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.core.ui.screenshot.ScreenshotEditorUi;
import dev.theredstonee.trsclient.core.util.OpenPath;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bildschirmfotos wie bei Essential (nur das Verhalten, eigener Code): nach F2 eine kleine Vorschau oben rechts mit
 * Bearbeiten/Favorit/Bild kopieren/An Freunde senden, eine Chatzeile mit denselben Aktionen und Bild beim Überfahren,
 * der Bild-Editor ({@link ScreenshotEditorUi}) und „Link kopieren“ über die geteilten Screenshots (§23).
 *
 * <p>Einstieg der Versionsbäume: {@link #install} beim Start, {@link #tick} im Client-Tick, {@link #render} im HUD
 * bzw. nach jedem Bildschirm, {@link #mouseClicked} bei einem Mausklick mit offenem Bildschirm (frei beweglicher
 * Zeiger), {@link #chatHover} über der Chatzeile, {@link #onVanillaLine}/{@link #onMarker} für den Chat.
 *
 * <p>Erkennung: {@link ScreenshotWatcher} (Ordner {@code screenshots/}, unabhängig davon, wer das Bild aufgenommen hat)
 * plus der schnellere Weg über die Vanilla-Chatzeile. Kein Mixin in {@code Screenshot} nötig – so bleibt es mit
 * Essential und anderen Screenshot-Mods verträglich (siehe {@link EssentialInterop}).
 */
public final class Screenshots {
	/** Wartezeit auf die Vanilla-Chatzeile, bevor wir eine eigene schreiben (Essential verschluckt sie ab Werk). */
	static final long CHAT_LINE_WAIT_MS = 1_500L;
	static final long WATCH_INTERVAL_MS = 400L;
	/** Essential-Einstellung höchstens so oft prüfen. */
	static final long ESSENTIAL_CHECK_MS = 2_000L;
	/** Erst nach dieser Zeit im Spiel Essentials Klassen anfassen (sicher initialisiert). */
	static final long ESSENTIAL_DELAY_MS = 5_000L;

	/** Was die Versionsbäume liefern. */
	public interface Platform {
		/** Spielordner ({@code screenshots/} liegt darin). */
		Path gameDir();

		/** Konfigurationsordner ({@code config}). */
		Path configDir();

		/** Einen TRS-Bildschirm öffnen (z. B. den Editor); false = geht hier nicht. */
		boolean openUi(String title, UiScreen ui);

		/** Den gerade offenen TRS-Bildschirm schließen (zurück ins Spiel bzw. zum vorherigen Bildschirm). */
		void closeUi();

		/** Text in die Zwischenablage. */
		boolean copyText(String text);

		/** Kurze Meldung (Aktionsleiste). */
		void notice(String text);

		void playClick();

		/**
		 * Eigene Chatzeile „Bildschirmfoto gespeichert …“ mit den Aktionen (Teile aus {@link #chatLine}); false = diese
		 * Version kann keine anklickbaren Chatzeilen.
		 */
		default boolean addChatLine(String relativeName) {
			return false;
		}

		/** Hängt diese Version Aktionen an die Vanilla-Chatzeile ({@link #onVanillaLine})? */
		default boolean decoratesVanillaLine() {
			return false;
		}

		/** Gibt es hier TRS Online (Link kopieren, An Freunde senden)? */
		default boolean online() {
			return true;
		}
	}

	/** Aktionen (Toast, Chat, Editor). */
	public enum Action {
		OPEN("o"), EDIT("e"), LINK("l"), COPY("c"), FAVORITE("f"), SEND("s");

		final String code;

		Action(String code) {
			this.code = code;
		}

		static Action of(String code) {
			for (Action a : values()) if (a.code.equals(code)) return a;
			return null;
		}
	}

	/** Rückmeldung einer Aktion (Spiel-Thread). */
	public interface Feedback {
		void done(String text, boolean error);
	}

	/** Teil einer Chatzeile (die Bäume bauen daraus ihre Komponenten). */
	public static final class Part {
		public enum Kind {
			TEXT, NAME, ACTION
		}

		public final Kind kind;
		public final String text;
		/** Einfüge-Text (Marker) oder null. */
		public final String insertion;
		/** Hover-Text oder null. */
		public final String hover;

		Part(Kind kind, String text, String insertion, String hover) {
			this.kind = kind;
			this.text = text;
			this.insertion = insertion;
			this.hover = hover;
		}
	}

	public static final String MARKER = "trs-shot:";
	/** Sitzungsgeheimnis im Marker – ein Server kann so keine Aktion auf lokale Dateien auslösen. */
	private static final String SESSION = newSession();

	private static volatile Screenshots instance;

	private final Platform platform;
	private final TrsModules modules;
	private final ScreenshotStore store;
	private final ScreenshotWatcher watcher;
	private final ThreadPoolExecutor worker;
	private final Thumbnails thumbs;
	private final ConcurrentLinkedQueue<Path> fresh = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Runnable> main = new ConcurrentLinkedQueue<>();
	private final long started = System.currentTimeMillis();
	/** Dateien mit Vanilla-Chatzeile (nur Spiel-Thread). */
	private final Set<String> vanillaLines = new HashSet<>();
	/** Eigene Chatzeile fällig: Name → Zeitpunkt. */
	private final Map<String, Long> pendingLines = new LinkedHashMap<>();
	private final ScreenshotToast toast = new ScreenshotToast(this);
	private long lastEssentialCheck;
	/** Letztes Bild des Editors (ms) – offen, solange er zeichnet (robust, auch wenn ihn ein anderer Bildschirm ersetzt). */
	private volatile long editorFrameAt;
	private volatile boolean uploading;
	private volatile boolean essentialReplaced;

	private Screenshots(Platform platform, TrsModules modules) {
		this.platform = platform;
		this.modules = modules;
		Path config = platform.configDir();
		this.store = ScreenshotStore.shared(config);
		Path game = platform.gameDir();
		this.watcher = new ScreenshotWatcher(game == null ? null : game.resolve("screenshots"), started);
		this.worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(64), r -> {
			Thread t = new Thread(r, "TRS-Screenshots");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			return t;
		});
		worker.allowCoreThreadTimeOut(true);
		this.thumbs = new Thumbnails(worker, 320, 180, 6);
	}

	/**
	 * Beim Start des Mods. Startet den Ordner-Wächter (Daemon-Thread, prüft alle {@value #WATCH_INTERVAL_MS} ms nur das
	 * Änderungsdatum des Ordners). Fehler werden nie nach außen gereicht.
	 */
	public static synchronized Screenshots install(Platform platform, TrsModules modules) {
		if (instance != null) return instance;
		try {
			Screenshots s = new Screenshots(platform, modules);
			instance = s;
			s.startWatcher();
			return s;
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** Laufender Dienst oder null (nicht installiert). */
	public static Screenshots get() {
		return instance;
	}

	private void startWatcher() {
		if (watcher.dir() == null) return;
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				while (true) {
					try {
						for (Path p : watcher.poll(System.currentTimeMillis())) fresh.add(p);
						Thread.sleep(WATCH_INTERVAL_MS);
					} catch (InterruptedException e) {
						return;
					} catch (RuntimeException e) {
						// weiter beobachten
					}
				}
			}
		}, "TRS-Screenshot-Watch");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	// --- Zustand / Einstellungen ------------------------------------------------------------------

	public Platform platform() {
		return platform;
	}

	public ScreenshotStore store() {
		return store;
	}

	Thumbnails thumbs() {
		return thumbs;
	}

	ComfortModules comfort() {
		return modules == null ? null : modules.comfort;
	}

	/** Modul „Screenshot-Werkzeuge“ an? Ohne Module (Tests): an. */
	public boolean enabled() {
		ComfortModules c = comfort();
		return c == null || c.screenshots.isEnabled();
	}

	boolean toastEnabled() {
		ComfortModules c = comfort();
		return enabled() && (c == null || c.shotToast.get());
	}

	/** Aktionen in der Chatzeile? */
	public boolean chatActions() {
		ComfortModules c = comfort();
		return enabled() && (c == null || c.shotChatActions.get());
	}

	long toastMillis() {
		ComfortModules c = comfort();
		return c == null ? 5_000L : Math.round(Math.max(3, Math.min(15, c.shotSeconds.get())) * 1000);
	}

	boolean replaceEssential() {
		ComfortModules c = comfort();
		return toastEnabled() && (c == null || c.shotReplaceEssential.get());
	}

	/** Hat der TRS Client Essentials Vorschau gerade ersetzt (Selbsttest/Anzeige)? */
	public boolean essentialReplaced() {
		return essentialReplaced;
	}

	public Path screenshotsDir() {
		return watcher.dir();
	}

	/** Relativer Name unter {@code screenshots/} oder null. */
	public String relative(Path file) {
		return ScreenshotShare.relative(platform.gameDir() == null ? null : platform.gameDir().toAbsolutePath(), file);
	}

	/** Datei zu einem relativen Namen (geprüft) oder null. */
	public Path resolve(String relativeName) {
		Path game = platform.gameDir();
		return game == null ? null : ScreenshotShare.resolve(game.toAbsolutePath(), relativeName);
	}

	public boolean isFavorite(Path file) {
		String rel = relative(file);
		return rel != null && store.isFavorite(rel);
	}

	/** Ist der Editor gerade offen (dann kein Toast)? */
	public boolean editorOpen() {
		return System.currentTimeMillis() - editorFrameAt < 500L;
	}

	/** Der Editor zeichnet gerade (jedes Bild). */
	public void editorFrame() {
		editorFrameAt = System.currentTimeMillis();
	}

	/** Der Editor wurde geschlossen. */
	public void editorClosed() {
		editorFrameAt = 0;
	}

	// --- Spiel-Thread --------------------------------------------------------------------------------

	/** Client-Tick: neue Bildschirmfotos, fällige Chatzeilen, Rückmeldungen, Essential-Einstellung. */
	public void tick() {
		long now = System.currentTimeMillis();
		try {
			Runnable r;
			while ((r = main.poll()) != null) r.run();
			Path p;
			while ((p = fresh.poll()) != null) saved(p, now);
			if (!pendingLines.isEmpty()) {
				Iterator<Map.Entry<String, Long>> it = pendingLines.entrySet().iterator();
				while (it.hasNext()) {
					Map.Entry<String, Long> e = it.next();
					if (vanillaLines.contains(e.getKey())) {
						it.remove();
					} else if (now >= e.getValue()) {
						it.remove();
						if (chatActions()) platform.addChatLine(e.getKey());
					}
				}
			}
			if (now - started >= ESSENTIAL_DELAY_MS && now - lastEssentialCheck >= ESSENTIAL_CHECK_MS) {
				lastEssentialCheck = now;
				essentialReplaced = EssentialInterop.apply(replaceEssential(), store) && replaceEssential();
			}
		} catch (RuntimeException e) {
			// nie das Spiel stören
		}
	}

	/** Neues Bildschirmfoto (Wächter oder Chatzeile). */
	private void saved(Path file, long now) {
		if (!enabled()) return;
		String rel = relative(file);
		if (rel == null) return;
		if (toastEnabled() && !editorOpen()) toast.show(file, now);
		if (!vanillaLines.contains(rel) && chatActions()) pendingLines.put(rel, now + CHAT_LINE_WAIT_MS);
	}

	/**
	 * Die Vanilla-Chatzeile „Bildschirmfoto gespeichert als …“ kam an (Datei relativ zu {@code screenshots/}).
	 * Zeigt den Toast sofort, falls der Wächter die Datei noch nicht gemeldet hat. Rückgabe: Aktionen anhängen?
	 */
	public boolean onVanillaLine(String relativeName) {
		if (relativeName == null || !ScreenshotShare.valid(relativeName)) return false;
		vanillaLines.add(relativeName);
		pendingLines.remove(relativeName);
		if (relativeName.indexOf('/') < 0 && watcher.announce(relativeName)) {
			Path file = resolve(relativeName);
			if (file != null) saved(file, System.currentTimeMillis());
		}
		return chatActions();
	}

	// --- Chat ------------------------------------------------------------------------------------------

	private static String newSession() {
		byte[] b = new byte[8];
		new SecureRandom().nextBytes(b);
		StringBuilder s = new StringBuilder();
		for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
		return s.toString();
	}

	public static String marker(Action a, String relativeName) {
		return MARKER + SESSION + ":" + a.code + ":" + relativeName;
	}

	/** Relativer Dateiname aus einem Marker dieser Sitzung oder null. */
	public static String markerName(String insertion) {
		String prefix = MARKER + SESSION + ":";
		if (insertion == null || !insertion.startsWith(prefix) || insertion.length() < prefix.length() + 3) return null;
		String rest = insertion.substring(prefix.length());
		if (rest.charAt(1) != ':' || Action.of(rest.substring(0, 1)) == null) return null;
		String name = rest.substring(2);
		return ScreenshotShare.valid(name) ? name : null;
	}

	static Action markerAction(String insertion) {
		String name = markerName(insertion);
		if (name == null) return null;
		return Action.of(insertion.substring(MARKER.length() + SESSION.length() + 1, MARKER.length() + SESSION.length() + 2));
	}

	/** Klick auf einen Chat-Stil mit unserem Marker: Aktion ausführen. true = verbraucht. */
	public boolean onMarker(String insertion) {
		String name = markerName(insertion);
		Action a = markerAction(insertion);
		if (name == null || a == null) return false;
		Path file = resolve(name);
		if (file == null) {
			platform.notice(I18n.tr("screenshots.gone"));
			return true;
		}
		run(a, file, null);
		return true;
	}

	/** Aktionen hinter der Vanilla-Zeile (beginnt mit einem Leerzeichen). */
	public List<Part> actionParts(String relativeName) {
		List<Part> out = new ArrayList<>();
		out.add(new Part(Part.Kind.TEXT, " ", null, null));
		action(out, Action.EDIT, "screenshots.chat.edit", "screenshots.chat.editHover", relativeName);
		if (platform.online()) {
			out.add(new Part(Part.Kind.TEXT, " ", null, null));
			action(out, Action.LINK, "screenshots.chat.link", "screenshots.chat.linkHover", relativeName);
		}
		out.add(new Part(Part.Kind.TEXT, " ", null, null));
		action(out, Action.COPY, "screenshots.chat.copy", "screenshots.chat.copyHover", relativeName);
		return out;
	}

	private static void action(List<Part> out, Action a, String label, String hover, String rel) {
		out.add(new Part(Part.Kind.ACTION, I18n.tr(label), marker(a, rel), I18n.tr(hover)));
	}

	/** Eigene Chatzeile: „Bildschirmfoto gespeichert: &lt;name&gt; [Bearbeiten] [Link kopieren] [Bild kopieren]“. */
	public List<Part> chatLine(String relativeName) {
		List<Part> out = new ArrayList<>();
		out.add(new Part(Part.Kind.TEXT, I18n.tr("screenshots.chat.saved") + " ", null, null));
		out.add(new Part(Part.Kind.NAME, relativeName, marker(Action.OPEN, relativeName), I18n.tr("screenshots.chat.openHover")));
		out.addAll(actionParts(relativeName));
		return out;
	}

	/**
	 * Kurze Zeile nur mit den Aktionen („» [Bearbeiten] [Link kopieren] [Bild kopieren]“) – für Versionen, in denen die
	 * Vanilla-Zeile schon im Chat steht, aber nicht erweitert werden kann (Legacy ohne Essential).
	 */
	public List<Part> compactLine(String relativeName) {
		List<Part> out = new ArrayList<>();
		out.add(new Part(Part.Kind.TEXT, "»", null, null));
		out.addAll(actionParts(relativeName));
		return out;
	}

	// --- Aktionen ------------------------------------------------------------------------------------

	/** Aktion ausführen; {@code feedback} null = Meldung in der Aktionsleiste. Spiel-Thread. */
	public void run(Action a, final Path file, Feedback feedback) {
		final Feedback fb = feedback != null ? feedback : new Feedback() {
			@Override
			public void done(String text, boolean error) {
				platform.notice(text);
			}
		};
		if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			fb.done(I18n.tr("screenshots.gone"), true);
			return;
		}
		switch (a) {
			case OPEN:
				if (!OpenPath.open(file.toAbsolutePath())) fb.done(I18n.tr("clips.openFailed"), true);
				return;
			case EDIT:
				edit(file);
				return;
			case FAVORITE: {
				String rel = relative(file);
				if (rel == null) return;
				boolean now = store.toggleFavorite(rel);
				fb.done(I18n.tr(now ? "screenshots.favorite.added" : "screenshots.favorite.removed"), false);
				return;
			}
			case COPY:
				copyPicture(file, fb);
				return;
			case LINK:
				copyLink(file, fb);
				return;
			case SEND:
				send(file, fb);
				return;
			default:
		}
	}

	/** Editor öffnen. */
	public boolean edit(Path file) {
		toast.hide();
		return platform.openUi(I18n.tr("screenshots.editor.title"), new ScreenshotEditorUi(this, file));
	}

	/** Bild in die Zwischenablage (im Hintergrund). */
	public void copyPicture(final Path file, final Feedback fb) {
		fb.done(I18n.tr("screenshots.copy.busy"), false);
		execute(new Runnable() {
			@Override
			public void run() {
				final ImageClipboard.Result r = ImageClipboard.copy(file);
				main.add(new Runnable() {
					@Override
					public void run() {
						fb.done(I18n.tr(r == ImageClipboard.Result.OK ? "screenshots.copy.done"
								: r == ImageClipboard.Result.UNSUPPORTED ? "screenshots.copy.unsupported" : "screenshots.copy.failed"),
								r != ImageClipboard.Result.OK);
					}
				});
			}
		}, fb);
	}

	/** Als Link teilen (§23) und den Link kopieren. */
	public void copyLink(Path file, final Feedback fb) {
		if (!platform.online()) {
			fb.done(I18n.tr("screenshots.unsupported"), true);
			return;
		}
		if (uploading) {
			fb.done(I18n.tr("chat.screenshot.uploading"), false);
			return;
		}
		final Social s = ScreenshotShare.social();
		if (s == null) {
			fb.done(I18n.tr("clips.share.offline"), true);
			return;
		}
		uploading = true;
		fb.done(I18n.tr("chat.screenshot.uploading"), false);
		boolean started = ScreenshotShare.share(file, (value, error) -> {
			uploading = false;
			if (value == null) {
				fb.done(I18n.tr(error == null ? "social.error.generic" : error), true);
				return;
			}
			boolean copied = platform.copyText(value.url);
			fb.done(ScreenshotShare.copiedText(value, copied), false);
		});
		if (!started) {
			uploading = false;
			fb.done(I18n.tr("clips.share.offline"), true);
		}
	}

	/** Zielauswahl im Sozial-Bildschirm (Freunde/Unterhaltungen, mehrere möglich). */
	public void send(Path file, Feedback fb) {
		if (!platform.online()) {
			fb.done(I18n.tr("screenshots.unsupported"), true);
			return;
		}
		if (ScreenshotShare.social() == null) {
			fb.done(I18n.tr("clips.share.offline"), true);
			return;
		}
		SocialPlatform sp = SocialOverlay.platform();
		ScreenshotShare.requestSend(file);
		toast.hide();
		if (sp == null || !sp.openSocial()) {
			ScreenshotShare.takePendingImages();
			fb.done(I18n.tr("screenshots.unsupported"), true);
		}
	}

	/** Im Hintergrund ausführen; volle Warteschlange = Fehlermeldung. */
	void execute(Runnable r, Feedback fb) {
		try {
			worker.execute(r);
		} catch (RuntimeException e) {
			if (fb != null) fb.done(I18n.tr("screenshots.busy"), true);
		}
	}

	/** Ergebnis aus dem Hintergrund in den Spiel-Thread (nächster Tick). */
	public void later(Runnable r) {
		main.add(r);
	}

	/** Hintergrund-Thread des Dienstes (Editor: Laden, Rendern, Speichern). */
	public void background(Runnable r) {
		execute(r, null);
	}

	// --- Zeichnen / Maus -----------------------------------------------------------------------------

	/** Etwas zu zeichnen (Toast oder Chat-Vorschau)? Billig. */
	public boolean active() {
		return toast.visible() || toast.chatHoverPending();
	}

	/**
	 * Toast (und Chat-Vorschau) zeichnen. {@code mouseFree} = ein Bildschirm ist offen, der Zeiger bewegt sich frei
	 * (nur dann reagiert der Toast auf die Maus). Render-Thread; nie Ausnahmen nach außen.
	 */
	public void render(Canvas c, int width, int height, int mouseX, int mouseY, boolean mouseFree) {
		try {
			thumbs.frame();
			toast.render(c, width, height, mouseX, mouseY, mouseFree && !editorOpen());
		} catch (RuntimeException e) {
			// nie das Spiel stören
		}
	}

	/** Mausklick (GUI-Koordinaten) mit offenem Bildschirm; true = der Toast hat ihn verbraucht. */
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		try {
			return !editorOpen() && toast.click(mouseX, mouseY, button);
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * Maus über einer Chatzeile (Chat offen): Text bzw. Einfüge-Text der Zeile. Enthält er ein Bildschirmfoto dieser
	 * Sitzung (Marker) oder einen Vanilla-Namen, zeigt {@link #render} im selben Bild eine Vorschau am Zeiger.
	 */
	public void chatHover(String lineTextOrInsertion, int mouseX, int mouseY) {
		if (lineTextOrInsertion == null || !enabled()) return;
		String name = markerName(lineTextOrInsertion);
		if (name == null) name = ScreenshotNames.find(lineTextOrInsertion);
		if (name == null) return;
		Path file = resolve(name);
		if (file != null) toast.chatHover(file, mouseX, mouseY);
	}

	/** Für Selbsttests: Toast für eine Datei zeigen, als wäre sie gerade aufgenommen worden. */
	public void testShow(Path file) {
		toast.show(file, System.currentTimeMillis());
	}

	/** Für Selbsttests: Toast unter dem Zeiger „überfahren“ lassen (Knöpfe zeigen). */
	public ScreenshotToast toast() {
		return toast;
	}
}
