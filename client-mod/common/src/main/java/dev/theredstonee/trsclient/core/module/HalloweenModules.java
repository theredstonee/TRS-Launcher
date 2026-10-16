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
	/** Grusel-Töne (ab Werk aus – ohne sie bleibt alles stumm). */
	public final BoolSetting sounds;
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
		trail = halloween.add(new BoolSetting("trail", "Ghost trail when sprinting", true));
		others = halloween.add(new BoolSetting("others", "See other players' spooky effects", false));
		jumpscares = halloween.add(new BoolSetting("jumpscares", "Jumpscares", true));
		sounds = halloween.add(new BoolSetting("sounds", "Spooky sounds", false));
		registry.addPart(local);
	}

	/** Halloween-Look und -Funktionen gerade an? (Event aktiv und Modul nicht ausgeschaltet.) */
	public boolean on() {
		return Halloween.active() && halloween.isEnabled();
	}

	/** Filmkorn + Blitz (Einstellung „Flackern &amp; Rauschen“). */
	public boolean flicker() {
		return local.flicker();
	}
}
