package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.panorama.Panorama;
import net.minecraft.client.Minecraft;
//? if >=1.20.5 {
import dev.theredstonee.trsclient.mixin.MouseHandlerAccessor;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.Slot;
//?}

/**
 * Selbsttest „Komfort 2“ ({@code -PtrsAutotestOnly=comfort}, nur ab 1.20.5 – Befehlssyntax der Item-Komponenten):
 * Tooltips (Shulker-Raster, Essen, Schwert mit Haltbarkeit + kompakten Verzauberungen, Karte), Server-Profil für
 * Einzelspieler (Wechsel beim Betreten, Seite im Menü) und Panorama. Screenshots trsclient-&lt;mc&gt;-comfort-*.png.
 */
public final class ComfortTest {
	private int phase;
	private int wait;
	private int panoramaTicks;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		//? if >=1.20.5 {
		if (wait > 0) {
			wait--;
			if (Mc.screen() instanceof InventoryScreen && hover >= 0) hover(mc, (InventoryScreen) Mc.screen(), hover);
			return true;
		}
		switch (phase++) {
			case 0: {
				if (mc.player == null) return false;
				actions.command("gamerule sendCommandFeedback false");
				actions.command("gamemode survival @p");
				actions.command("time set day");
				actions.command("weather clear");
				actions.command("clear @p");
				actions.command("item replace entity @p weapon.mainhand with map");
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.dynamicFps.setEnabled(false);
				modules.comfort.tooltips.setEnabled(true);
				modules.comfort.serverProfiles.setEnabled(true);
				modules.comfort.panorama.setEnabled(true);
				Mc.setScreen(null);
				wait = 30;
				return true;
			}
			case 1:
				// Leere Karte benutzen → gefüllte Karte der Umgebung (Server schickt die Kartendaten).
				mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
				wait = 100;
				return true;
			case 2:
				actions.command("item replace entity @p hotbar.3 from entity @p weapon.mainhand");
				actions.command("item replace entity @p hotbar.0 with red_shulker_box[container=["
						+ "{slot:0,item:{id:\"minecraft:diamond\",count:64}},{slot:1,item:{id:\"minecraft:golden_apple\",count:12}},"
						+ "{slot:4,item:{id:\"minecraft:ender_pearl\",count:16}},{slot:10,item:{id:\"minecraft:diamond_pickaxe\",count:1}},"
						+ "{slot:13,item:{id:\"minecraft:redstone\",count:48}},{slot:22,item:{id:\"minecraft:totem_of_undying\",count:1}},"
						+ "{slot:26,item:{id:\"minecraft:oak_log\",count:32}}]]");
				actions.command("item replace entity @p hotbar.1 with cooked_beef 16");
				//? if >=1.21.5 {
				/*actions.command("item replace entity @p hotbar.2 with diamond_sword[damage=700,enchantments={\"minecraft:sharpness\":5,"
						+ "\"minecraft:unbreaking\":3,\"minecraft:mending\":1,\"minecraft:looting\":3,\"minecraft:fire_aspect\":2}]");
				*///?} else {
				actions.command("item replace entity @p hotbar.2 with diamond_sword[damage=700,enchantments={levels:{\"minecraft:sharpness\":5,"
						+ "\"minecraft:unbreaking\":3,\"minecraft:mending\":1,\"minecraft:looting\":3,\"minecraft:fire_aspect\":2}}]");
				//?}
				wait = 20;
				return true;
			case 3:
				mc.mouseHandler.releaseMouse();
				Mc.setScreen(new InventoryScreen(mc.player));
				hover = 36;
				wait = 20;
				return true;
			case 4:
				actions.shot("trsclient-comfort-shulker");
				hover = 37;
				wait = 8;
				return true;
			case 5:
				actions.shot("trsclient-comfort-food");
				hover = 38;
				wait = 8;
				return true;
			case 6:
				actions.shot("trsclient-comfort-sword");
				hover = 39;
				wait = 8;
				return true;
			case 7:
				actions.shot("trsclient-comfort-map");
				hover = -1;
				Mc.setScreen(null);
				dev.theredstonee.trsclient.compat.ChatLines.chat().clearMessages(false);
				// Server-Profil „Einzelspieler“: Anzeigen an, merken, dann Welt „neu betreten“ (Standard → Profil).
				modules.fps.setEnabled(true);
				modules.coords.setEnabled(true);
				modules.clock.setEnabled(true);
				String error = modules.serverProfiles.rememberCurrent();
				TrsClient.LOGGER.info("[Autotest] Server-Profil merken: {} → {}", error == null ? "ok" : error,
						modules.serverProfiles.active() == null ? "-" : modules.serverProfiles.active().name());
				modules.serverProfiles.update(null);
				TrsClient.LOGGER.info("[Autotest] Standard nach Verlassen: fps={} coords={}", modules.fps.isEnabled(), modules.coords.isEnabled());
				wait = 6;
				return true;
			case 8:
				TrsClient.LOGGER.info("[Autotest] Profil nach Betreten: {} fps={} coords={}",
						modules.serverProfiles.active() == null ? "-" : modules.serverProfiles.active().name(), modules.fps.isEnabled(),
						modules.coords.isEnabled());
				actions.shot("trsclient-comfort-profile");
				Mc.setScreen(new TrsMenuScreen(null).showServerProfiles());
				wait = 20;
				return true;
			case 9:
				actions.shot("trsclient-comfort-profile-menu");
				Mc.setScreen(new TrsMenuScreen(null).select(modules.comfort.panorama));
				wait = 20;
				return true;
			case 10:
				actions.shot("trsclient-comfort-panorama-page");
				Mc.setScreen(null);
				actions.command("tp @p ~ ~ ~ 45 0");
				wait = 20;
				return true;
			case 11:
				Panorama.get().request(true, true);
				panoramaTicks = 0;
				return true;
			case 12:
				panoramaTicks++;
				if (Panorama.get().state() != Panorama.State.IDLE && panoramaTicks < 1200) {
					phase = 12;
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] Panorama: {} Ticks, Ordner {}", panoramaTicks, Panorama.get().lastFolder());
				wait = 5;
				return true;
			case 13:
				actions.shot("trsclient-comfort-panorama-toast");
				modules.serverProfiles.delete(0);
				return false;
			default:
				return false;
		}
		//?} else
		/*return false;*/
	}

	//? if >=1.20.5 {
	/** Menü-Feld, über dem die Maus steht (-1 = keins). */
	private int hover = -1;

	/** Maus über das Feld {@code index} des Inventars stellen (über Minecrafts Mausposition). */
	private static void hover(Minecraft mc, InventoryScreen screen, int index) {
		Slot slot = screen.getMenu().slots.get(index);
		int left = (screen.width - 176) / 2, top = (screen.height - 166) / 2;
		double gx = left + slot.x + 8, gy = top + slot.y + 8;
		double sx = gx * Mc.window().getScreenWidth() / Math.max(1, Mc.window().getGuiScaledWidth());
		double sy = gy * Mc.window().getScreenHeight() / Math.max(1, Mc.window().getGuiScaledHeight());
		((MouseHandlerAccessor) mc.mouseHandler).trsclient$setXpos(sx);
		((MouseHandlerAccessor) mc.mouseHandler).trsclient$setYpos(sy);
	}
	//?}
}
