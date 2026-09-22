package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.pvp.ComboTracker;
import dev.theredstonee.trsclient.core.pvp.ReachTracker;
import dev.theredstonee.trsclient.core.pvp.SpeedTracker;

/** HUD-Anzeigen fürs PvP: Reichweite des letzten Treffers, Combo und Geschwindigkeit. */
public final class PvpHuds {
	private PvpHuds() {
	}

	/** Entfernung des letzten Treffers. */
	public static final class Reach extends LinesHudElement {
		private final TrsModules modules;

		public Reach(HudModule module, TrsModules modules) {
			super(module);
			this.modules = modules;
		}

		@Override
		protected void build(boolean preview) {
			ReachTracker reach = TrsClient.get().pvp().reach();
			int decimals = modules.reachDecimals.getInt();
			long hold = (long) (modules.reachHold.get() * 1000);
			if (preview && !reach.valid(System.currentTimeMillis(), hold)) {
				line("3.04 m");
				return;
			}
			if (reach.valid(System.currentTimeMillis(), hold)) {
				line(ReachTracker.format(reach.distance(), decimals));
			}
		}
	}

	/** Treffer in Folge. */
	public static final class Combo extends LinesHudElement {
		private final TrsModules modules;

		public Combo(HudModule module, TrsModules modules) {
			super(module);
			this.modules = modules;
		}

		@Override
		protected void build(boolean preview) {
			ComboTracker combo = TrsClient.get().pvp().combo();
			int count = combo.combo();
			if (preview && count == 0) count = 3;
			if (count <= 0) return;
			String text = count + "er Combo";
			if (modules.comboBest.get() && combo.best() > 0) text += "  (best " + combo.best() + ")";
			line(text);
		}
	}

	/** Geschwindigkeit in Blöcken pro Sekunde. */
	public static final class Speed extends LinesHudElement {
		public Speed(HudModule module) {
			super(module);
		}

		@Override
		protected void build(boolean preview) {
			double bps = TrsClient.get().pvp().speed().blocksPerSecond();
			line(SpeedTracker.format(preview && bps == 0 ? 4.32 : bps));
		}
	}
}
