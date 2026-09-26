package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.hosting.Hosting;
import dev.theredstonee.trsclient.core.hosting.PlayerRights;
import dev.theredstonee.trsclient.core.hosting.PublicLink;
import dev.theredstonee.trsclient.core.hosting.Rooms;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.social.SocialOverlay;
import dev.theredstonee.trsclient.core.ui.hosting.HostingUi;
import dev.theredstonee.trsclient.core.ui.hosting.JoinUi;
import dev.theredstonee.trsclient.core.ui.hosting.PublicLinkDialog;
import dev.theredstonee.trsclient.screen.MenuScreens;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Welt-Hosting mit ZWEI Spielen ({@code -PtrsAutotestOnly=hosting}): Host ({@code -Dtrsclient.hosting.test.role=host})
 * öffnet eine Testwelt, Gast ({@code =guest}) tritt bei – per Code + Anfrage ({@code flow=code}) oder per Einladung +
 * Toast ({@code flow=invite}). Absprache über Dateien in {@code -Dtrsclient.hosting.test.dir}. Screenshots:
 * trsclient-&lt;mc&gt;-hosting-&lt;rolle&gt;-*.png. Beendet das Spiel danach.
 */
public final class HostingTest {
	private final String role = System.getProperty("trsclient.hosting.test.role", "host");
	private final String flow = System.getProperty("trsclient.hosting.test.flow", "code");
	private final boolean linkTest = Boolean.getBoolean("trsclient.hosting.test.link");
	/** Mods + Resource Pack teilen ({@code -Dtrsclient.hosting.test.mods=true}): Host teilt, Gast bekommt Liste + Pack-Frage. */
	private final boolean modsTest = Boolean.getBoolean("trsclient.hosting.test.mods");
	private boolean packAsked;
	private final Path dir = Paths.get(System.getProperty("trsclient.hosting.test.dir", "hosting-test"));
	private int phase;
	private int wait;
	private int waited;
	private Hosting.Notice lastNotice;
	/** Harte Zeitgrenze je Spiel (Standard 170 s): danach beendet sich das Spiel selbst – auch wenn die Gegenseite fehlt. */
	private final long deadline = System.currentTimeMillis() + Long.getLong("trsclient.hosting.test.limitMs", 170_000L);
	private String guestUuid;
	private PublicLinkDialog dialog;

	public static void install() {
		HostingTest test = new HostingTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Hosting/{}: {}", role, text);
	}

	private void shot(Minecraft mc, String name) {
		AutoTest.shot(mc, "trsclient-hosting-" + role + "-" + name);
		log("Bild " + name);
	}

	private boolean waitFor(boolean ready, int maxTries, String what) {
		if (!ready && waited++ < maxTries) {
			wait = 5;
			return false;
		}
		if (!ready) log("ZEITÜBERSCHREITUNG: " + what);
		waited = 0;
		return true;
	}

