package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Hat;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.wardrobe.WardrobeUi;
import dev.theredstonee.trsclient.core.wardrobe.CosmeticCatalog;
import dev.theredstonee.trsclient.core.wardrobe.WardrobeContext;
import dev.theredstonee.trsclient.online.OnlineHooks;
import dev.theredstonee.trsclient.screen.WardrobeScreen;
import net.minecraft.client.Minecraft;

/**
 * Selbsttest „Kopf-Kosmetik v2“ ({@code -PtrsAutotestOnly=cosmetics2}, mit der v2-Attrappe
 * {@code scratchpad/cos2mod/mock-api.mjs}): setzt über den Katalog ({@code PUT /v1/me/cosmetics}) nacheinander alle
 * sechs Studio-Teile auf, wartet, bis Modell und Texturen geladen sind, und fotografiert jedes in der 3. Person von vorne,
 * schräg und hinten – bei Tag und bei Nacht (Kamera per Freelook um die Figur, Zoom auf den Kopf). Dazu: mit Helm
 * (unsichtbar) und die Garderobe (Reiter „Kosmetik“ bei Tag/Nacht, gesperrtes Teil).
 */
public final class Cosmetics2Test {
	private static final String[] IDS = { "redstone_crown", "team_crown", "trs_cap", "lamp_helmet", "top_hat", "halo" };

