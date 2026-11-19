package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.i18n.I18n;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.format.HudFormat;
import dev.theredstonee.trsclient.core.input.ToggleStatus;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.entity.player.Player;

import java.time.LocalTime;

/** Die einfachen Text-HUD-Module (Koordinaten, Uhrzeit, Speicher, Server, Packs, Toggle-Anzeige). */
public final class InfoHuds {
	private InfoHuds() {
	}

	/** Koordinaten, Blickrichtung und Biom. */
	public static final class Coords extends LinesHudElement {
		private final TrsModules modules;

		public Coords(HudModule module, TrsModules modules) {
			super(module);
			this.modules = modules;
		}

		@Override
		protected void build(boolean preview) {
			Player p = mc.player;
			if (p == null) {
				if (preview) line(HudFormat.coords(128, 64, -256));
				return;
			}
			line(HudFormat.coords(Mc.x(p), Mc.y(p), Mc.z(p)));
			if (modules.coordsDirection.get()) {
				float yaw = Mc.yRot(p);
				line(I18n.tr("hud.coords.direction", HudFormat.directionName(yaw), HudFormat.direction(yaw)));
			}
			if (modules.coordsBiome.get() && mc.level != null) {
				line(I18n.tr("hud.coords.biome", Mc.biomeName(mc.level, Mc.blockPos(p))));
			}
		}
	}

	/** Echte Uhrzeit. */
	public static final class Clock extends LinesHudElement {
		private final TrsModules modules;

		public Clock(HudModule module, TrsModules modules) {
			super(module);
			this.modules = modules;
		}

		@Override
		protected void build(boolean preview) {
			line(HudFormat.clock(LocalTime.now(), modules.clockSeconds.get(), modules.clockTwelveHour.get()));
		}
	}

	/** Belegter Arbeitsspeicher der JVM. */
	public static final class Memory extends LinesHudElement {
		public Memory(HudModule module) {
			super(module);
		}

		@Override
		protected void build(boolean preview) {
			Runtime rt = Runtime.getRuntime();
			line("RAM " + HudFormat.memory(rt.totalMemory() - rt.freeMemory(), rt.maxMemory()));
		}
	}

	/** Adresse des Servers (nicht im Einzelspieler; respektiert "Serveradresse ausblenden"). */
	public static final class Server extends LinesHudElement {
		public Server(HudModule module) {
			super(module);
		}

		@Override
		protected void build(boolean preview) {
			ServerData data = mc.getSingleplayerServer() == null ? mc.getCurrentServer() : null;
			if (data != null) {
				line(dev.theredstonee.trsclient.qol.QolHooks.maskAddress(mc.options.hideServerAddress ? data.name : data.ip));
			} else if (preview) {
				line("play.example.net");
			}
		}
	}

	/** Eingeschaltete Resourcepacks (ohne Pflicht-Packs wie "Standard"), höchste Priorität oben. */
	public static final class Packs extends LinesHudElement {
		public Packs(HudModule module) {
			super(module);
		}

		@Override
		protected void build(boolean preview) {
			java.util.List<String> titles = Mc.selectedPackTitles();
			for (String title : titles) line(title);
			if (preview && titles.isEmpty()) line(I18n.tr("hud.packs.none"));
		}
	}

	/** Anzeige für Toggle-Sprint bzw. Toggle-Schleichen, solange umgeschaltet. */
	public static final class ToggleIndicator extends LinesHudElement {
		private final boolean sprint;

		/** Anzeige von Toggle-Sprint ({@code sprint}) bzw. Toggle-Schleichen, z. B. „[Sprinten (umgeschaltet)]“. */
		public ToggleIndicator(HudModule module, boolean sprint) {
			super(module);
			this.sprint = sprint;
		}

		@Override
		protected void build(boolean preview) {
			ToggleStatus status = TrsClient.get().pvp().toggles().status(sprint, preview);
			if (status != ToggleStatus.NONE) line(status.text());
		}
	}
}
