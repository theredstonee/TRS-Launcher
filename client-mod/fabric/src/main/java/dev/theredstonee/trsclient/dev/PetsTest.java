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
import dev.theredstonee.trsclient.online.OnlineHooks;
import dev.theredstonee.trsclient.screen.WardrobeScreen;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Selbsttest „Winter-Kosmetik“ ({@code -PtrsAutotestOnly=pets}, mit {@code scripts/mock-winter-api.mjs}): baut eine
 * Szene in der Luft (Schnee, Eisbahn, Wasserbecken, 1-Block-Stufe), legt nacheinander Mütze (Bart an/aus), Schneewolke,
 * Schnee-/Eisflügel und Pinguine an und fotografiert: Pinguine folgen, rutschen, schwimmen, springen, eigene Pinguine in
 * der Ich-Perspektive, Garderobe mit Begleiter-Fach und Bart-Schalter. Teile kommen aus dem Katalog der Attrappe
 * (synthetisch {@code test_*} oder – mit {@code WINTER_REAL_DIR} – die echten Teile).
 */
public final class PetsTest {
	private int phase;
	private int wait;
	private int tries;
	private int ticks;
	private CosmeticCatalog catalog;
	private WardrobeUi ui;
	private double bx, by, bz;
	private String hat, cloud, snowWings, iceWings, pet;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (mc.player == null) return false;
		if (wait > 0) {
			wait--;
			return true;
		}
		OnlineFeatures<Object> f = OnlineHooks.features();
		switch (phase) {
			case 0: {
				actions.command("gamerule logAdminCommands false");
				actions.command("gamerule sendCommandFeedback false");
				actions.command("gamerule doDaylightCycle false");
				actions.command("time set day");
				actions.command("weather clear");
				actions.command("gamemode creative @p");
				//? if >=1.17 {
				actions.command("item replace entity @p armor.head with air");
				//?} else
				/*actions.command("replaceitem entity @p armor.head air");*/
				bx = Math.floor(Mc.x(mc.player)) + 0.5;
				bz = Math.floor(Mc.z(mc.player)) + 0.5;
				by = Math.floor(Mc.y(mc.player)) + 40;
				int x = (int) Math.floor(bx), y = (int) by, z = (int) Math.floor(bz);
				// Boden aus Schnee, Eisbahn nach Süden, Wasserbecken im Osten, Stufe im Westen
				cmd(actions, "fill %d %d %d %d %d %d air", x - 24, y - 4, z - 24, x + 24, y + 8, z + 24);
				cmd(actions, "fill %d %d %d %d %d %d snow_block", x - 24, y - 1, z - 24, x + 24, y - 1, z + 24);
				cmd(actions, "fill %d %d %d %d %d %d ice", x - 2, y - 1, z + 2, x + 2, y - 1, z + 22);
				cmd(actions, "fill %d %d %d %d %d %d stone", x + 6, y - 4, z - 7, x + 16, y - 1, z + 7);
				cmd(actions, "fill %d %d %d %d %d %d water", x + 7, y - 3, z - 6, x + 15, y - 1, z + 6);
				cmd(actions, "fill %d %d %d %d %d %d snow_block", x - 16, y, z - 6, x - 8, y, z + 6);
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.crosshair.setEnabled(false);
				Mc.setScreen(null);
				Mc.setHudHidden(true);
				catalog = CosmeticCatalog.shared(new WardrobeContext(I18n.configDir(), "TRS-Client", f));
				catalog.refresh(true);
				phase = 1;
				wait = 20;
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
					if ("companion".equals(it.slot)) cloud = it.id;
					if ("wings".equals(it.slot) && it.id.contains("snow")) snowWings = it.id;
					if ("wings".equals(it.slot) && it.id.contains("ice")) iceWings = it.id;
					if ("pet".equals(it.slot)) pet = it.id;
				}
				TrsClient.LOGGER.info("[Autotest] Winter: Katalog {} – Mütze {}, Wolke {}, Flügel {}/{}, Begleiter {}",
						s.loaded ? "geladen" : "FEHLT", hat, cloud, snowWings, iceWings, pet);
				place(mc, 0, 0, 0f);
				catalog.wear(hat);
				tries = 0;
				phase = 2;
				wait = 5;
				return true;
			}
			case 2: {
				place(mc, 0, 0, 0f);
				V2Hat<Object> h = f.hatV2(mc.player.getUUID(), false);
				if ((h == null || !h.model.id.equals(hat)) && tries++ < 600) {
					if (tries % 200 == 0) f.online().refreshSelf();
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] Winter: Mütze {} nach {} Ticks, Optionen {}", h != null ? "geladen" : "FEHLT", tries,
						h == null ? "-" : h.model.options.size());
				TrsClient.get().setForceZoom(true);
				TrsClient.get().pvp().forceFreelook(180F);
				camera(mc, 160F, 6F);
				phase = 3;
				wait = 12;
				return true;
			}
			case 3:
				place(mc, 0, 0, 0f);
				actions.shot("trsclient-winter-beard-on");
				// Bart aus (PUT options) und warten, bis der eigene Lookup ihn ohne Bart liefert
				catalog.setOption("hat", "beard", false, CosmeticCatalog.optionsOf(catalog.state(), catalog.state().item(hat)));
				tries = 0;
				phase = 4;
				return true;
			case 4: {
				place(mc, 0, 0, 0f);
				V2Hat<Object> h = f.hatV2(mc.player.getUUID(), false);
				if ((h == null || h.hiddenBones == 0L) && tries++ < 400) {
					if (tries % 100 == 0) f.online().refreshSelf();
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] Winter: Bart aus {} nach {} Ticks (Knochen-Maske {})", h != null && h.hiddenBones != 0
						? "ok" : "FEHLT", tries, h == null ? 0 : Long.toBinaryString(h.hiddenBones));
				phase = 5;
				wait = 6;
				return true;
			}
			case 5:
				place(mc, 0, 0, 0f);
				actions.shot("trsclient-winter-beard-off");
				catalog.setOption("hat", "beard", true, CosmeticCatalog.optionsOf(catalog.state(), catalog.state().item(hat)));
				if (cloud != null) catalog.wearCompanion(cloud);
				tries = 0;
				phase = 6;
				wait = 5;
				return true;
			case 6: {
				place(mc, 0, 0, 0f);
				V2Hat<Object> c = f.companionV2(mc.player.getUUID(), false);
				if (cloud != null && (c == null || !c.model.id.equals(cloud)) && tries++ < 400) {
					if (tries % 100 == 0) f.online().refreshSelf();
					return true;
				}
				TrsClient.get().setForceZoom(false);
				camera(mc, 150F, 12F);
				phase = 7;
				wait = 12;
				return true;
			}
			case 7:
				place(mc, 0, 0, 0f);
				actions.shot("trsclient-winter-cloud");
				// Sprung: Mütze und Wolke spielen „air“
				dev.theredstonee.trsclient.core.cosmetic.v2.WingState.forced = dev.theredstonee.trsclient.core.cosmetic.v2.WingState.AIR;
				phase = 8;
				wait = 8;
				return true;
			case 8:
				place(mc, 0, 0, 0f);
				actions.shot("trsclient-winter-cloud-air");
				dev.theredstonee.trsclient.core.cosmetic.v2.WingState.forced = -1;
				if (snowWings != null) catalog.wearWings(snowWings);
				tries = 0;
				phase = 9;
				return true;
			case 9:
			case 11: {
				place(mc, 0, 0, 0f);
				String want = phase == 9 ? snowWings : iceWings;
				V2Hat<Object> w = f.wingsV2(mc.player.getUUID(), true, false, 0);
				if (want != null && (w == null || !w.model.id.equals(want)) && tries++ < 400) {
					if (tries % 100 == 0) f.online().refreshSelf();
					return true;
				}
				camera(mc, 20F, 10F);
				if (phase == 11) actions.command("time set midnight");
				phase++;
				wait = 14;
				return true;
			}
			case 10:
				place(mc, 0, 0, 0f);
				actions.shot("trsclient-winter-snow-wings");
				if (iceWings != null) catalog.wearWings(iceWings);
				tries = 0;
				phase = 11;
				return true;
			case 12:
				place(mc, 0, 0, 0f);
				actions.shot("trsclient-winter-ice-wings-night");
				actions.command("time set day");
				catalog.wearWings(null);
				catalog.wearCompanion(null);
				if (pet != null) catalog.wearPet(pet);
				DevPets.forcedCount = 3;
				PetSim.forceSlide = 0;
				tries = 0;
				phase = 13;
				return true;
			case 13: {
				place(mc, 0, 0, 0f);
				List<Pet> pets = f.pets().pets(mc.player.getUUID());
				boolean drawn = f.petFrame(null, Mc.cameraX(), Mc.cameraY(), Mc.cameraZ(), Mc.cameraYaw(), Mc.cameraPitch()) > 0;
				if ((pets.isEmpty() || !drawn) && tries++ < 600) {
					if (tries % 200 == 0) f.online().refreshSelf();
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] Winter: {} Pinguine nach {} Ticks ({})", pets.size(), tries, drawn ? "gezeichnet" : "FEHLT");
				ticks = 0;
				phase = 14;
				return true;
			}
			case 14:
				// Folgen: 6 Blöcke nach Süden laufen (0,2 je Tick), Kamera schräg von vorne oben
				place(mc, 0, Math.min(6, ticks * 0.2), 0f);
				camera(mc, 145F, 28F);
				if (++ticks == 50) {
					actions.shot("trsclient-winter-pets-follow");
					log(f, mc, "folgen");
				}
				if (ticks < 52) return true;
				ticks = 0;
				phase = 15;
				return true;
			case 15:
				// Rutschen: auf der Eisbahn nach Süden, Rutschen erzwungen
				PetSim.forceSlide = 1;
				place(mc, 0, 6 + Math.min(12, ticks * 0.3), 0f);
				camera(mc, 160F, 25F);
				if (++ticks == 22) {
					actions.shot("trsclient-winter-pets-slide");
					log(f, mc, "rutschen");
				}
				if (ticks < 30) return true;
				PetSim.forceSlide = 0;
				ticks = 0;
				phase = 16;
				return true;
			case 16:
				// Schwimmen: ins Becken (Osten), Spieler an der Oberfläche festhalten
				place(mc, 11, 0, 0f, -0.4);
				camera(mc, 130F, 35F);
				if (++ticks == 90) {
					actions.shot("trsclient-winter-pets-swim");
					log(f, mc, "schwimmen");
				}
				if (ticks < 92) return true;
				ticks = 0;
				phase = 17;
				return true;
			case 17: {
				// Springen: nach Westen auf die Stufe (ab x − 8 einen Block höher), die Pinguine springen hinterher
				double dx = -2 - Math.min(10, ticks * 0.15);
				place(mc, dx, 0, 90f, dx < -7.7 ? 1.0 : 0.0);
				camera(mc, 90F, 30F);
				ticks++;
				if (ticks > 75 && f.pets().pets(mc.player.getUUID()).stream().anyMatch(p -> p.state() == Pet.AIR) || ticks == 220) {
					actions.shot("trsclient-winter-pets-jump");
					log(f, mc, "springen");
					ticks = 0;
					phase = 18;
				}
				return true;
			}
			case 18:
				// Ich-Perspektive, umgedreht (nach Osten): die eigenen Pinguine sind zu sehen
				TrsClient.get().pvp().forceFreelook(Float.NaN);
				Mc.setCameraMode(0);
				place(mc, -12, 0, 270f, 1.0);
				// nach unten schauen (Pinguine stehen am Boden vor der Kamera)
				//? if >=1.17 {
				mc.player.setXRot(35);
				//?} else
				/*mc.player.xRot = 35;*/
				if (++ticks < 30) return true;
				actions.shot("trsclient-winter-pets-firstperson");
				ticks = 0;
				phase = 19;
				return true;
			case 19:
				// Garderobe: Begleiter-Fach und Bart-Schalter
				Mc.setHudHidden(false);
				Mc.setScreen(WardrobeScreen.create(null));
				ui = WardrobeScreen.last;
				ui.category("cosmetics");
				ui.selectCosmetic(pet);
				tries = 0;
				phase = 20;
				wait = 10;
				return true;
			case 20:
				if (!catalog.state().previews.containsKey(pet) && tries++ < 300) return true;
				phase = 21;
				wait = 20;
				return true;
			case 21:
				actions.shot("trsclient-winter-wardrobe-pet");
				ui.selectCosmetic(hat);
				tries = 0;
				phase = 22;
				wait = 5;
				return true;
			case 22:
				if (!catalog.state().previews.containsKey(hat) && tries++ < 300) return true;
				phase = 23;
				wait = 20;
				return true;
			case 23:
				actions.shot("trsclient-winter-wardrobe-hat");
				Mc.setScreen(null);
				DevPets.forcedCount = 0;
				PetSim.forceSlide = Double.NaN;
				TrsClient.get().setForceZoom(false);
				return false;
			default:
				return false;
		}
	}

	private void place(Minecraft mc, double dx, double dz, float yaw) {
		place(mc, dx, dz, yaw, 0);
	}

	/** Spieler an (Szene + dx, by + dy, Szene + dz) halten, Blick {@code yaw} (Kopf gerade). */
	private void place(Minecraft mc, double dx, double dz, float yaw, double dy) {
		mc.player.setPos(bx + dx, by + dy, bz + dz);
		mc.player.setDeltaMovement(0, 0, 0);
		//? if >=1.17 {
		mc.player.setYRot(yaw);
		mc.player.setXRot(0);
		//?} else {
		/*mc.player.yRot = yaw;
		mc.player.xRot = 0;
		*///?}
		mc.player.yHeadRot = yaw;
		mc.player.yBodyRot = yaw;
	}

	private static void cmd(CapeTest.Actions actions, String fmt, Object... args) {
		actions.command(String.format(java.util.Locale.ROOT, fmt, args));
	}

	private static void log(OnlineFeatures<Object> f, Minecraft mc, String what) {
		StringBuilder sb = new StringBuilder();
		for (Pet p : f.pets().pets(mc.player.getUUID())) {
			sb.append(p.stateName()).append(String.format(java.util.Locale.ROOT, "@%.1f/%.2f/%.1f ", p.x(1f), p.y(1f), p.z(1f)));
		}
		TrsClient.LOGGER.info("[Autotest] Winter: Pinguine {} – {}(Spieler {}/{}/{})", what, sb,
				String.format(java.util.Locale.ROOT, "%.1f", Mc.x(mc.player)), String.format(java.util.Locale.ROOT, "%.2f", Mc.y(mc.player)),
				String.format(java.util.Locale.ROOT, "%.1f", Mc.z(mc.player)));
	}

	/** Kamera: Freelook-Winkel relativ zur Figur ({@code 180} = von vorne, {@code 0} = von hinten), 3. Person. */
	private static void camera(Minecraft mc, float yawOffset, float pitch) {
		TrsClient.get().pvp().forceFreelook(180F);
		TrsClient.get().pvp().freelook().start(yawOffset, pitch);
		Mc.setCameraMode(1);
	}
}
