package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.menus.ServerPins;
import dev.theredstonee.trsclient.core.online.Friends;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.clips.ClipPreview;
import dev.theredstonee.trsclient.core.ui.clips.ClipsUi;
import dev.theredstonee.trsclient.core.ui.friends.FriendsUi;
import dev.theredstonee.trsclient.core.ui.menus.MenuSkin;
import dev.theredstonee.trsclient.screen.MenuScreens;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
//? if >=1.21 {
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
//?} else {
/*import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.VideoSettingsScreen;
*///?}

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Selbsttest Menüs ({@code -PtrsAutotestOnly=menus}): Serverliste (angeheftet, Freunde auf dem Server),
 * Einstellungen, Weltenliste, Freunde (mit API-Attrappe), Clips &amp; Bilder, Ressourcen-Überblendung,
 * Ladebildschirm, Pausenmenü mit TRS-Knöpfen, Server-Info. Beendet das Spiel direkt nach den Bildern.
 * Screenshots: trsclient-&lt;mc&gt;-menus-*.png.
 */
public final class MenusTest {
	private static final String FRIEND_SERVER = "play.trs-test.net";
	private int phase;
	private int wait;
	private int waited;
	private Path clipDir;
	private Path clipsJson;
	/** Unterschritte in Phase 12 (Bild groß, Clip-Vorschau). */
	private int clipStep;

	public static void install() {
		MenusTest test = new MenusTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Menüs: {}", text);
	}

	private void shot(Minecraft mc, String name) {
		AutoTest.shot(mc, "trsclient-menus-" + name);
		log("Bild " + name + " (Hintergrund Ø " + Math.round(MenuSkin.averageMicros()) + " µs)");
	}

	private boolean waitFor(boolean ready, int maxTries) {
		if (!ready && waited++ < maxTries) {
			wait = 5;
			return false;
		}
		waited = 0;
		return true;
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			step(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Menüs: Fehler in Phase {}", phase, e);
			cleanup();
			mc.stop();
			phase = 999;
		}
	}

