package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.halloween.Halloween;
import dev.theredstonee.trsclient.core.halloween.HalloweenLocal;

/**
 * Halloween 2026 „Nacht der langen Schatten“ (TRS Client {@link NewSince#HALLOWEEN}): Schalter für Spuk-Effekte,
 * Jumpscares, Grusel-Töne und „Flackern &amp; Rauschen“. Das Modul erscheint nur, solange das Event läuft
 * ({@link Halloween#active()}); die Seite „Halloween“ im TRS-Menü zeigt dieselben Schalter mit Kürbis-Suche und
 * „Süßes oder Saures“.
 *
 * <p>Alles reine Optik – Fair Play bleibt unberührt.
 */
public final class HalloweenModules {
	public final Module halloween;
	/** Fledermäuse bei eigenen Treffern. */
	public final BoolSetting hits;
	/** Geister-Spur beim Sprinten. */
	public final BoolSetting trail;
	/** Spuk-Effekte anderer TRS-Spieler sehen (ab Werk aus). */
	public final BoolSetting others;
	/** Jumpscares (ab Werk an; Schutzregeln in {@code core.halloween.Jumpscares}). */
	public final BoolSetting jumpscares;
	/** Grusel-Töne (ab Werk an – Jumpscares bekommen jetzt standardmäßig einen Ton). */
	public final BoolSetting sounds;
	/**
	 * „Event-Design anzeigen“ (ab Werk an): aus = kein Halloween-Look, keine Spuk-Effekte, Jumpscares oder Spuk-Töne.
	 * Kürbis-Suche, Süßes oder Saures, Sticker und Belohnungen bleiben.
	 */
	public final BoolSetting eventLook;
	/** Lokaler Zustand: „Flackern &amp; Rauschen“ (Launcher-Standard), gesehene Hinweise. */
	public final HalloweenLocal local = new HalloweenLocal();

	HalloweenModules(ModuleRegistry registry) {
		halloween = registry.register(new Module("halloween", "Halloween",
				"Night of the Long Shadows: pumpkin hunt, trick or treat, spooky effects and jumpscares – only during the "
						+ "Halloween event. Pure visuals, Fair Play stays fair.",
				true)).category(Category.MISC).icon("pumpkin");
		halloween.availableWhen(new java.util.concurrent.Callable<Boolean>() {
			@Override
			public Boolean call() {
				return Halloween.active();
			}
		});
		hits = halloween.add(new BoolSetting("hits", "Spooky hits (bats on hits)", true));
		// Ab Werk aus (Spieler-Feedback 0.19.0: „Striche beim Laufen nerven“); neuer Schlüssel, damit alte „an“-Werte nicht greifen.
		trail = halloween.add(new BoolSetting("ghostTrail", "Ghost trail when sprinting", false));
		others = halloween.add(new BoolSetting("others", "See other players' spooky effects", false));
		jumpscares = halloween.add(new BoolSetting("jumpscares", "Jumpscares", true));
		// Ab Werk an (0.19.2): neuer Schlüssel, damit alte „aus“-Werte nicht greifen – wer nie umgeschaltet hat,
		// bekommt den neuen Standard „an“ (wie seinerzeit die Umbenennung von „trail“ → „ghostTrail“).
		sounds = halloween.add(new BoolSetting("spookySounds", "Spooky sounds", true));
		eventLook = halloween.add(new BoolSetting("eventLook", "Show event design", true));
		final BoolSetting look = eventLook;
		Halloween.ownLook(new java.util.function.BooleanSupplier() {
			@Override
			public boolean getAsBoolean() {
				// Die Module des laufenden Spiels zählen (weitere Instanzen, z. B. Benchmark-Standardwerte, nicht).
				TrsModules game = dev.theredstonee.trsclient.core.intro.IntroGate.modules();
				return game != null ? game.halloween.eventLook.get() : look.get();
			}
		});
		registry.addPart(local);
	}

	/**
	 * Spuk-Effekte, Jumpscares und Spuk-Töne gerade an? (Event-Look an – siehe {@link Halloween#lookActive()} – und Modul
	 * nicht ausgeschaltet.) Die Funktionen des Events (Kürbis-Suche …) hängen nur an {@link Halloween#active()}.
	 */
	public boolean on() {
		return Halloween.lookActive() && halloween.isEnabled();
	}

	/** Filmkorn + Blitz (Einstellung „Flackern &amp; Rauschen“). */
	public boolean flicker() {
		return local.flicker();
	}
}
