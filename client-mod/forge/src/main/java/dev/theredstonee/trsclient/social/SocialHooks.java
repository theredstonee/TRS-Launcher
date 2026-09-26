package dev.theredstonee.trsclient.social;

import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.social.SocialOverlay;
import dev.theredstonee.trsclient.core.social.SocialPlatform;
import dev.theredstonee.trsclient.core.social.VanillaToastProbe;
import dev.theredstonee.trsclient.core.ui.social.SocialHost;
import dev.theredstonee.trsclient.screen.MenuScreens;
import dev.theredstonee.trsclient.screen.TrsMenuHost;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
//? if >=1.17 {
import net.minecraft.client.multiplayer.resolver.ServerAddress;
//?}

import java.nio.file.Path;

/**
 * Anbindung der Sozialfunktionen an Minecraft: Host des Sozial-Bildschirms (Beitreten, Zwischenablage), Plattform der
 * Benachrichtigungen (Ton, Vollbild, Taste), Zeichnen der Toasts im HUD und über Bildschirmen, Tasten. Dieselbe Datei in
 * allen Mojmap-Bäumen (Fabric, NeoForge, Forge, Forge-Mojmap-Legacy).
 */
public final class SocialHooks {
	private SocialHooks() {
	}

	/** Gibt es in dieser Version den allgemeinen Haken nach jedem Bildschirm (MenuScreenMixin → afterRender)? */
	//? if >=1.19.4 {
	static final boolean SCREEN_HOOK = true;
	//?} else
	/*static final boolean SCREEN_HOOK = false;*/

	// --- Start ---

	/** Beim Start des Mods: Plattform der Toasts anmelden. */
	public static void install(TrsModules modules) {
		try {
			SocialOverlay.install(PLATFORM, modules);
		} catch (RuntimeException | LinkageError e) {
			// ohne Toasts weiter
		}
	}

