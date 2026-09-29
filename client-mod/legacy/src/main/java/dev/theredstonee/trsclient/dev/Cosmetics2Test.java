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
import dev.theredstonee.trsclient.online.LegacyOnline;
import dev.theredstonee.trsclient.screen.WardrobeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Selbsttest „Kopf-Kosmetik v2“ für Forge 1.8.9–1.12.2 ({@code -PtrsAutotestOnly=cosmetics2}, Attrappe
 * {@code scratchpad/cos2mod/mock-api.mjs}): wie der Fabric-Test – alle sechs Studio-Teile aufsetzen und von vorne,
 * schräg und hinten bei Tag und Nacht fotografieren, dazu Garderobe (Reiter „Kosmetik“) und Quietscheente.
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
		EntityPlayerSP player = Mc.player();
		if (player == null) return false;
		face(player);
		if (wait > 0) {
			wait--;
			return true;
		}
		String name = player.getName();
		switch (phase) {
			case 0:
				actions.command("gamerule logAdminCommands false");
				actions.command("gamerule sendCommandFeedback false");
				actions.command("time set 1000");
				actions.command("weather clear");
				actions.command("effect " + name + " clear");
				actions.command("replaceitem entity " + name + " slot.armor.head minecraft:air");
				actions.command("execute " + name + " ~ ~ ~ fill ~-8 ~ ~-8 ~8 ~8 ~8 minecraft:air");
				actions.command("execute " + name + " ~ ~ ~ fill ~-8 ~-1 ~-8 ~8 ~-1 ~8 minecraft:grass");
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.crosshair.setEnabled(false);
				modules.fullbright.setEnabled(false);
				mc.displayGuiScreen(null);
				mc.gameSettings.hideGUI = true;
				catalog = CosmeticCatalog.shared(new WardrobeContext(I18n.configDir(), "TRS-Client", LegacyOnline.features()));
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
				tries = 0;
				item = 0;
				phase = 2;
				return true;
			}
			case 2:
				catalog.wear(IDS[item]);
				night = false;
				tries = 0;
				phase = 3;
				wait = 5;
				return true;
			case 3: {
				V2Hat<Object> hat = LegacyOnline.features() == null ? null : LegacyOnline.features().hatV2(player.getUniqueID(), false);
				boolean ok = hat != null && IDS[item].equals(hat.model.id);
				if (!ok && tries++ < 600) {
					if (tries % 200 == 0) LegacyOnline.features().online().refreshSelf();
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
				actions.shot("cos2-" + IDS[item] + (night ? "-night" : "-day") + "-front");
				camera(mc, 142F, 14F);
				phase = 6;
				wait = 4;
				return true;
			case 6:
				actions.shot("cos2-" + IDS[item] + (night ? "-night" : "-day") + "-oblique");
				camera(mc, 0F, 8F);
				phase = 7;
				wait = 4;
				return true;
			case 7:
				actions.shot("cos2-" + IDS[item] + (night ? "-night" : "-day") + "-back");
				if (!night) {
					night = true;
					actions.command("time set 18000");
					camera(mc, 180F, 0F);
					phase = 5;
					wait = 14;
					return true;
				}
				actions.command("time set 1000");
				item++;
				phase = item < IDS.length ? 2 : 9;
				wait = 6;
				return true;
			case 9:
				TrsClient.get().pvp().forceFreelook(Float.NaN);
				TrsClient.get().setForceZoom(false);
				mc.gameSettings.hideGUI = false;
				phase = 10;
				wait = 5;
				return true;
			case 10:
				mc.displayGuiScreen(WardrobeScreen.create(null));
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
				actions.shot("cos2-wardrobe-day");
				ui.cosmeticNight(true);
				phase = 13;
				wait = 10;
				return true;
			case 13:
				actions.shot("cos2-wardrobe-night");
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
				actions.shot("cos2-wardrobe-locked");
				mc.displayGuiScreen(null);
				// Format 1 (Quietscheente) im Spiel – darf durch v2 nicht kaputtgehen
				catalog.wear("rubber_duck");
				tries = 0;
				phase = 16;
				wait = 5;
				return true;
			case 16: {
				Object tex = LegacyOnline.features() == null ? null : LegacyOnline.features().hatTexture(player.getUniqueID());
				if (tex == null && tries++ < 400) return true;
				TrsClient.LOGGER.info("[Autotest] Kosmetik rubber_duck (v1): {} nach {} Ticks", tex != null ? "geladen" : "FEHLT", tries);
				mc.gameSettings.hideGUI = true;
				TrsClient.get().setForceZoom(true);
				TrsClient.get().pvp().forceFreelook(180F);
				phase = 17;
				wait = 4;
				return true;
			}
			case 17:
				camera(mc, 150F, 10F);
				phase = 18;
				wait = 10;
				return true;
			case 18:
				actions.shot("cos2-duck-oblique");
				TrsClient.get().pvp().forceFreelook(Float.NaN);
				TrsClient.get().setForceZoom(false);
				mc.gameSettings.hideGUI = false;
				catalog.wear("redstone_crown");
				phase = 19;
				wait = 5;
				return true;
			default:
				return false;
		}
	}

	/** Figur schaut stur nach Süden (Yaw 0), Kopf gerade – die Kamera kreist per Freelook. */
	private static void face(EntityPlayerSP p) {
		p.rotationYaw = 0F;
		p.prevRotationYaw = 0F;
		p.rotationPitch = 0F;
		p.prevRotationPitch = 0F;
		p.rotationYawHead = 0F;
		p.prevRotationYawHead = 0F;
		p.renderYawOffset = 0F;
		p.prevRenderYawOffset = 0F;
	}

	/** Kamera: Freelook-Winkel relativ zur Figur ({@code 180} = von vorne, {@code 0} = von hinten), 3. Person. */
	private static void camera(Minecraft mc, float yawOffset, float pitch) {
		TrsClient.get().pvp().freelook().start(yawOffset, pitch);
		mc.gameSettings.thirdPersonView = 1;
	}
}