	private void write(String file, String text) {
		try {
			Files.createDirectories(dir);
			Files.write(dir.resolve(file), text.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			log("Datei " + file + ": " + e);
		}
	}

	private String read(String file) {
		try {
			Path p = dir.resolve(file);
			return Files.isRegularFile(p) ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim() : null;
		} catch (IOException e) {
			return null;
		}
	}

	private static HostingUi hostingUi(Screen s) {
		return s instanceof TrsUiScreen && ((TrsUiScreen) s).ui() instanceof HostingUi ? (HostingUi) ((TrsUiScreen) s).ui() : null;
	}

	private static JoinUi joinUi(Screen s) {
		return s instanceof TrsUiScreen && ((TrsUiScreen) s).ui() instanceof JoinUi ? (JoinUi) ((TrsUiScreen) s).ui() : null;
	}

	private void tick(Minecraft mc) {
		if (phase >= 0 && System.currentTimeMillis() > deadline) {
			log("ZEITGRENZE erreicht – Spiel wird beendet");
			phase = -1000;
			mc.stop();
			return;
		}
		Hosting hn = Hosting.current();
		Hosting.Notice n = hn == null ? null : hn.notice(System.currentTimeMillis());
		if (n != null && n != lastNotice) {
			lastNotice = n;
			log("Hinweis" + (n.error ? " (Fehler)" : "") + ": " + n.text());
		}
		if (wait > 0) {
			wait--;
			return;
		}
		int current = phase;
		try {
			if (role.equals("guest")) guest(mc);
			else host(mc);
		} catch (RuntimeException | LinkageError e) {
			TrsClient.LOGGER.error("[Autotest] Hosting: Fehler in Phase {} – weiter", current, e);
			if (phase == current) phase++;
			wait = 5;
		}
		if (phase > 100) {
			phase = -1000;
			log("fertig");
			mc.stop();
		}
	}

	private boolean ready(Minecraft mc) {
		if (Mc.overlay() != null || Mc.screen() == null) return false;
		mc.options.pauseOnLostFocus = false;
		//? if >=1.19 {
		mc.options.renderDistance().set(2);
		//?}
		return true;
	}

	private static boolean signedIn() {
		TrsOnline o = TrsOnline.current();
		Hosting h = Hosting.current();
		return o != null && o.online() && h != null && h.signedIn();
	}

	// --- Host ---

	private void host(Minecraft mc) {
		Hosting h = Hosting.current();
		switch (phase) {
			case 0:
				if (!ready(mc)) return;
				Mc.setScreen(new TrsTitleScreen());
				phase++;
				wait = 20;
				return;
			case 1:
				if (!waitFor(signedIn(), 600, "Anmeldung")) return;
				Mc.setScreen(null);
				AutoTest.startWorld(mc);
				phase++;
				return;
			case 2:
				if (!waitFor(mc.level != null && mc.player != null && Mc.screen() == null, 600, "Welt")) return;
				wait = 40;
				phase++;
				return;
			case 3:
				Mc.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
				wait = 15;
				phase++;
				return;
			case 4:
				shot(mc, "pause");
				Mc.setScreen(MenuScreens.hosting(null));
				wait = 20;
				phase++;
				return;
			case 5: {
				shot(mc, "setup");
				HostingUi ui = hostingUi(Mc.screen());
				if (modsTest && ui != null) {
					dev.theredstonee.trsclient.core.hosting.share.ShareModel m = ui.testShare();
					m.setShareMods(true);
					m.setDirect(true);
					m.setSharePack(true);
					m.choosePack("TRS-Testpack.zip");
					h.scanShare(m);
					phase = 50;
					return;
				}
				if (ui != null) ui.testHost(true);
				phase++;
				return;
			}
			case 50: {
				HostingUi ui = hostingUi(Mc.screen());
				dev.theredstonee.trsclient.core.hosting.share.ShareModel m = ui == null ? null : ui.testShare();
				if (!waitFor(m != null && m.state() == dev.theredstonee.trsclient.core.hosting.share.ShareModel.State.READY, 400,
						"Mod-Liste")) return;
				for (dev.theredstonee.trsclient.core.hosting.share.ShareModel.Row r : m.rows()) {
					log("Mod " + r.mod.file + " kind=" + r.kind + " on=" + r.on + " required=" + r.required + " source="
							+ r.source(m.direct()) + (r.match == null ? "" : " store=" + r.match.projectId + "/" + r.match.fileId));
				}
				log("Packs: " + m.packs().size() + ", gewählt: " + (m.selectedPack() == null ? "-" : m.selectedPack().file)
						+ ", Pack möglich: " + Hosting.packSupported());
				wait = 10;
				phase++;
				return;
			}
			case 51: {
				shot(mc, "share-setup");
				HostingUi ui = hostingUi(Mc.screen());
				if (ui != null) ui.testShareDialog(false);
				wait = 10;
				phase++;
				return;
			}
			case 52: {
				shot(mc, "share-mods");
				HostingUi ui = hostingUi(Mc.screen());
				if (ui != null) ui.testShareDialog(true);
				wait = 10;
				phase++;
				return;
			}
			case 53: {
				shot(mc, "share-pack");
				HostingUi ui = hostingUi(Mc.screen());
				if (ui != null) {
					ui.testCloseDialog();
					ui.testHost(true);
				}
				phase = 6;
				return;
			}
			case 6:
				if (!waitFor(h.hostState() == Hosting.HostState.OPEN, 600, "Welt offen")) return;
				wait = 20;
				phase++;
				return;
			case 7: {
				shot(mc, "open");
				Rooms.Room r = h.room();
				log("Raum " + (r == null ? "?" : r.prettyCode()) + ", Relay-Problem: " + h.relayProblem());
				if (modsTest) {
					dev.theredstonee.trsclient.core.hosting.share.SharedContent sc = h.sharedContent();
					log("Geteilt: " + sc.mods.size() + " Mods, Pack " + (sc.pack == null ? "-" : sc.pack.name + " " + sc.pack.sha1)
							+ ", Freigabe läuft: " + h.shareBusy());
					for (dev.theredstonee.trsclient.core.hosting.share.SharedContent.Mod x : sc.mods) {
						log("  " + x.file + " " + x.source.id + (x.required ? " Pflicht" : " optional") + " sha256=" + x.sha256);
					}
				}
				if (r != null) write("code.txt", r.code);
				if ("invite".equals(flow)) {
					List<Hosting.Friend> f = h.friendsForInvite();
					log("Freunde zum Einladen: " + f.size());
					if (!f.isEmpty()) {
						guestUuid = f.get(0).uuid;
						h.invite(guestUuid);
					}
				}
				HostingUi ui = hostingUi(Mc.screen());
				if (ui != null) ui.testTab(1);
				phase++;
				return;
			}
			case 8: {
				Rooms.Room r = h.room();
				boolean requested = r != null && !r.members("requested").isEmpty();
				boolean in = h.guests().size() > 1;
				if (!waitFor(requested || in, 1200, "Anfrage/Gast")) return;
				if (requested) {
					shot(mc, "request");
					guestUuid = r.members("requested").get(0).uuid;
					h.accept(guestUuid);
					log("angenommen: " + guestUuid);
				}
				phase++;
				return;
			}
			case 9: {
				List<Hosting.Guest> g = h.guests();
				if (!waitFor(g.size() > 1, 1200, "Gast in der Welt")) return;
				for (Hosting.Guest x : g) {
					if (!x.host) {
						guestUuid = x.uuid;
						log("Gast " + x.name + " über " + x.path);
						write("host-sees.txt", x.name + " " + x.path);
					}
				}
				HostingUi ui = hostingUi(Mc.screen());
				if (ui != null) ui.testTab(0);
				wait = 20;
				phase++;
				return;
			}
			case 10:
				shot(mc, "players");
				if (modsTest) {
					HostingUi ui = hostingUi(Mc.screen());
					if (ui != null) ui.testTab(3);
				}
				if (guestUuid != null) h.setRights(guestUuid, PlayerRights.DEFAULT.withSpectator(true));
				wait = 30;
				phase++;
				return;
			case 11:
				shot(mc, modsTest ? "share-settings" : "rights");
				if (guestUuid != null) h.setRights(guestUuid, PlayerRights.DEFAULT.withBuild(false).withOp(true));
				wait = 10;
				phase = linkTest ? 12 : 20;
				return;
			case 12: {
				HostingUi ui = hostingUi(Mc.screen());
				if (ui == null) {
					Mc.setScreen(MenuScreens.hosting(null));
					wait = 10;
					return;
				}
				dialog = ui.testPublicLinkDialog();
				wait = 10;
				phase++;
				return;
			}
			case 13:
				shot(mc, "link-warning");
				log("Aktivieren ohne Häkchen möglich? " + dialog.gate().canActivate());
				dialog.gate().setUnderstood(true);
				wait = 5;
				phase++;
				return;
			case 14:
				shot(mc, "link-checked");
				log("aktiviert: " + h.publicLink().activate(dialog.gate()));
				HostingUi ui = hostingUi(Mc.screen());
				if (ui != null) ui.testCloseDialog();
				phase++;
				return;
			case 15:
				if (!waitFor(h.publicLink().state() == PublicLink.State.ON || h.publicLink().state() == PublicLink.State.ERROR, 600,
						"öffentlicher Link")) return;
				log("Link: " + h.publicLink().state() + " " + h.publicLink().domain() + " " + h.publicLink().error());
				wait = 10;
				phase++;
				return;
			case 16:
				shot(mc, "link-manage");
				Mc.setScreen(null);
				wait = 20;
				phase++;
				return;
			case 17:
				shot(mc, "link-hud");
				Mc.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
				wait = 15;
				phase++;
				return;
			case 18:
				shot(mc, "link-pause");
				h.publicLink().stop();
				Mc.setScreen(null);
				phase = 20;
				return;
			case 20:
				if (!waitFor(read("guest-done.txt") != null, 1200, "Gast fertig")) return;
				Mc.setScreen(MenuScreens.hosting(null));
				wait = 20;
				phase++;
				return;
			case 21:
				shot(mc, "after");
				h.stopHosting("closed");
				wait = 40;
				phase++;
				return;
			case 22:
				log("Status nach Beenden: " + h.hostState());
				write("host-done.txt", "ok");
				phase = 101;
				return;
			default:
		}
	}

	// --- Gast ---

	private void guest(Minecraft mc) {
		Hosting h = Hosting.current();
		switch (phase) {
			case 0:
				if (!ready(mc)) return;
				Mc.setScreen(new TrsTitleScreen());
				phase++;
				wait = 20;
				return;
			case 1:
				if (!waitFor(signedIn(), 600, "Anmeldung")) return;
				phase = "invite".equals(flow) ? 10 : 2;
				return;
			case 2: {
				String code = read("code.txt");
				if (!waitFor(code != null, 2400, "Code vom Host")) return;
				Mc.setScreen(MenuScreens.join(new TrsTitleScreen()));
				wait = 20;
				phase++;
				return;
			}
			case 3: {
				shot(mc, "join");
				JoinUi ui = joinUi(Mc.screen());
				if (ui != null) ui.testCode(read("code.txt"));
				wait = 30;
				phase++;
				return;
			}
			case 4:
				shot(mc, "waiting");
				phase = modsTest ? 30 : 20;
				return;
			case 30: {
				if (!waitFor(h.guestState() == Hosting.GuestState.NEEDS_MODS || mc.level != null, 1200, "Mod-Abgleich")) return;
				dev.theredstonee.trsclient.core.hosting.share.GuestCheck gc = h.modCheck();
				if (gc == null) {
					log("kein Mod-Dialog (Zustand " + h.guestState() + ")");
					phase = 20;
					return;
				}
				log("Mod-Abgleich: fehlt Pflicht " + gc.missingRequired.size() + ", optional " + gc.missingOptional.size()
						+ ", ohne Mods möglich: " + gc.canJoinWithout());
				for (dev.theredstonee.trsclient.core.hosting.share.SharedContent.Mod x : gc.content.mods) {
					log("  " + x.name + " " + x.version + " · " + x.source.id + " · " + (x.required ? "Pflicht" : "optional") + " · " + x.size);
				}
				write("guest-room.txt", gc.roomId);
				wait = 20;
				phase++;
				return;
			}
			case 31:
				shot(mc, "mods-dialog");
				phase++;
				return;
			case 32: {
				// Der Launcher-Teil (Rust: Store-Download + Host-Datei über das Relay, Hash-Prüfung) läuft außerhalb.
				String res = read("launcher-result.txt");
				if (!waitFor(res != null, 240, "Launcher-Ergebnis")) return;
				log("Launcher: " + res);
				h.testJoinDespiteMods();
				phase = 20;
				return;
			}
			case 10: {
				// Einladung per Toast (Welt steht dann als „eingeladen“ in der Liste), dann Schnelltaste (Beitreten).
				boolean invited = false;
				for (Rooms.Room r : h.friendsRooms()) if ("invited".equals(r.myState)) invited = true;
				if (!waitFor(invited && SocialOverlay.active(), 2400, "Einladungs-Toast")) return;
				wait = 10;
				phase++;
				return;
			}
			case 11: {
				shot(mc, "invite-toast");
				// Ältere Toasts (z. B. „ist online“) überspringen, bis die Einladung dran ist.
				SocialOverlay.QuickAction a = null;
				for (int i = 0; i < 6; i++) {
					a = SocialOverlay.takeQuickAction();
					if (a == null || a.kind == SocialOverlay.QuickAction.Kind.WORLD_JOIN) break;
				}
				log("Schnelltaste: " + (a == null ? "nichts" : a.kind));
				if (a != null) Mc.setScreen(MenuScreens.socialAction(a, new TrsTitleScreen()));
				wait = 10;
				phase = 20;
				return;
			}
			case 20:
				if (modsTest && !packAsked && Mc.screen() instanceof net.minecraft.client.gui.screens.ConfirmScreen) {
					// Vanilla-Frage „Server-Resource-Pack verwenden?“ (Pack des Hosts über den lokalen Endpunkt).
					packAsked = true;
					shot(mc, "pack-question");
					wait = 10;
					phase = 40;
					return;
				}
				if (!waitFor(mc.level != null && mc.player != null, 2400, "in der Welt des Hosts")) return;
				wait = 80;
				phase++;
				return;
			case 21:
				shot(mc, "joined");
				log("Weg: " + h.guestPath() + ", Zustand " + h.guestState());
				write("guest-path.txt", String.valueOf(h.guestPath()));
				wait = linkTest ? 900 : 300;
				phase++;
				return;
			case 40: {
				net.minecraft.client.gui.screens.Screen s = Mc.screen();
				if (s instanceof net.minecraft.client.gui.screens.ConfirmScreen) {
					try {
						java.lang.reflect.Field f = net.minecraft.client.gui.screens.ConfirmScreen.class.getDeclaredField("callback");
						f.setAccessible(true);
						((it.unimi.dsi.fastutil.booleans.BooleanConsumer) f.get(s)).accept(true);
						log("Pack-Frage: Ja");
					} catch (ReflectiveOperationException | RuntimeException e) {
						log("Pack-Frage nicht beantwortbar: " + e);
					}
				}
				phase = 20;
				return;
			}
			case 22:
				shot(mc, "later");
				log("Spielmodus jetzt: " + (mc.gameMode == null ? "?" : mc.gameMode.getPlayerMode()));
				AutoTest.disconnect(mc);
				write("guest-done.txt", "ok");
				wait = 40;
				phase = 101;
				return;
			default:
		}
	}
}
