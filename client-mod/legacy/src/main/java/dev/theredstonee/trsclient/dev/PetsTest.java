package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cosmetic.pet.DevPets;
import dev.theredstonee.trsclient.core.cosmetic.pet.Pet;
import dev.theredstonee.trsclient.core.cosmetic.pet.PetSim;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Hat;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import dev.theredstonee.trsclient.core.ui.wardrobe.WardrobeUi;
import dev.theredstonee.trsclient.core.wardrobe.CosmeticCatalog;
import dev.theredstonee.trsclient.core.wardrobe.WardrobeContext;
import dev.theredstonee.trsclient.online.LegacyOnline;
import dev.theredstonee.trsclient.screen.WardrobeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

import java.util.Locale;

/**
 * Selbsttest „Winter-Kosmetik“ für Forge 1.8.9–1.12.2 ({@code -PtrsAutotestOnly=pets}, Attrappe
 * {@code scripts/mock-winter-api.mjs}): wie der Fabric-Test – Szene in der Luft (Schnee, Eisbahn, Becken, Stufe), Mütze mit
 * Bart an/aus, Pinguine folgen/rutschen/schwimmen/springen, Ich-Perspektive, Garderobe mit Begleiter-Fach.
 */
public final class PetsTest {
	private int phase;
	private int wait;
	private int tries;
	private int ticks;
	private CosmeticCatalog catalog;
	private WardrobeUi ui;
	private double bx, by, bz;
	private String hat, pet, wings;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		EntityPlayerSP player = Mc.player();
		if (player == null) return false;
		if (wait > 0) {
			wait--;
			return true;
		}
		OnlineFeatures<Object> f = LegacyOnline.features();
		String name = player.getName();
		switch (phase) {
			case 0: {
				actions.command("gamerule logAdminCommands false");
				actions.command("gamerule sendCommandFeedback false");
				actions.command("gamerule doDaylightCycle false");
				actions.command("time set 1000");
				actions.command("weather clear");
				actions.command("gamemode 1 " + name);
				actions.command("replaceitem entity " + name + " slot.armor.head minecraft:air");
				bx = Math.floor(player.posX) + 0.5;
				bz = Math.floor(player.posZ) + 0.5;
				by = Math.floor(player.posY) + 40;
				int x = (int) Math.floor(bx), y = (int) by, z = (int) Math.floor(bz);
				cmd(actions, "fill %d %d %d %d %d %d minecraft:air", x - 16, y - 4, z - 16, x + 16, y + 6, z + 16);
				cmd(actions, "fill %d %d %d %d %d %d minecraft:snow", x - 16, y - 1, z - 16, x + 16, y - 1, z + 16);
				cmd(actions, "fill %d %d %d %d %d %d minecraft:ice", x - 2, y - 1, z + 2, x + 2, y - 1, z + 15);
				cmd(actions, "fill %d %d %d %d %d %d minecraft:stone", x + 6, y - 4, z - 7, x + 15, y - 1, z + 7);
				cmd(actions, "fill %d %d %d %d %d %d minecraft:water", x + 7, y - 3, z - 6, x + 14, y - 1, z + 6);
				cmd(actions, "fill %d %d %d %d %d %d minecraft:snow", x - 15, y, z - 6, x - 8, y, z + 6);
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.crosshair.setEnabled(false);
				mc.displayGuiScreen(null);
				mc.gameSettings.hideGUI = true;
				catalog = CosmeticCatalog.shared(new WardrobeContext(I18n.configDir(), "TRS-Client", f));
				catalog.refresh(true);
				phase = 1;
				wait = 40;
				return true;
			}
			case 1: {
				CosmeticCatalog.State s = catalog.state();
				if (!s.loaded && tries++ < 400) {
					if (tries % 100 == 0) catalog.refresh(true);
					return true;
				}
				for (CosmeticCatalog.Item it : s.items) {
					if ("hat".equals(it.slot) && hat == null) hat = it.id;
					if ("pet".equals(it.slot)) pet = it.id;
					if ("wings".equals(it.slot) && it.id.contains("snow")) wings = it.id;
				}
				TrsClient.LOGGER.info("[Autotest] Winter: Katalog {} – Mütze {}, Begleiter {}", s.loaded ? "geladen" : "FEHLT", hat, pet);
				if (Boolean.getBoolean("trsclient.autotest.petsFix")) {
					// Nur die Prüfung der Fehlerbehebungen: Flossen (idle/air), Pinguine und Flügel im/vor Wasser
					catalog.wear(hat);
					catalog.wearPet(pet);
					if (wings != null) catalog.wearWings(wings);
					DevPets.forcedCount = 1;
					PetSim.forceSlide = 0;
					tries = 0;
					phase = 40;
					return true;
				}
				place(player, 0, 0, 0f, 0);
				catalog.wear(hat);
				catalog.wearPet(pet);
				DevPets.forcedCount = 3;
				PetSim.forceSlide = 0;
				tries = 0;
				phase = 2;
				wait = 5;
				return true;
			}
			case 2: {
				place(player, 0, 0, 0f, 0);
				V2Hat<Object> h = f.hatV2(player.getUniqueID(), false);
				boolean pets = !f.pets().pets(player.getUniqueID()).isEmpty();
				if ((h == null || !pets) && tries++ < 600) {
					if (tries % 200 == 0) f.online().refreshSelf();
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] Winter: Mütze {}, Pinguine {} nach {} Ticks", h != null ? "geladen" : "FEHLT",
						f.pets().pets(player.getUniqueID()).size(), tries);
				TrsClient.get().setForceZoom(true);
				TrsClient.get().pvp().forceFreelook(180F);
				camera(mc, 160F, 6F);
				phase = 3;
				wait = 15;
				return true;
			}
			case 3:
				place(player, 0, 0, 0f, 0);
				actions.shot("winter-beard-on");
				catalog.setOption("hat", "beard", false, CosmeticCatalog.optionsOf(catalog.state(), catalog.state().item(hat)));
				tries = 0;
				phase = 4;
				return true;
			case 4: {
				place(player, 0, 0, 0f, 0);
				V2Hat<Object> h = f.hatV2(player.getUniqueID(), false);
				if ((h == null || h.hiddenBones == 0L) && tries++ < 400) {
					if (tries % 100 == 0) f.online().refreshSelf();
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] Winter: Bart aus {} nach {} Ticks", h != null && h.hiddenBones != 0 ? "ok" : "FEHLT", tries);
				phase = 5;
				wait = 8;
				return true;
			}
			case 5:
				place(player, 0, 0, 0f, 0);
				actions.shot("winter-beard-off");
				catalog.setOption("hat", "beard", true, CosmeticCatalog.optionsOf(catalog.state(), catalog.state().item(hat)));
				TrsClient.get().setForceZoom(false);
				ticks = 0;
				phase = 6;
				return true;
			case 6:
				// Folgen nach Süden
				place(player, 0, Math.min(6, ticks * 0.2), 0f, 0);
				camera(mc, 145F, 28F);
				if (++ticks == 50) {
					actions.shot("winter-pets-follow");
					log(f, player, "folgen");
				}
				if (ticks < 52) return true;
				ticks = 0;
				phase = 7;
				return true;
			case 7:
				// Rutschen auf der Eisbahn
				PetSim.forceSlide = 1;
				place(player, 0, 6 + Math.min(8, ticks * 0.3), 0f, 0);
				camera(mc, 160F, 25F);
				if (++ticks == 22) {
					actions.shot("winter-pets-slide");
					log(f, player, "rutschen");
				}
				if (ticks < 30) return true;
				PetSim.forceSlide = 0;
				ticks = 0;
				phase = 8;
				return true;
			case 8:
				// Schwimmen im Becken
				place(player, 10.5, 0, 0f, -0.4);
				camera(mc, 130F, 35F);
				if (++ticks == 90) {
					actions.shot("winter-pets-swim");
					log(f, player, "schwimmen");
				}
				if (ticks < 92) return true;
				ticks = 0;
				phase = 9;
				return true;
			case 9: {
				// Auf die Stufe im Westen, Pinguine springen hinterher
				double dx = -2 - Math.min(10, ticks * 0.15);
				place(player, dx, 0, 90f, dx < -7.7 ? 1.0 : 0.0);
				camera(mc, 90F, 30F);
				ticks++;
				boolean air = false;
				for (Pet p : f.pets().pets(player.getUniqueID())) air |= p.state() == Pet.AIR;
				if (ticks > 75 && air || ticks == 220) {
					actions.shot("winter-pets-jump");
					log(f, player, "springen");
					ticks = 0;
					phase = 10;
				}
				return true;
			}
			case 10:
				TrsClient.get().pvp().forceFreelook(Float.NaN);
				mc.gameSettings.thirdPersonView = 0;
				place(player, -12, 0, 270f, 1.0);
				player.rotationPitch = 35F;
				player.prevRotationPitch = 35F;
				if (++ticks < 30) return true;
				actions.shot("winter-pets-firstperson");
				ticks = 0;
				phase = 11;
				return true;
			case 11:
				mc.gameSettings.hideGUI = false;
				mc.displayGuiScreen(WardrobeScreen.create(null));
				ui = WardrobeScreen.last;
				ui.category("cosmetics");
				ui.selectCosmetic(pet);
				tries = 0;
				phase = 12;
				wait = 10;
				return true;
			case 12:
				if (!catalog.state().previews.containsKey(pet) && tries++ < 300) return true;
				phase = 13;
				wait = 20;
				return true;
			case 13:
				actions.shot("winter-wardrobe-pet");
				mc.displayGuiScreen(null);
				DevPets.forcedCount = 0;
				PetSim.forceSlide = Double.NaN;
				return false;
			case 40: {
				place(player, 0, 0, 0f, 0);
				boolean pets = !f.pets().pets(player.getUniqueID()).isEmpty();
				V2Hat<Object> w = f.wingsV2(player.getUniqueID(), true, false, 0);
				boolean wingsOk = wings == null || w != null && w.model.id.equals(wings);
				if ((!pets || !wingsOk) && tries++ < 600) {
					if (tries % 200 == 0) f.online().refreshSelf();
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] Fix: Pinguin {}, Flügel {} nach {} Ticks", pets ? "da" : "FEHLT", wingsOk ? "geladen" : "FEHLEN", tries);
				TrsClient.get().setForceZoom(true);
				TrsClient.get().pvp().forceFreelook(Float.NaN);
				mc.gameSettings.thirdPersonView = 0;
				ticks = 0;
				phase = 41;
				return true;
			}
			case 41:
			case 42: {
				// Flossen wie in der Studio-Werkbank: idle bei 4100 ms, air bei 0 ms; Pinguin 3 Blöcke vor der Kamera
				boolean air = phase == 42;
				PetSim.frozen = true;
				PetSim.forceState = air ? Pet.AIR : Pet.IDLE;
				PetSim.forceTimeMs = air ? 0 : 4100;
				place(player, 0, 0, 0f, 0);
				player.rotationPitch = 24F;
				player.prevRotationPitch = 24F;
				f.pets().debugPose(player.getUniqueID(), bx, by, bz + 3.0, 180f);
				if (++ticks < 8) return true;
				actions.shot(air ? "fix-pet-air-front" : "fix-pet-idle-front");
				ticks = 0;
				phase++;
				return true;
			}
			case 43:
				// Im Becken stehen: Pinguine schwimmen, Flügel vor dem Wasser
				PetSim.frozen = false;
				PetSim.forceState = -1;
				PetSim.forceTimeMs = -1;
				TrsClient.get().setForceZoom(false);
				DevPets.forcedCount = 3;
				place(player, 10.5, 0, 0f, -0.4);
				ticks++;
				if (ticks < 80) camera(mc, 15F, 38F);
				if (ticks == 80) actions.shot("fix-water-stand-back");
				if (ticks == 81) camera(mc, 140F, 30F);
				if (ticks == 92) {
					actions.shot("fix-water-stand-front");
					log(f, player, "im Becken");
				}
				if (ticks < 94) return true;
				DevPets.forcedCount = 0;
				PetSim.forceSlide = Double.NaN;
				TrsClient.get().pvp().forceFreelook(Float.NaN);
				return false;
			default:
				return false;
		}
	}

	/** Spieler an (Szene + dx, by + dy, Szene + dz) halten, Blick {@code yaw}. */
	private void place(EntityPlayerSP p, double dx, double dz, float yaw, double dy) {
		p.setPosition(bx + dx, by + dy, bz + dz);
		p.motionX = p.motionY = p.motionZ = 0;
		p.rotationYaw = yaw;
		p.prevRotationYaw = yaw;
		p.rotationPitch = 0F;
		p.prevRotationPitch = 0F;
		p.rotationYawHead = yaw;
		p.prevRotationYawHead = yaw;
		p.renderYawOffset = yaw;
		p.prevRenderYawOffset = yaw;
	}

	private static void cmd(CapeTest.Actions actions, String fmt, Object... args) {
		actions.command(String.format(Locale.ROOT, fmt, args));
	}

	private static void log(OnlineFeatures<Object> f, EntityPlayerSP player, String what) {
		StringBuilder sb = new StringBuilder();
		for (Pet p : f.pets().pets(player.getUniqueID())) {
			sb.append(p.stateName()).append(String.format(Locale.ROOT, "@%.1f/%.2f/%.1f ", p.x(1f), p.y(1f), p.z(1f)));
		}
		TrsClient.LOGGER.info("[Autotest] Winter: Pinguine {} – {}(Spieler {})", what, sb,
				String.format(Locale.ROOT, "%.1f/%.2f/%.1f", player.posX, player.posY, player.posZ));
	}

	/** Kamera: Freelook-Winkel relativ zur Figur ({@code 180} = von vorne, {@code 0} = von hinten), 3. Person. */
	private static void camera(Minecraft mc, float yawOffset, float pitch) {
		TrsClient.get().pvp().forceFreelook(180F);
		TrsClient.get().pvp().freelook().start(yawOffset, pitch);
		mc.gameSettings.thirdPersonView = 1;
	}
}