	private void step(Minecraft mc) {
		Screen screen = Mc.screen();
		switch (phase) {
			case 0:
				if (Mc.overlay() != null || screen == null) return;
				mc.options.pauseOnLostFocus = false;
				//? if >=1.19 {
				mc.options.renderDistance().set(2);
				//?}
				fixtures(mc);
				Mc.setScreen(new TrsTitleScreen());
				phase++;
				wait = 10;
				return;
			case 1:
				Mc.setScreen(new JoinMultiplayerScreen(new TrsTitleScreen()));
				phase++;
				wait = 60;
				return;
			case 2:
				shot(mc, "multiplayer");
				Mc.setScreen(options(new TrsTitleScreen(), mc));
				phase++;
				wait = 15;
				return;
			case 3:
				shot(mc, "options");
				//? if >=1.21 {
				Mc.setScreen(new VideoSettingsScreen(screen, mc, mc.options));
				//?} else
				/*Mc.setScreen(new VideoSettingsScreen(screen, mc.options));*/
				phase++;
				wait = 15;
				return;
			case 4:
				shot(mc, "options-video");
				Mc.setScreen(new SelectWorldScreen(new TrsTitleScreen()));
				phase++;
				wait = 30;
				return;
			case 5:
				shot(mc, "worlds");
				Mc.setScreen(MenuScreens.friends(new TrsTitleScreen()));
				phase++;
				wait = 10;
				return;
			case 6: {
				TrsOnline online = TrsOnline.current();
				Friends.Snapshot s = online == null ? null : online.friends().snapshot();
				if (!waitFor(s != null && s.view != null, 120)) return;
				wait = 40; // Gesichter
				phase++;
				return;
			}
			case 7:
				shot(mc, "friends");
				friendsUi(screen).showTab(1);
				phase++;
				wait = 10;
				return;
			case 8:
				shot(mc, "friends-requests");
				friendsUi(screen).showTab(2);
				phase++;
				wait = 30;
				return;
			case 9:
				shot(mc, "friends-blocked");
				Mc.setScreen(MenuScreens.clips(new TrsTitleScreen()));
				phase++;
				wait = 10;
				return;
			case 10:
				if (!waitFor(clipsUi(Mc.screen()) != null && clipsUi(Mc.screen()).settled(), 100)) return;
				wait = 10;
				phase++;
				return;
			case 11:
				shot(mc, "clips");
				clipsUi(screen).openFirstImage();
				phase++;
				wait = 25;
				return;
			case 12:
				// Bild groß → Clip-Vorschau (über den TRS-Link, falls das Spiel mit Launcher-Attrappe läuft) → weiter.
				if (clipStep == 0) {
					shot(mc, "clips-preview");
					Mc.setScreen(MenuScreens.clips(new TrsTitleScreen()));
					clipStep = 1;
					wait = 20;
					return;
				}
				if (clipStep == 1) {
					if (clipsUi(screen) != null) clipsUi(screen).openFirstClip();
					clipStep = 2;
					wait = 5;
					return;
				}
				if (clipStep == 2) {
					ClipsUi ui = clipsUi(screen);
					ClipPreview.State st = ui == null ? null : ui.clipPreview().state();
					if (!waitFor(st == null || (st != ClipPreview.State.LOADING && st != ClipPreview.State.IDLE), 200)) return;
					log("Clip-Vorschau: " + st + (ui == null ? "" : " " + ui.clipPreview().code()));
					clipStep = 3;
					wait = 25; // Zeitraffer ein paar Bilder laufen lassen
					return;
				}
				shot(mc, "clips-clip");
				Mc.setScreen(MenuScreens.clips(new TrsTitleScreen()));
				phase++;
				wait = 20;
				return;
			case 13:
				if (clipsUi(screen) != null) clipsUi(screen).confirmFirst();
				phase++;
				wait = 5;
				return;
			case 14:
				shot(mc, "clips-delete");
				Mc.setScreen(new TrsTitleScreen());
				mc.reloadResourcePacks();
				phase++;
				wait = 3;
				return;
			case 15:
				if (!waitFor(Mc.overlay() != null, 40)) return;
				shot(mc, "reload");
				phase++;
				return;
			case 16:
				if (!waitFor(Mc.overlay() == null, 400)) return;
				AutoTest.startWorld(mc);
				phase++;
				return;
			case 17:
				// Ladebildschirm abpassen (während die Welt entsteht bzw. lädt).
				if (screen instanceof LevelLoadingScreen) {
					wait = 2;
					phase++;
					return;
				}
				if (mc.level != null && waited++ > 20) {
					log("Ladebildschirm verpasst");
					phase += 2;
				}
				return;
			case 18:
				if (screen instanceof LevelLoadingScreen) shot(mc, "loading");
				phase++;
				return;
			case 19:
				if (!waitFor(mc.level != null && mc.player != null && Mc.screen() == null, 600)) return;
				wait = 40;
				phase++;
				return;
			case 20:
				Mc.setScreen(new PauseScreen(true));
				phase++;
				wait = 15;
				return;
			case 21:
				shot(mc, "pause");
				Mc.setScreen(MenuScreens.serverInfo(screen));
				phase++;
				wait = 15;
				return;
			case 22:
				shot(mc, "serverinfo");
				Mc.setScreen(options(new PauseScreen(true), mc));
				phase++;
				wait = 15;
				return;
			case 23:
				shot(mc, "options-ingame");
				log("fertig");
				cleanup();
				phase++;
				mc.stop();
				return;
			default:
		}
	}

	private static Screen options(Screen parent, Minecraft mc) {
		//? if >=26.1 && <26.3 {
		/*return new OptionsScreen(parent, mc.options, false);
		*///?} else
		return new OptionsScreen(parent, mc.options);
	}

	private static FriendsUi friendsUi(Screen s) {
		return s instanceof TrsUiScreen && ((TrsUiScreen) s).ui() instanceof FriendsUi ? (FriendsUi) ((TrsUiScreen) s).ui() : null;
	}

