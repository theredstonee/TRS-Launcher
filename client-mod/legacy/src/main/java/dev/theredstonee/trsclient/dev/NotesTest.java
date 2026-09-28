package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.notes.Notes;
import dev.theredstonee.trsclient.core.notes.NotesSelfTest;
import dev.theredstonee.trsclient.core.ui.menu.ModMenu;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Selbsttest „Notizen je Welt“ unter 1.8.9–1.12.2 ({@code -PtrsAutotestOnly=notes}): eigene Testwelt, Beispiel-Notizen,
 * angeheftete Notiz im HUD, Liste/Lesen/Bearbeiten/Suche/andere Welten, Koordinaten-Menü, vorübergehender Wegpunkt,
 * Weltkarte. Bilder: trsclient-&lt;mc&gt;-notes-*.png. Ohne Netz.
 */
public final class NotesTest {
	private final String mcVersion = Mc.version();
	private final String world = "trs-notes-" + Mc.version();
	private final NotesSelfTest self = new NotesSelfTest();
	private int phase = -1;
	private int wait;
	private int waited;
	private dev.theredstonee.trsclient.core.config.TrsConfig before;

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new NotesTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft mc = Minecraft.getMinecraft();
		if (phase > 0 && phase < 999 && mc.currentScreen instanceof GuiIngameMenu) mc.displayGuiScreen(null);
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			tick(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Notizen: Fehler", e);
			phase = 999;
			mc.shutdown();
		}
	}

	private void tick(Minecraft mc) {
		GuiScreen screen = mc.currentScreen;
		TrsModules modules = TrsClient.get().modules();
		String me = mc.getSession().getUsername();
		switch (phase) {
			case -1:
				if (!(screen instanceof TrsTitleScreen) && !(screen instanceof GuiMainMenu)) return;
				mc.gameSettings.pauseOnLostFocus = false;
				mc.getSaveLoader().deleteWorldDirectory(world);
				mc.launchIntegratedServer(world, world, Mc.creativeWorld(20260928L));
				phase++;
				return;
			case 0: {
				if (Mc.world() == null || Mc.player() == null || mc.currentScreen != null) {
					if (waited++ > 900) throw new IllegalStateException("Welt lädt nicht");
					return;
				}
				before = modules.registry.capture();
				command(mc, "gamerule sendCommandFeedback false");
				command(mc, "gamerule logAdminCommands false");
				command(mc, "time set 1000");
				command(mc, "weather clear");
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.notes.notes.setEnabled(true);
				modules.waypoints.setEnabled(true);
				modules.worldMap.setEnabled(true);
				boolean ok = self.seed();
				TrsClient.LOGGER.info("[Autotest] Notizen angelegt: {} – {}", ok, self.summary());
				if (!ok) throw new IllegalStateException("keine Welt für Notizen");
				phase++;
				wait = 40;
				return;
			}
			case 1:
				shot(mc, "notes-hud");
				Notes.requestOpen();
				mc.displayGuiScreen(new TrsMenuScreen(null));
				phase++;
				wait = 20;
				return;
			case 2:
				TrsClient.LOGGER.info("[Autotest] Notiz-Seite: {}", menu(mc) == null ? "FEHLT" : menu(mc).notesPage().viewName());
				shot(mc, "notes-list");
				if (menu(mc) != null) menu(mc).notesPage().showNote(self.world().key(), self.main().id, false);
				phase++;
				wait = 10;
				return;
			case 3:
				shot(mc, "notes-view");
				if (menu(mc) != null) menu(mc).notesPage().testPopup(self.target()[0], self.target()[1], self.target()[2], 200, 120);
				phase++;
				wait = 6;
				return;
			case 4:
				shot(mc, "notes-coords");
				if (menu(mc) != null) {
					menu(mc).notesPage().back();
					menu(mc).notesPage().showNote(self.world().key(), self.main().id, true);
				}
				phase++;
				wait = 10;
				return;
			case 5:
				shot(mc, "notes-edit");
				if (menu(mc) != null) menu(mc).notesPage().testSearch("redstone");
				phase++;
				wait = 6;
				return;
			case 6:
				shot(mc, "notes-search");
				if (menu(mc) != null) menu(mc).notesPage().testWorlds();
				phase++;
				wait = 6;
				return;
			case 7: {
				shot(mc, "notes-worlds");
				int[] t = self.target();
				Notes.Outcome o = Notes.get().temporaryWaypoint(self.world(), self.main().displayTitle(), t[0], t[1], t[2]);
				TrsClient.LOGGER.info("[Autotest] Vorübergehender Wegpunkt: {} {}", o.ok, o.message);
				mc.displayGuiScreen(null);
				phase++;
				wait = 20;
				return;
			}
			case 8: {
				shot(mc, "notes-waypoint");
				int[] t = self.target();
				Notes.Outcome o = Notes.get().showOnMap(self.world(), self.main().displayTitle(), t[0], t[2]);
				TrsClient.LOGGER.info("[Autotest] Auf Weltkarte: {} {}", o.ok, o.message);
				phase++;
				wait = 30;
				return;
			}
			case 9:
				shot(mc, "notes-map");
				mc.displayGuiScreen(null);
				TrsClient.LOGGER.info("[Autotest] Notizen: {}", self.summary());
				command(mc, "tp " + me + " " + self.target()[0] + " " + self.target()[1] + " " + self.target()[2]);
				phase++;
				wait = 20;
				return;
			case 10:
				TrsClient.LOGGER.info("[Autotest] Wegpunkt nach Erreichen: {}", Notes.get().temporary() == null ? "entfernt" : "NOCH DA");
				self.cleanup();
				if (before != null) {
					modules.registry.apply(before);
					TrsClient.get().saveConfig();
				}
				TrsClient.LOGGER.info("[Autotest] Notizen: fertig");
				phase = 999;
				mc.displayGuiScreen(new GuiMainMenu());
				wait = 10;
				return;
			case 999:
				mc.shutdown();
				return;
			default:
		}
	}

	private static ModMenu menu(Minecraft mc) {
		GuiScreen s = mc.currentScreen;
		if (!(s instanceof TrsUiScreen)) return null;
		Object ui = ((TrsUiScreen) s).ui();
		return ui instanceof ModMenu ? (ModMenu) ui : null;
	}

	private static void command(Minecraft mc, final String command) {
		final IntegratedServer server = mc.getIntegratedServer();
		if (server == null) return;
		server.addScheduledTask(new Runnable() {
			@Override
			public void run() {
				server.getCommandManager().executeCommand(server, command);
			}
		});
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		TrsClient.LOGGER.info("[Autotest] Screenshot {}", file);
	}
}
