package dev.theredstonee.trsclient.core.hosting;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.hosting.HostingUi;

/**
 * Einstieg der Versionsbäume für die Hinweise des Welt-Hostings: das rote Abzeichen „Öffentlicher Link aktiv“ im HUD
 * und im Pausemenü, Beschriftung des Pausemenü-Knopfs, Verfügbarkeit. Kosten ohne Hosting: ein Feldzugriff.
 */
public final class HostingOverlay {
	public static final int BADGE_H = 14;

	private HostingOverlay() {
	}

	/** Ist der öffentliche Link an (dann Abzeichen zeigen)? */
	public static boolean linkActive() {
		Hosting h = Hosting.current();
		return h != null && h.publicLink().active();
	}

	/** Öffentlichen Link ausschalten (Knopf „Deaktivieren“ im Pausemenü). */
	public static void disableLink() {
		Hosting h = Hosting.current();
		if (h != null) h.publicLink().stop();
	}

	/** Knopf „Welt hosten“ zeigen? (Einzelspielerwelt, Online-Funktionen vorhanden.) */
	public static boolean hostButtonVisible() {
		Hosting h = Hosting.current();
		HostingPlatform p = Hosting.platform();
		if (h == null || p == null) return false;
		try {
			return p.canHost();
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** Beschriftung des Pausemenü-Knopfs: „Welt hosten“ bzw. „Hosting verwalten“. */
	public static String hostButtonLabel() {
		Hosting h = Hosting.current();
		boolean open = h != null && h.hostState() == Hosting.HostState.OPEN;
		return I18n.tr(open ? "hosting.pause.manage" : "hosting.pause.host");
	}

	/** Breite des Abzeichens für diese Beschriftung. */
	public static int badgeWidth(Canvas c) {
		return c.textWidth(I18n.tr("hosting.link.active")) + 16;
	}

	/** Abzeichen oben mittig (HUD). Render-Thread; nie Ausnahmen nach außen. */
	public static void renderHud(Canvas c, int width, int height) {
		if (!linkActive()) return;
		try {
			int w = badgeWidth(c);
			HostingUi.badge(c, (width - w) / 2, 2, w, BADGE_H, I18n.tr("hosting.link.active"));
		} catch (RuntimeException ignored) {
			// Hinweis darf das Spiel nie stören
		}
	}

	/** Abzeichen an fester Stelle (Pausemenü, über dem Deaktivieren-Knopf). */
	public static void renderAt(Canvas c, int x, int y) {
		if (!linkActive()) return;
		try {
			HostingUi.badge(c, x, y, badgeWidth(c), BADGE_H, I18n.tr("hosting.link.active"));
		} catch (RuntimeException ignored) {
			// egal
		}
	}
}
