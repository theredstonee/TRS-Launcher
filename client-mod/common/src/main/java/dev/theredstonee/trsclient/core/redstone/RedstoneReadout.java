package dev.theredstonee.trsclient.core.redstone;

import dev.theredstonee.trsclient.core.i18n.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * Was die Signalstärke-Anzeige für den angeschauten Block zeigt: Name, Balkenwert (0–15) mit
 * Beschriftung und Detailzeilen (Verzögerung, Modus, Zustand, Komparator-Ausgabe).
 * Wird nur neu gebaut, wenn sich der Block oder sein Zustand ändert.
 */
public final class RedstoneReadout {
	/** Etwas anzuzeigen? */
	public boolean valid;
	public String name = "";
	public RedstoneKind kind = RedstoneKind.NONE;
	/** Wert des Balkens 0–15 oder -1 (unbekannt). */
	public int signal = -1;
	/** Beschriftung des Balkens (Signal / Ausgang / Eingang / Komparator). */
	public String signalLabel = "";
	public final List<String> details = new ArrayList<String>();

	/**
	 * Baut die Anzeige aus dem Blockzustand.
	 *
	 * @param output           berechnete Komparator-Ausgabe (nur für Komparatoren) oder -1
	 * @param container        Komparator-Ausgabe des Behälters aus dem Inhalt oder -1
	 * @param containerCurrent der Behälter ist gerade offen (sonst: Wert vom letzten Öffnen)
	 */
	public void describe(String name, BlockProbe p, int output, int container, boolean containerCurrent) {
		details.clear();
		this.name = name == null ? "" : name;
		kind = p.kind;
		valid = p.kind != RedstoneKind.NONE;
		signalLabel = I18n.tr("hud.redstone.signal");
		switch (p.kind) {
			case DUST:
				signal = Math.max(0, p.power);
				break;
			case REPEATER:
				signal = on(p) ? 15 : 0;
				details.add(p.delay == 1 ? I18n.tr("hud.redstone.delay1") : I18n.tr("hud.redstone.delay", p.delay));
				if (p.locked) details.add(I18n.tr("hud.redstone.locked"));
				break;
			case COMPARATOR:
				signal = output >= 0 ? output : (on(p) ? -1 : 0);
				signalLabel = I18n.tr("hud.redstone.output");
				details.add(I18n.tr("hud.redstone.mode",
						I18n.tr(p.subtract ? "hud.redstone.mode.subtract" : "hud.redstone.mode.compare")));
				break;
			case TORCH:
			case LEVER:
			case BUTTON:
			case OBSERVER:
				signal = on(p) ? 15 : 0;
				break;
			case PLATE:
			case DAYLIGHT:
			case SOURCE:
				signal = p.power >= 0 ? p.power : (on(p) ? 15 : 0);
				break;
			case REDSTONE_BLOCK:
				signal = 15;
				break;
			case PISTON:
				signal = p.received;
				signalLabel = I18n.tr("hud.redstone.input");
				details.add(I18n.tr(p.extended ? "hud.redstone.extended" : "hud.redstone.retracted"));
				break;
			case LAMP:
				signal = p.received;
				signalLabel = I18n.tr("hud.redstone.input");
				details.add(I18n.tr(on(p) ? "hud.redstone.on" : "hud.redstone.off"));
				break;
			case CONSUMER:
				signal = p.received;
				signalLabel = I18n.tr("hud.redstone.input");
				if (p.hasPowered) details.add(I18n.tr(p.powered ? "hud.redstone.powered" : "hud.redstone.unpowered"));
				break;
			case CONTAINER:
				signal = container;
				signalLabel = I18n.tr("hud.redstone.comparatorShort");
				// Inhalt unbekannt → nichts zu zeigen (eine Truhe ist kein Bauteil).
				valid = container >= 0;
				if (valid && !containerCurrent) details.add(I18n.tr("hud.redstone.lastOpened"));
				break;
			case ANALOG:
				signal = p.analog;
				signalLabel = I18n.tr("hud.redstone.comparatorShort");
				valid = p.analog >= 0;
				break;
			default:
				signal = -1;
				valid = false;
		}
		// Verbraucher, die zugleich Behälter sind (Trichter, Werfer): Komparator-Ausgabe dazu.
		if (valid && p.container && p.kind != RedstoneKind.CONTAINER && container >= 0) {
			details.add(containerCurrent ? I18n.tr("hud.redstone.comparator", container)
					: I18n.tr("hud.redstone.comparatorSeen", container));
		}
	}

	/** Beispielwerte für die Vorschau im HUD-Editor. */
	public static RedstoneReadout preview() {
		RedstoneReadout r = new RedstoneReadout();
		BlockProbe p = new BlockProbe();
		p.kind = RedstoneKind.COMPARATOR;
		p.facing = Dir.NORTH;
		p.subtract = true;
		p.powered(true);
		r.describe(I18n.tr("hud.redstone.preview.comparator"), p, 11, -1, false);
		return r;
	}

	/** Aktivität für den Takt-Messer: Signal bzw. an = 15, aus = 0. */
	public static int activity(BlockProbe p, int output) {
		switch (p.kind) {
			case DUST:
				return Math.max(0, p.power);
			case PLATE:
			case DAYLIGHT:
			case SOURCE:
				return p.power >= 0 ? p.power : (on(p) ? 15 : 0);
			case COMPARATOR:
				return output >= 0 ? output : (on(p) ? 15 : 0);
			case PISTON:
				return p.extended ? 15 : 0;
			case REDSTONE_BLOCK:
				return 15;
			case ANALOG:
				return Math.max(0, p.analog);
			case CONSUMER:
				if (p.hasPowered) return p.powered ? 15 : 0;
				return Math.max(0, p.received);
			default:
				return on(p) ? 15 : 0;
		}
	}

	private static boolean on(BlockProbe p) {
		return p.hasPowered && p.powered;
	}
}