	private static ClipsUi clipsUi(Screen s) {
		return s instanceof TrsUiScreen && ((TrsUiScreen) s).ui() instanceof ClipsUi ? (ClipsUi) ((TrsUiScreen) s).ui() : null;
	}

	// --- Testdaten ---

	/** Server mit TRS-Freunden (angeheftet), ein Clip-Ordner mit zwei MP4-Attrappen, clips.json dazu. */
	private void fixtures(Minecraft mc) {
		ServerList list = new ServerList(mc);
		list.load();
		boolean have = false;
		for (int i = 0; i < list.size(); i++) if (FRIEND_SERVER.equals(list.get(i).ip)) have = true;
		if (!have) {
			//? if >=1.20.2 {
			list.add(new ServerData("TRS Testserver (Freunde)", FRIEND_SERVER, ServerData.Type.OTHER), false);
			//?} elif >=1.19 {
			/*list.add(new ServerData("TRS Testserver (Freunde)", FRIEND_SERVER, false), false);
			*///?} else
			/*list.add(new ServerData("TRS Testserver (Freunde)", FRIEND_SERVER, false));*/
			list.save();
		}
		Path config = mc.gameDirectory.toPath().toAbsolutePath().resolve("config");
		ServerPins pins = ServerPins.shared(config);
		if (!pins.isPinned(FRIEND_SERVER)) pins.toggle(FRIEND_SERVER);
		try {
			clipDir = mc.gameDirectory.toPath().toAbsolutePath().resolve("trs-autotest-clips");
			Files.createDirectories(clipDir);
			Files.write(clipDir.resolve("Survival 2026-09-25 18-04-11.mp4"), mp4(15_000));
			Files.write(clipDir.resolve("Redstone-Uhr 2026-09-24 21-40-02.mp4"), mp4(92_500));
			clipsJson = config.resolve("trsclient").resolve("clips.json");
			if (!Files.exists(clipsJson)) {
				Files.createDirectories(clipsJson.getParent());
				String dir = clipDir.toString().replace("\\", "\\\\");
				Files.write(clipsJson, ("{\"version\":2,\"enabled\":false,\"clipsDir\":\"" + dir + "\"}").getBytes(StandardCharsets.UTF_8));
			} else {
				clipsJson = null; // fremde Datei (z. B. vom Launcher) nicht anfassen
			}
			Path shots = mc.gameDirectory.toPath().resolve("screenshots");
			Files.createDirectories(shots);
		} catch (IOException e) {
			log("Testdaten: " + e);
		}
	}

	private void cleanup() {
		try {
			if (clipsJson != null) Files.deleteIfExists(clipsJson);
		} catch (IOException ignored) {
			// egal
		}
	}

	/** Minimale MP4: ftyp + moov/mvhd mit Dauer (ms, Zeitbasis 1000). */
	static byte[] mp4(int durationMs) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		box(out, "ftyp", new byte[]{'i', 's', 'o', 'm', 0, 0, 2, 0, 'i', 's', 'o', 'm'});
		byte[] mvhd = new byte[100];
		mvhd[15] = (byte) 0xE8; // timescale 1000 bei Offset 12..15 (Version 0)
		mvhd[14] = 0x03;
		mvhd[16] = (byte) (durationMs >>> 24);
		mvhd[17] = (byte) (durationMs >>> 16);
		mvhd[18] = (byte) (durationMs >>> 8);
		mvhd[19] = (byte) durationMs;
		ByteArrayOutputStream moov = new ByteArrayOutputStream();
		box(moov, "mvhd", mvhd);
		box(out, "moov", moov.toByteArray());
		return out.toByteArray();
	}

	private static void box(ByteArrayOutputStream out, String type, byte[] body) {
		int size = body.length + 8;
		out.write(size >>> 24);
		out.write(size >>> 16);
		out.write(size >>> 8);
		out.write(size);
		byte[] t = type.getBytes(StandardCharsets.US_ASCII);
		out.write(t, 0, 4);
		out.write(body, 0, body.length);
	}
}