	static final SocialPlatform PLATFORM = new SocialPlatform() {
		@Override
		public void playToastSound() {
			try {
				Mc.mc().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.4F));
			} catch (RuntimeException | LinkageError ignored) {
				// kein Ton
			}
		}

		@Override
		public boolean fullscreen() {
			try {
				//? if >=1.19 {
				return Mc.mc().options.fullscreen().get();
				//?} else
				/*return Mc.mc().options.fullscreen;*/
			} catch (RuntimeException | LinkageError e) {
				return false;
			}
		}

		@Override
		public String quickReplyKey() {
			return keyName(TrsKeys.quickReply);
		}

		@Override
		public int vanillaToastBottom() {
			try {
				return TOAST_PROBE.bottom(toastManager());
			} catch (RuntimeException | LinkageError e) {
				return 0;
			}
		}
	};

	/** Liest die belegten Plätze der Vanilla-Toasts (Feldsuche per Typ, siehe VanillaToastProbe). Render-Thread. */
	static final VanillaToastProbe TOAST_PROBE = new VanillaToastProbe();

	/** Toast-Komponente des Spiels (ToastComponent bis 1.21.1, danach ToastManager; ab 26.2 im Gui). */
	static Object toastManager() {
		//? if >=26.2 {
		/*return Mc.mc().gui.toastManager();
		*///?} elif >=1.21.2 {
		/*return Mc.mc().getToastManager();
		*///?} else
		return Mc.mc().getToasts();
	}

	/** Anzeigename einer Belegung oder null (unbelegt). */
	static String keyName(KeyMapping k) {
		try {
			if (k == null || k.isUnbound()) return null;
			//? if >=1.16 {
			return k.getTranslatedKeyMessage().getString();
			//?} else
			/*return k.getTranslatedKeyMessage();*/
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	// --- Bildschirm-Host ---

	/** Was der Sozial-Bildschirm von Minecraft braucht; „Schließen“ führt zu {@code parent} zurück. */
	public static SocialHost host(final Screen parent) {
		return new SocialHost() {
			@Override
			public void playClick() {
				new TrsMenuHost(parent).playClick();
			}

			@Override
			public void closeScreen() {
				Mc.setScreen(parent);
			}

			@Override
			public String userAgent() {
				return "TRS-Client";
			}

			@Override
			public Path gameDir() {
				try {
					return Mc.mc().gameDirectory.toPath().toAbsolutePath();
				} catch (RuntimeException e) {
					return null;
				}
			}

			@Override
			public String currentServer() {
				try {
					return Mc.serverAddress();
				} catch (RuntimeException e) {
					return null;
				}
			}

			@Override
			public boolean inWorld() {
				return Mc.mc().level != null;
			}

			@Override
			public void joinServer(String address, String label) {
				SocialHooks.joinServer(address, label);
			}

			@Override
			public void copy(String text) {
				try {
					Mc.setClipboard(text == null ? "" : text);
				} catch (RuntimeException ignored) {
					// egal
				}
			}

			@Override
			public String paste() {
				try {
					return Mc.mc().keyboardHandler.getClipboard();
				} catch (RuntimeException e) {
					return null;
				}
			}
		};
	}

	// --- Beitreten ---

	/**
	 * Mit einem Server verbinden (Einladung). Läuft im nächsten Durchlauf des Spiel-Threads (nicht mitten im Klick):
	 * aktuelle Welt verlassen (Einzelspieler: speichern), dann verbinden – zurück geht es zur Serverliste.
	 */
	public static void joinServer(final String address, final String label) {
		if (address == null || address.isEmpty()) return;
		final Minecraft mc = Mc.mc();
		mc.execute(new Runnable() {
			@Override
			public void run() {
				try {
					leaveWorld(mc);
					connect(mc, address, label == null || label.isEmpty() ? address : label);
				} catch (RuntimeException | LinkageError e) {
					// Verbindung scheitert sichtbar im Verbindungs-Bildschirm; hier nichts weiter.
				}
			}
		});
	}

	static void leaveWorld(Minecraft mc) {
		if (mc.level == null) return;
		//? if >=1.21.6 {
		/*mc.level.disconnect(net.minecraft.network.chat.Component.literal("TRS"));
		mc.disconnectWithSavingScreen();
		*///?} elif >=1.20.2 {
		mc.level.disconnect();
		mc.disconnect();
		//?} else {
		/*mc.level.disconnect();
		mc.clearLevel();
		*///?}
	}

	static void connect(Minecraft mc, String address, String name) {
		Screen parent = new JoinMultiplayerScreen(new TitleScreen());
		//? if >=1.20.2 {
		ServerData data = new ServerData(name, address, ServerData.Type.OTHER);
		//?} else
		/*ServerData data = new ServerData(name, address, false);*/
		//? if >=1.20.5 {
		ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(address), data, false, null);
		//?} elif >=1.20 {
		/*ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(address), data, false);
		*///?} elif >=1.17 {
		/*ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(address), data);
		*///?} else
		/*Mc.setScreen(new ConnectScreen(parent, mc, data));*/
	}

	// --- Tasten ---

	/** Taste „Sozial“: Bildschirm öffnen (nur ohne offenen Bildschirm). */
	public static void onSocialKey() {
		try {
			if (Mc.screen() == null && MenuScreens.friendsAvailable()) Mc.setScreen(MenuScreens.social(null));
		} catch (RuntimeException | LinkageError ignored) {
			// egal
		}
	}

	/** Taste „Schnellantwort“: zum neuesten Toast antworten/beitreten/Anfragen öffnen. */
	public static void onQuickReplyKey() {
		try {
			if (Mc.screen() != null || !MenuScreens.friendsAvailable()) return;
			SocialOverlay.QuickAction a = SocialOverlay.takeQuickAction();
			if (a != null) Mc.setScreen(MenuScreens.socialAction(a, null));
		} catch (RuntimeException | LinkageError ignored) {
			// egal
		}
	}

	/** Strg (bzw. Cmd auf macOS) gedrückt? */
	public static boolean control() {
		try {
			//? if >=1.21.9 {
			/*return Mc.mc().hasControlDown();
			*///?} else
			return Screen.hasControlDown();
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	// --- Zeichnen ---

	/**
	 * Aus dem HUD (jedes Bild, auch wenn die TRS-Anzeigen ruhen). Zeichnet nur, wenn Toasts da sind, das HUD nicht per
	 * F1 aus ist und kein anderer Pfad sie schon zeichnet (Bildschirm-Haken bzw. TRS-Bildschirm).
	 */
	public static void hud(Gfx g) {
		dev.theredstonee.trsclient.hosting.HostingHooks.hud(g);
		if (!SocialOverlay.active() || Mc.hudHidden()) return;
		Screen s = Mc.screen();
		if (s != null && (screenHookLive() || s instanceof TrsUiScreen)) return;
		draw(g, false);
	}

	/** Nach dem ganzen Bildschirm (ab 1.19.4, aus MenuScreenMixin): Toasts über jedem Menü. */
	public static void overScreen(Gfx g) {
		if (SCREEN_HOOK) pauseBadge(g);
		if (!SCREEN_HOOK || !SocialOverlay.active()) return;
		draw(g, true);
	}

	/** Zuletzt gefeuerter Haken nach dem Bildschirm bis 1.19.3 (Fabric: ScreenToastMixin, Forge: Render-Post-Ereignis). */
	private static volatile long legacyScreenHookAt;

	/**
	 * Bis 1.19.3, direkt nach dem Zeichnen des offenen Bildschirms (Fabric: ScreenToastMixin, Forge: Ereignis nach dem
	 * Bildschirm): merkt, dass der Haken lebt, und sagt, ob {@link #afterScreen} zeichnen soll (erst dann lohnt ein
	 * Gfx). Ab 1.19.4 immer false – dort zeichnet {@link #overScreen}.
	 */
	public static boolean afterScreenPending() {
		if (SCREEN_HOOK) return false;
		legacyScreenHookAt = System.currentTimeMillis();
		return SocialOverlay.active() || dev.theredstonee.trsclient.core.hosting.HostingOverlay.linkActive();
	}

	/** Pausemenü: Abzeichen „Öffentlicher Link aktiv“ über dem Knopf „Deaktivieren“. */
	static void pauseBadge(Gfx g) {
		int x = dev.theredstonee.trsclient.menus.VanillaMenus.linkBadgeX();
		if (x >= 0) dev.theredstonee.trsclient.hosting.HostingHooks.overPause(g, x, dev.theredstonee.trsclient.menus.VanillaMenus.linkBadgeY());
	}

	/** Bis 1.19.3: Toasts über dem gerade gezeichneten Bildschirm – auch über Vanilla-Menüs. */
	public static void afterScreen(Gfx g) {
		if (SCREEN_HOOK) return;
		pauseBadge(g);
		if (!SocialOverlay.active()) return;
		draw(g, true);
	}

	/** Zeichnet ein Haken nach jedem Bildschirm (ab 1.19.4 immer, darunter sobald der Haken zuletzt gefeuert hat)? */
	static boolean screenHookLive() {
		if (SCREEN_HOOK) return true;
		long at = legacyScreenHookAt;
		return at != 0 && System.currentTimeMillis() - at < 1000;
	}

	/** Nach einem TRS-Bildschirm (nur solange kein Haken nach jedem Bildschirm zeichnet): Toasts darüber. */
	public static void afterUi(Gfx g) {
		if (screenHookLive() || !SocialOverlay.active()) return;
		draw(g, true);
	}

	private static void draw(final Gfx g, boolean raise) {
		try {
			if (raise) {
				g.overlayLayer();
				g.push();
				g.raise(450f);
			}
			final GfxCanvas c = GfxCanvas.of(g, Mc.mc().font);
			final int w = g.width();
			final int h = g.height();
			try {
				g.managed(new Runnable() {
					@Override
					public void run() {
						SocialOverlay.render(c, w, h);
					}
				});
			} finally {
				if (raise) g.pop();
			}
		} catch (RuntimeException | LinkageError ignored) {
			// Toasts dürfen das Spiel nie stören.
		}
	}
}