	private int phase;
	private int wait;
	private int tries;
	private int item;
	private boolean night;
	private CosmeticCatalog catalog;
	private WardrobeUi ui;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (mc.player == null) return false;
		face(mc);
		if (wait > 0) {
			wait--;
			return true;
		}
		switch (phase) {
			case 0:
				actions.command("gamerule logAdminCommands false");
				actions.command("gamerule sendCommandFeedback false");
				actions.command("time set day");
				actions.command("weather clear");
				actions.command("effect clear @p");
				//? if >=1.17 {
				actions.command("item replace entity @p armor.head with air");
				//?} else
				/*actions.command("replaceitem entity @p armor.head air");*/
				actions.command("execute as @p at @s run fill ~-8 ~ ~-8 ~8 ~8 ~8 air");
				actions.command("execute as @p at @s run fill ~-8 ~-1 ~-8 ~8 ~-1 ~8 grass_block");
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.crosshair.setEnabled(false);
				modules.fullbright.setEnabled(false);
				Mc.setScreen(null);
				Mc.setHudHidden(true);
				catalog = CosmeticCatalog.shared(new WardrobeContext(I18n.configDir(), "TRS-Client", OnlineHooks.features()));
				catalog.refresh(true);
				phase = 1;
				wait = 20;
				return true;
			case 1: {
				CosmeticCatalog.State s = catalog.state();
				if (!s.loaded && tries++ < 400) {
					if (tries % 100 == 0) catalog.refresh(true);
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] Kosmetik: Katalog {} ({} Teile, getragen {}) nach {} Ticks",
						s.loaded ? "geladen" : "FEHLT", s.items.size(), s.equipped, tries);
				dev.theredstonee.trsclient.compat.ChatLines.chat().clearMessages(false);
				tries = 0;
				item = 0;
				phase = 2;
				return true;
			}
			case 2:
				// Teil aufsetzen (über die API wie in der Garderobe)
				catalog.wear(IDS[item]);
				night = false;
				tries = 0;
				phase = 3;
				wait = 5;
				return true;
			case 3: {
				V2Hat<Object> hat = OnlineHooks.features() == null ? null : OnlineHooks.features().hatV2(mc.player.getUUID(), false);
				boolean ok = hat != null && IDS[item].equals(hat.model.id);
				if (!ok && tries++ < 600) {
					// eigener Lookup wird nach dem Aufsetzen aufgefrischt; zur Not noch einmal
					if (tries % 200 == 0) OnlineHooks.features().online().refreshSelf();
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] Kosmetik {}: {} nach {} Ticks", IDS[item], ok ? "geladen" : "FEHLT", tries);
				TrsClient.get().setForceZoom(true);
				TrsClient.get().pvp().forceFreelook(180F);
				phase = 4;
				wait = 4;
				return true;
			}
			case 4:
				camera(mc, 180F, 0F);
				phase = 5;
				wait = 8;
				return true;
			case 5:
				actions.shot("trsclient-cos2-" + IDS[item] + (night ? "-night" : "-day") + "-front");
				camera(mc, 142F, 14F);
				phase = 6;
				wait = 4;
				return true;
			case 6:
				actions.shot("trsclient-cos2-" + IDS[item] + (night ? "-night" : "-day") + "-oblique");
				camera(mc, 0F, 8F);
				phase = 7;
				wait = 4;
				return true;
			case 7:
				actions.shot("trsclient-cos2-" + IDS[item] + (night ? "-night" : "-day") + "-back");
				if (!night) {
					night = true;
					actions.command("time set midnight");
					camera(mc, 180F, 0F);
					phase = 5;
					wait = 12;
					return true;
				}
				actions.command("time set day");
				item++;
				phase = item < IDS.length ? 2 : 8;
				wait = 6;
				return true;
			case 8:
				// Helm-Regel: v2 bleibt mit Helm unsichtbar
				catalog.wear("redstone_crown");
				//? if >=1.17 {
				actions.command("item replace entity @p armor.head with iron_helmet");
				//?} else
				/*actions.command("replaceitem entity @p armor.head iron_helmet");*/
				camera(mc, 142F, 14F);
				phase = 9;
				wait = 40;
				return true;
			case 9:
				actions.shot("trsclient-cos2-helmet");
				//? if >=1.17 {
				actions.command("item replace entity @p armor.head with air");
				//?} else
				/*actions.command("replaceitem entity @p armor.head air");*/
				TrsClient.get().pvp().forceFreelook(Float.NaN);
				TrsClient.get().setForceZoom(false);
				Mc.setHudHidden(false);
				phase = 10;
				wait = 5;
				return true;
			case 10:
				// Garderobe, Reiter „Kosmetik“
				Mc.setScreen(WardrobeScreen.create(null));
				ui = WardrobeScreen.last;
				ui.category("cosmetics");
				ui.selectCosmetic("redstone_crown");
				tries = 0;
				phase = 11;
				wait = 10;
				return true;
			case 11: {
				CosmeticCatalog.State s = catalog.state();
				boolean ready = s.previews.containsKey("redstone_crown") && s.cards.size() >= IDS.length;
				if (!ready && tries++ < 300) return true;
				TrsClient.LOGGER.info("[Autotest] Garderobe Kosmetik: Vorschau {}, {} Karten", s.previews.containsKey("redstone_crown"),
						s.cards.size());
				phase = 12;
				wait = 20;
				return true;
			}
			case 12:
				actions.shot("trsclient-cos2-wardrobe-day");
				ui.cosmeticNight(true);
				phase = 13;
				wait = 10;
				return true;
			case 13:
				actions.shot("trsclient-cos2-wardrobe-night");
				ui.cosmeticNight(false);
				ui.selectCosmetic("halo");
				tries = 0;
				phase = 14;
				wait = 5;
				return true;
			case 14:
				if (!catalog.state().previews.containsKey("halo") && tries++ < 200) return true;
				phase = 15;
				wait = 15;
				return true;
			case 15:
				actions.shot("trsclient-cos2-wardrobe-locked");
				ui.selectCosmetic("top_hat");
				tries = 0;
				phase = 16;
				wait = 5;
				return true;
			case 16:
				if (!catalog.state().previews.containsKey("top_hat") && tries++ < 200) return true;
				phase = 17;
				wait = 15;
				return true;
			case 17:
				actions.shot("trsclient-cos2-wardrobe-tophat");
				// Format 1 (Quietscheente) in der Garderobe und im Spiel – darf durch v2 nicht kaputtgehen
				ui.selectCosmetic("rubber_duck");
				tries = 0;
				phase = 18;
				wait = 5;
				return true;
			case 18:
				if (!catalog.state().previews.containsKey("rubber_duck") && tries++ < 200) return true;
				phase = 19;
				wait = 15;
				return true;
			case 19:
				actions.shot("trsclient-cos2-wardrobe-duck");
				Mc.setScreen(null);
				catalog.wear("rubber_duck");
				tries = 0;
				phase = 20;
				wait = 5;
				return true;
			case 20: {
				Object tex = OnlineHooks.features() == null ? null : OnlineHooks.features().hatTexture(mc.player.getUUID());
				if (tex == null && tries++ < 400) return true;
				TrsClient.LOGGER.info("[Autotest] Kosmetik rubber_duck (v1): {} nach {} Ticks", tex != null ? "geladen" : "FEHLT", tries);
				Mc.setHudHidden(true);
				TrsClient.get().setForceZoom(true);
				TrsClient.get().pvp().forceFreelook(180F);
				phase = 21;
				wait = 4;
				return true;
			}
			case 21:
				camera(mc, 150F, 10F);
				phase = 22;
				wait = 10;
				return true;
			case 22:
				actions.shot("trsclient-cos2-duck-oblique");
				TrsClient.get().pvp().forceFreelook(Float.NaN);
				TrsClient.get().setForceZoom(false);
				Mc.setHudHidden(false);
				catalog.wear("redstone_crown");
				phase = 23;
				wait = 5;
				return true;
			default:
				return false;
		}
	}

	/** Figur schaut stur nach Süden (Yaw 0), Kopf gerade – die Kamera kreist per Freelook. */
	private static void face(Minecraft mc) {
		//? if >=1.17 {
		mc.player.setYRot(0);
		mc.player.setXRot(0);
		//?} else {
		/*mc.player.yRot = 0;
		mc.player.xRot = 0;
		*///?}
		mc.player.yHeadRot = 0;
		mc.player.yBodyRot = 0;
	}

	/** Kamera: Freelook-Winkel relativ zur Figur ({@code 180} = von vorne, {@code 0} = von hinten), 3. Person. */
	private static void camera(Minecraft mc, float yawOffset, float pitch) {
		TrsClient.get().pvp().freelook().start(yawOffset, pitch);
		//? if >=1.17 {
		mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
		//?}
	}
}
