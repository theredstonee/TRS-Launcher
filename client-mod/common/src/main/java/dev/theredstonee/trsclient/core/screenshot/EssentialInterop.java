package dev.theredstonee.trsclient.core.screenshot;

import java.lang.reflect.Method;

/**
 * Zusammenspiel mit der Mod Essential (geschlossener Quelltext – hier werden nur öffentliche Namen benutzt, kein Code
 * übernommen).
 *
 * <p><b>Wie Essential Bildschirmfotos abfängt</b> (per {@code javap} an Essential 1.5 für Fabric 1.21.11 und Forge
 * 1.12.2 nachgesehen): Ein Mixin leitet in {@code KeyboardHandler#keyPress} den Aufruf von
 * {@code Screenshot.grab(…)} auf den eigenen {@code ScreenshotManager.handleScreenshotKeyPressed()} um. Der ruft
 * wieder das Vanilla-{@code Screenshot.grab} auf, aber mit eigenem Rückruf: die Vanilla-Chatzeile erscheint nur, wenn
 * Essentials Einstellung „Screenshot message“ an ist (ab Werk aus – daher die „geklaute“ Chatzeile). Ein zweites Mixin
 * hängt hinter {@code NativeImage.writeTo(File)} und zeigt Essentials Vorschau-Toast, wenn
 * {@code EssentialConfig.getEssentialScreenshots()} (Einstellung „Screenshot preview“, Datei
 * {@code essential/config.toml}, {@code essential_screenshots}) wahr ist.
 *
 * <p><b>Unser Weg:</b> Die Datei entsteht in jedem Fall normal unter {@code screenshots/}; unser
 * {@link ScreenshotWatcher} findet sie, zeigt den TRS-Toast und – wenn keine Vanilla-Zeile kam – eine eigene Chatzeile.
 * Ist „Essential-Popup ersetzen“ an, schaltet der TRS Client Essentials Einstellung „Screenshot preview“ über die
 * öffentliche Konfigurations-Klasse aus (per Reflection, im Spiel-Thread) und merkt sich das in
 * {@code screenshots.json}. Wird unsere Einstellung abgeschaltet, stellt er sie wieder an – aber nur, wenn er sie selbst
 * ausgeschaltet hatte. Schlägt irgendetwas fehl (andere Essential-Version, Klassen nicht sichtbar), passiert nichts:
 * dann erscheinen eben beide Popups.
 */
public final class EssentialInterop {
	static final String CONFIG_CLASS = "gg.essential.config.EssentialConfig";

	private static volatile boolean looked;
	private static volatile Handle handle;

	private EssentialInterop() {
	}

	/** Reflektierter Zugriff auf {@code EssentialConfig.INSTANCE}. */
	static final class Handle {
		final Object instance;
		final Method get;
		final Method set;

		Handle(Object instance, Method get, Method set) {
			this.instance = instance;
			this.get = get;
			this.set = set;
		}

		boolean preview() throws Exception {
			return (Boolean) get.invoke(instance);
		}

		void preview(boolean on) throws Exception {
			set.invoke(instance, on);
		}
	}

	/** Ist Essential geladen (Klasse sichtbar)? Sucht nur einmal. */
	public static boolean present() {
		return handle() != null;
	}

	private static Handle handle() {
		if (looked) return handle;
		synchronized (EssentialInterop.class) {
			if (looked) return handle;
			handle = find();
			looked = true;
			return handle;
		}
	}

	private static Handle find() {
		ClassLoader[] loaders = {EssentialInterop.class.getClassLoader(), Thread.currentThread().getContextClassLoader()};
		for (ClassLoader cl : loaders) {
			if (cl == null) continue;
			try {
				Class<?> cls = Class.forName(CONFIG_CLASS, true, cl);
				Object instance = cls.getField("INSTANCE").get(null);
				Method get = cls.getMethod("getEssentialScreenshots");
				Method set = cls.getMethod("setEssentialScreenshots", boolean.class);
				if (instance == null || get.getReturnType() != boolean.class) continue;
				return new Handle(instance, get, set);
			} catch (Throwable ignored) {
				// nicht da oder andere Version
			}
		}
		return null;
	}

	/**
	 * Einstellung durchsetzen (Spiel-Thread, gelegentlich aufrufen): {@code replace} = Essentials Vorschau aus.
	 * Rückgabe: true, wenn Essential da ist und der gewünschte Zustand gilt.
	 */
	public static boolean apply(boolean replace, ScreenshotStore store) {
		Handle h = handle();
		if (h == null || store == null) return false;
		return apply(h, replace, store);
	}

	static boolean apply(Handle h, boolean replace, ScreenshotStore store) {
		try {
			if (replace) {
				if (h.preview()) {
					h.preview(false);
					store.essentialPreviewOff(true);
				}
				return !h.preview();
			}
			if (store.essentialPreviewOff()) {
				h.preview(true);
				store.essentialPreviewOff(false);
			}
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/** Nur für Tests: eigenen Zugriff einsetzen. */
	static void testHandle(Handle h) {
		handle = h;
		looked = true;
	}
}
