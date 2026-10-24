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
		if (phase >= 40) return fix(mc, f, actions);
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
				// Prüfung der Fehlerbehebungen: feste Höhe (die Testwelt merkt sich die letzte Position nach der Bootsfahrt)
				if (Boolean.getBoolean("trsclient.autotest.petsFix")) by = 120;
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
				if (Boolean.getBoolean("trsclient.autotest.petsFix")) {
					// Nur die Prüfung der Fehlerbehebungen: Flossen je Zustand, Flügel/Pinguine vor Wasser, Boot
					phase = 40;
					tries = 0;
					return true;
				}
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

	// =====================================================================================================
	// Prüfung der Fehlerbehebungen 0.21.1 (-PtrsSys.trsclient.autotest.petsFix=true): Flossen je Zustand wie in der
	// Studio-Werkbank (gleiche Zeitpunkte wie tools/shots.mjs --t=…), Flügel im Sprung, Wasser vor/hinter Pinguinen und
	// Flügeln, Bootsfahrt mit 8 Blöcken je Sekunde.
	// =====================================================================================================

	private static final int[] FIX_STATES = { Pet.IDLE, Pet.WALK, Pet.SLIDE, Pet.SWIM, Pet.AIR };
	private static final long[] FIX_TIMES = { 4100, 140, 300, 0, 0 };
	private int fixIndex;
	private int startTeleports, lastTeleports;
	private double boatY;

	private boolean fix(Minecraft mc, OnlineFeatures<Object> f, CapeTest.Actions actions) {
		java.util.UUID me = mc.player.getUUID();
		switch (phase) {
			case 40: {
				int x = (int) Math.floor(bx), y = (int) by, z = (int) Math.floor(bz);
				// Kanal für die Bootsfahrt nach Süden (5 tief)
				cmd(actions, "fill %d %d %d %d %d %d stone", x - 8, y - 6, z + 26, x + 8, y - 1, z + 110);
				cmd(actions, "fill %d %d %d %d %d %d water", x - 6, y - 5, z + 26, x + 6, y - 1, z + 110);
				catalog.wear(hat);
				if (cloud != null) catalog.wearCompanion(cloud);
				if (snowWings != null) catalog.wearWings(snowWings);
				if (pet != null) catalog.wearPet(pet);
				DevPets.forcedCount = 1;
				PetSim.forceSlide = 0;
				tries = 0;
				phase = 41;
				return true;
			}
			case 41: {
				place(mc, 0, 0, 0f);
				V2Hat<Object> w = f.wingsV2(me, true, false, 0);
				boolean drawn = f.petFrame(null, Mc.cameraX(), Mc.cameraY(), Mc.cameraZ(), Mc.cameraYaw(), Mc.cameraPitch()) > 0;
				boolean wingsOk = snowWings == null || w != null && w.model.id.equals(snowWings);
				if ((!drawn || !wingsOk) && tries++ < 600) {
					if (tries % 200 == 0) f.online().refreshSelf();
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] Fix: Pinguin {}, Flügel {} nach {} Ticks", drawn ? "gezeichnet" : "FEHLT",
						wingsOk ? "geladen" : "FEHLEN", tries);
				TrsClient.get().setForceZoom(true);
				fixIndex = 0;
				phase = 42;
				return true;
			}
			case 42:
			case 43:
			case 44: {
				// Ein Zustand: Pinguin steht still 3 Blöcke vor der Kamera (Ich-Perspektive, Zoom), feste Animationszeit
				int st = FIX_STATES[fixIndex];
				boolean swim = st == Pet.SWIM;
				PetSim.frozen = true;
				PetSim.forceState = st;
				PetSim.forceTimeMs = FIX_TIMES[fixIndex];
				TrsClient.get().pvp().forceFreelook(Float.NaN);
				Mc.setCameraMode(0);
				// Becken: Wasser y−3…y−1, Oberfläche by − 0,1; Schwimmhöhe 0,3 darunter
				double px = swim ? bx + 9.5 : bx, py = swim ? by - 0.4 : by, pz = swim ? bz : bz + 3.0;
				if (swim) look(mc, 5.5, 0, 270f, 28f);
				else look(mc, 0, 0, 0f, 24f);
				float front = swim ? 90f : 180f;
				if (phase == 42) {
					f.pets().debugPose(me, px, py, pz, front);
					phase = 43;
					wait = 8;
				} else if (phase == 43) {
					actions.shot("trsclient-fix-pet-" + Pet.stateNameOf(st) + "-front");
					f.pets().debugPose(me, px, py, pz, front - 35f);
					phase = 44;
					wait = 4;
				} else {
					actions.shot("trsclient-fix-pet-" + Pet.stateNameOf(st) + "-three");
					fixIndex++;
					phase = fixIndex < FIX_STATES.length ? 42 : 45;
				}
				return true;
			}
			case 45:
				// Flügel im Sprung (Zustand air, feste Zeit), Kamera von hinten
				PetSim.frozen = false;
				PetSim.forceState = -1;
				PetSim.forceTimeMs = -1;
				TrsClient.get().setForceZoom(false);
				dev.theredstonee.trsclient.core.cosmetic.v2.WingState.forced = dev.theredstonee.trsclient.core.cosmetic.v2.WingState.AIR;
				OnlineFeatures.v2Clock = () -> 180L;
				place(mc, 0, 0, 0f);
				camera(mc, 20F, 10F);
				phase = 46;
				wait = 10;
				return true;
			case 46:
				place(mc, 0, 0, 0f);
				actions.shot("trsclient-fix-snow-wings-air");
				OnlineFeatures.v2Clock = null;
				dev.theredstonee.trsclient.core.cosmetic.v2.WingState.forced = -1;
				DevPets.forcedCount = 3;
				ticks = 0;
				phase = 47;
				return true;
			case 47:
				// Im Becken stehen: Pinguine schwimmen um den Spieler, Flügel vor dem Wasser
				place(mc, 11, 0, 0f, -0.4);
				ticks++;
				if (ticks < 80) camera(mc, 15F, 38F);
				if (ticks == 80) {
					actions.shot("trsclient-fix-water-stand-back");
					if (iceWings != null) catalog.wearWings(iceWings);
				}
				if (ticks > 80 && ticks < 300 && iceWings != null) {
					V2Hat<Object> w = f.wingsV2(me, true, false, 0);
					if (w == null || !w.model.id.equals(iceWings)) {
						if (ticks % 100 == 0) f.online().refreshSelf();
						return true;
					}
					if (ticks < 1000) ticks = 1000;
				}
				if (ticks == 1012) actions.shot("trsclient-fix-water-stand-back-ice");
				if (ticks == 1013) camera(mc, 140F, 30F);
				if (ticks == 1024) {
					actions.shot("trsclient-fix-water-stand-front-ice");
					log(f, mc, "im Becken");
					if (snowWings != null) catalog.wearWings(snowWings);
				}
				if (ticks < 1060 && ticks >= 1000 || ticks < 300) return true;
				// Boot: Spieler an den Kanal, Boot setzen, einsteigen
				place(mc, 0, 28, 0f);
				// Boote früherer Läufe entfernen (sonst steigt der Spieler in ein altes Boot)
				actions.command("kill @e[type=" + boatType() + "]");
				//? if >=1.21.2 {
				cmd(actions, "summon oak_boat %.2f %.2f %.2f {Rotation:[0f,0f]}", bx, by - 0.45, bz + 30);
				//?} else
				/*cmd(actions, "summon boat %.2f %.2f %.2f {Rotation:[0f,0f]}", bx, by - 0.45, bz + 30);*/
				tries = 0;
				phase = 48;
				wait = 5;
				return true;
			case 48: {
				net.minecraft.world.entity.Entity v = mc.player.getVehicle();
				if (v == null) {
					if (tries % 20 == 0) actions.command("ride @p mount @e[type=" + boatType() + ",limit=1,sort=nearest]");
					if (tries++ < 120) {
						place(mc, 0, 29.5, 0f);
						return true;
					}
					TrsClient.LOGGER.info("[Autotest] Fix: Boot FEHLT (kein Einsteigen)");
					return finish();
				}
				boatY = Mc.y(v);
				startTeleports = lastTeleports = teleports(f, me);
				TrsClient.LOGGER.info("[Autotest] Fix: im Boot nach {} Ticks (y {})", tries, String.format(java.util.Locale.ROOT, "%.2f", boatY));
				ticks = 0;
				phase = 49;
				return true;
			}
			case 49: {
				// Bootsfahrt nach Süden: erst 2 s stehen, dann 8 Blöcke je Sekunde (0,4 je Tick)
				net.minecraft.world.entity.Entity v = mc.player.getVehicle();
				if (v == null) return finish();
				double z = bz + 30 + Math.max(0, ticks - 40) * 0.4;
				v.setPos(bx, boatY, z);
				v.setDeltaMovement(0, 0, 0);
				//? if >=1.17 {
				v.setYRot(0f);
				mc.player.setYRot(0f);
				//?} else {
				/*v.yRot = 0f;
				mc.player.yRot = 0f;
				*///?}
				ticks++;
				int tp = teleports(f, me);
				if (tp != lastTeleports || ticks % 40 == 0) {
					logBoat(f, mc, v, String.format(java.util.Locale.ROOT, "Tick %d, Boot z %.1f, Teleports %d", ticks, Mc.z(v) - bz,
							tp - startTeleports));
					lastTeleports = tp;
				}
				if (ticks < 150) camera(mc, 18F, 26F);
				if (ticks == 150) {
					actions.shot("trsclient-fix-boat-follow");
					logBoat(f, mc, v, "Fahrt");
				}
				if (ticks == 151) camera(mc, 95F, 18F);
				if (ticks == 165) actions.shot("trsclient-fix-boat-side");
				if (ticks == 166) camera(mc, 160F, 30F);
				if (ticks == 182) {
					actions.shot("trsclient-fix-boat-front");
					logBoat(f, mc, v, "Ende");
					TrsClient.LOGGER.info("[Autotest] Fix: Boot – Teleports während der Fahrt: {}", teleports(f, me) - startTeleports);
					actions.command("ride @p dismount");
					return finish();
				}
				return true;
			}
			default:
				return finish();
		}
	}

	private static String boatType() {
		//? if >=1.21.2 {
		return "oak_boat";
		//?} else
		/*return "boat";*/
	}

	private boolean finish() {
		PetSim.frozen = false;
		PetSim.forceState = -1;
		PetSim.forceTimeMs = -1;
		OnlineFeatures.v2Clock = null;
		dev.theredstonee.trsclient.core.cosmetic.v2.WingState.forced = -1;
		DevPets.forcedCount = 0;
		PetSim.forceSlide = Double.NaN;
		TrsClient.get().setForceZoom(false);
		phase = 99;
		return false;
	}

	private static int teleports(OnlineFeatures<Object> f, java.util.UUID me) {
		int n = 0;
		for (Pet p : f.pets().pets(me)) n += p.teleports;
		return n;
	}

	private static void logBoat(OnlineFeatures<Object> f, Minecraft mc, net.minecraft.world.entity.Entity v, String what) {
		StringBuilder sb = new StringBuilder();
		for (Pet p : f.pets().pets(mc.player.getUUID())) {
			sb.append(p.stateName()).append(String.format(java.util.Locale.ROOT, " %.1f Blöcke; ",
					Math.hypot(p.x(1f) - Mc.x(v), p.z(1f) - Mc.z(v))));
		}
		TrsClient.LOGGER.info("[Autotest] Fix: Boot {} – {}", what, sb);
	}

	/** Spieler hinstellen und Blick (Gieren, Nicken) setzen. */
	private void look(Minecraft mc, double dx, double dz, float yaw, float pitch) {
		place(mc, dx, dz, yaw);
		//? if >=1.17 {
		mc.player.setXRot(pitch);
		//?} else
		/*mc.player.xRot = pitch;*/
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
