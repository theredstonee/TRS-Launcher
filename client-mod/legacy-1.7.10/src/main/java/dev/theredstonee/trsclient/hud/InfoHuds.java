package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.format.HudFormat;
import dev.theredstonee.trsclient.core.input.ToggleState;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.MathHelper;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

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
			EntityPlayer p = mc.thePlayer;
			int count = 0;
			if (p != null) {
				@SuppressWarnings("unchecked")
				Collection<PotionEffect> effects = p.getActivePotionEffects();
				for (PotionEffect e : effects) {
					int id = e.getPotionID();
					Potion potion = id >= 0 && id < Potion.potionTypes.length ? Potion.potionTypes[id] : null;
					if (potion == null) continue;
					String level = HudFormat.level(e.getAmplifier());
					String name = I18n.format(potion.getName()) + (level.isEmpty() ? "" : " " + level);
					line(name + "  " + HudFormat.duration(e.getDuration(), e.getIsPotionDurationMax()),
							0xFF000000 | potion.getLiquidColor());
					count++;
				}
			}
			if (preview && count == 0) {
				line("Schnelligkeit II  1:30", 0xFF7CAFC6);
				line("Stärke  0:45", 0xFF932423);
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
			EntityPlayer p = mc.thePlayer;
			if (p == null) {
				if (preview) line(HudFormat.coords(128, 64, -256));
				return;
			}
			// 1.7.10: posY des Client-Spielers liegt auf Augenhöhe – boundingBox.minY sind die Füße.
			line(HudFormat.coords(p.posX, p.boundingBox.minY, p.posZ));
			if (modules.coordsDirection.get()) {
				float yaw = p.rotationYaw;
				line("Richtung: " + HudFormat.directionName(yaw) + " (" + HudFormat.direction(yaw) + ")");
			}
			if (modules.coordsBiome.get() && mc.theWorld != null) {
				int x = MathHelper.floor_double(p.posX);
				int z = MathHelper.floor_double(p.posZ);
				line("Biom: " + mc.theWorld.getBiomeGenForCoords(x, z).biomeName);
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

	/** Adresse des Servers (nicht im Einzelspieler). */
	public static final class Server extends LinesHudElement {
		public Server(HudModule module) {
			super(module);
		}

		@Override
		protected void build(boolean preview) {
			ServerData data = mc.isSingleplayer() ? null : mc.func_147104_D();
			if (data != null) {
				line(data.serverIP);
			} else if (preview) {
				line("play.example.net");
			}
		}
	}

	/** Eingeschaltete Resourcepacks, höchste Priorität oben (1.7.10: Standard-Pack steht nicht in der Liste). */
	public static final class Packs extends LinesHudElement {
		public Packs(HudModule module) {
			super(module);
		}

		@Override
		protected void build(boolean preview) {
			@SuppressWarnings("unchecked")
			List<ResourcePackRepository.Entry> selected =
					new ArrayList<ResourcePackRepository.Entry>(mc.getResourcePackRepository().getRepositoryEntries());
			Collections.reverse(selected);
			for (ResourcePackRepository.Entry pack : selected) line(pack.getResourcePackName());
			if (preview && selected.isEmpty()) line("Keine zusätzlichen Packs aktiv");
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
			ToggleState state = sprint ? TrsClient.get().pvp().sprint() : TrsClient.get().pvp().sneak();
			if (preview || state.active()) line("[" + label + " (umgeschaltet)]");
		}
	}
}
