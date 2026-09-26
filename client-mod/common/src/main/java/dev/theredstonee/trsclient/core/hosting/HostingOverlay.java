package dev.theredstonee.trsclient.core.hosting;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.hosting.HostingUi;

/**
 * Einstieg der Versionsbäume für die Hinweise des Welt-Hostings: dezenter Hinweis „Öffentlicher Link aktiv“ im
 * Pausemenü (im Spiel-HUD bewusst keiner – Wunsch des Users 2026-09-26), Beschriftung des Pausemenü-Knopfs,
 * Verfügbarkeit. Kosten ohne Hosting: ein Feldzugriff.
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

	/** Breite des Hinweises für diese Beschriftung. */
	public static int badgeWidth(Canvas c) {
		return c.textWidth(I18n.tr("hosting.link.active")) + 22;
	}

	/**
	 * Früher das rote Abzeichen oben mittig im Spiel. Bleibt als Einstieg für die Versionsbäume, zeichnet aber nichts
	 * mehr: Im Spiel stört es nur; der Hinweis steht im Pausemenü und im Hosting-Fenster.
	 */
	public static void renderHud(Canvas c, int width, int height) {
		// bewusst leer
	}

	/** Dezenter Hinweis an fester Stelle (Pausemenü, über dem Deaktivieren-Knopf). */
	public static void renderAt(Canvas c, int x, int y) {
		if (!linkActive()) return;
		try {
			HostingUi.linkHint(c, x, y, badgeWidth(c), BADGE_H, I18n.tr("hosting.link.active"));
		} catch (RuntimeException ignored) {
			// egal
		}
	}
}
