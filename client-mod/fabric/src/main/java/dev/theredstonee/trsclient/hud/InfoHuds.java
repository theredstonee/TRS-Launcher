package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.i18n.I18n;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.format.HudFormat;
import dev.theredstonee.trsclient.core.input.ToggleState;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;

import java.time.LocalTime;

/** Die einfachen Text-HUD-Module (Effekte, Koordinaten, Uhrzeit, Speicher, Server, Packs, Toggle-Anzeige). */
public final class InfoHuds {
	private InfoHuds() {
	}

	/** Trank-Effekte mit Stufe und Restzeit, in der Farbe des Effekts. */
	public static final class Effects extends LinesHudElement {
		public Effects(HudModule module) {
			super(module);
		}

		@Override
		protected void build(boolean preview) {
			Player p = mc.player;
			int count = 0;
			if (p != null) {
				for (MobEffectInstance e : p.getActiveEffects()) {
					MobEffect effect = Mc.effect(e);
					String level = HudFormat.level(e.getAmplifier());
					String name = effect.getDisplayName().getString() + (level.isEmpty() ? "" : " " + level);
					line(name + "  " + HudFormat.duration(e.getDuration(), Mc.infinite(e)), 0xFF000000 | effect.getColor());
					count++;
				}
			}
			if (preview && count == 0) {
				line(I18n.tr("hud.preview.effect1"), 0xFF33EBFF);
				line(I18n.tr("hud.preview.effect2"), 0xFFFFC700);
			}
		}
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
				line(I18n.tr("hud.coords.biome", Mc.biomeName(p)));
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
				line(mc.options.hideServerAddress ? data.name : data.ip);
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
			java.util.List<String> titles = dev.theredstonee.trsclient.compat.Packs.activeTitles();
			for (String title : titles) line(title);
			if (preview && titles.isEmpty()) line(I18n.tr("hud.packs.none"));
		}
	}

	/** Anzeige für Toggle-Sprint bzw. Toggle-Schleichen, solange umgeschaltet. */
	public static final class ToggleIndicator extends LinesHudElement {
		private final String label;
		private final boolean sprint;

		public ToggleIndicator(HudModule module, String label, boolean sprint) {
			super(module);
			this.label = label;
			this.sprint = sprint;
		}

		@Override
		protected void build(boolean preview) {
			ToggleState state = sprint ? TrsClient.get().sprintToggle() : TrsClient.get().sneakToggle();
			if (preview || state.active()) line("[" + I18n.tr("hud.toggled", I18n.tr(label)) + "]");
		}
	}
}
