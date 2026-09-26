package dev.theredstonee.trsclient.hosting;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.hosting.Hosting;
import dev.theredstonee.trsclient.core.hosting.HostingOverlay;
import dev.theredstonee.trsclient.core.hosting.HostingPlatform;
import dev.theredstonee.trsclient.core.hosting.WorldBackup;
import dev.theredstonee.trsclient.social.SocialHooks;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
//? if >=1.16 {
import net.minecraft.world.level.storage.LevelResource;
//?}

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Anbindung des Welt-Hostings an Minecraft (Mojmap-Bäume, dieselbe Datei in Fabric/NeoForge/Forge/Forge-Mojmap-Legacy):
 * integrierten Server für Gäste öffnen – über {@code publishServer} mit unterdrücktem TCP-Port und LAN-Rundruf
 * ({@code mixin/HostingPublishMixin}) –, Spielergrenze, PvP, Rechte, Trennen, Backup, Beitreten. Server-Zugriffe laufen
 * auf dem Server-Thread.
 */
public final class HostingHooks {
	/** Dummy-Port für {@code publishServer} (es wird nichts gebunden). */
	static final int PORT = 25565;

	/** Während unseres {@code publishServer}: TCP-Listener und LAN-Rundruf unterdrücken (Mixin fragt das). */
	private static volatile boolean quietPublish;
	/** Hat der Mixin den TCP-Listener wirklich unterdrückt? */
	private static volatile boolean bindSuppressed;
	/** Spielergrenze während des Hostings (0 = Vanilla). */
	private static volatile int maxPlayers;
	private static volatile int vanillaMax = -1;
	private static String minecraftVersion = "unknown";
	private static String loader = "vanilla";
	private static Consumer<String> log;
	private static volatile List<HostingPlatform.Player> lastPlayers = Collections.emptyList();

	private HostingHooks() {
	}

	/** Beim Start (aus OnlineHooks.init). */
	public static void install(String mcVersion, String loaderName, Consumer<String> logger) {
		minecraftVersion = mcVersion;
		loader = loaderName;
		log = logger;
		try {
			Hosting.install(PLATFORM);
		} catch (RuntimeException | LinkageError e) {
			// ohne Hosting weiter
		}
	}

	// --- für die Mixins ---

	/** Aus HostingPublishMixin: gerade unser stilles {@code publishServer}? */
	public static boolean quiet() {
		return quietPublish;
	}

	/** Aus HostingPublishMixin: TCP-Listener wurde unterdrückt. */
	public static void suppressed() {
		bindSuppressed = true;
	}

	/** Spielergrenze während des Hostings (0 = Vanilla), für HostingPlayerListMixin. */
	public static int maxPlayers() {
		return maxPlayers;
	}

	// --- Anbindung ---

	static Minecraft mc() {
		return Mc.mc();
	}

	static IntegratedServer server() {
		return mc().getSingleplayerServer();
	}

	static GameType gameType(String mode) {
		try {
			return GameType.valueOf(mode == null ? "SURVIVAL" : mode.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return GameType.SURVIVAL;
		}
	}

	static ServerPlayer player(MinecraftServer s, String uuid) {
		UUID id = parse(uuid);
		return id == null ? null : s.getPlayerList().getPlayer(id);
	}

	static UUID parse(String hex) {
		if (hex == null || !hex.matches("[0-9a-f]{32}")) return null;
		return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
	}

	static String hex(UUID u) {
		return u == null ? null : u.toString().replace("-", "");
	}

	static net.minecraft.network.chat.Component text(String s) {
		//? if >=1.19 {
		return net.minecraft.network.chat.Component.literal(s);
		//?} else
		/*return new net.minecraft.network.chat.TextComponent(s);*/
	}

	static void applyMax(IntegratedServer s, int max) {
		maxPlayers = max;
		//? if <1.21.9 {
		dev.theredstonee.trsclient.mixin.HostingPlayerListAccessor acc = (dev.theredstonee.trsclient.mixin.HostingPlayerListAccessor) s.getPlayerList();
		if (vanillaMax < 0) vanillaMax = acc.trsclient$maxPlayers();
		acc.trsclient$setMaxPlayers(max > 0 ? Math.max(max, vanillaMax) : vanillaMax);
		//?}
	}

	static void applyPvp(final IntegratedServer s, final boolean pvp) {
		s.execute(new Runnable() {
			@Override
			public void run() {
				try {
					//? if >=1.21.9 {
					/*s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), "gamerule pvp " + pvp);
					*///?} else
					s.setPvpAllowed(pvp);
				} catch (RuntimeException | LinkageError e) {
					log("TRS Hosting: PvP: " + e);
				}
			}
		});
	}

	static void log(String message) {
		Consumer<String> l = log;
		if (l != null) l.accept(message);
	}

	static Path worldDir(IntegratedServer s) {
		//? if >=1.16 {
		return s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		//?} else
		/*return mc().getLevelSource().getBaseDir().resolve(s.getLevelIdName()).toAbsolutePath().normalize();*/
	}

	static final HostingPlatform PLATFORM = new HostingPlatform() {
		@Override
		public boolean canHost() {
			Minecraft m = mc();
			IntegratedServer s = m.getSingleplayerServer();
			return s != null && m.level != null && m.player != null && s.isRunning() && channelConnect();
		}

		@Override
		public String worldName() {
			return Mc.levelName();
		}

		@Override
		public String worldKey() {
			IntegratedServer s = server();
			if (s == null) return null;
			return System.identityHashCode(s) + ":" + worldDir(s);
		}

		@Override
		public String minecraftVersion() {
			return minecraftVersion;
		}

		@Override
		public String loader() {
			return loader;
		}

		@Override
		public Object connectionListener() {
			IntegratedServer s = server();
			return s == null ? null : s.getConnection();
		}

		@Override
		public void publish(Options o) {
			IntegratedServer s = server();
			if (s == null) throw new IllegalStateException("no world");
			bindSuppressed = false;
			quietPublish = true;
			boolean ok;
			try {
				//? if >=26.3 {
				/*ok = s.publishServer(MinecraftServer.MultiplayerScope.LAN, false, PORT);
				*///?} elif >=26.2 {
				/*ok = s.publishServer(MinecraftServer.MultiplayerScope.LAN, gameType(o.gameMode), false, PORT);
				*///?} else
				ok = s.publishServer(gameType(o.gameMode), false, PORT);
			} finally {
				quietPublish = false;
			}
			if (!bindSuppressed) {
				// Ohne den Mixin hätte Vanilla einen offenen LAN-Port – nie still weitermachen.
				log("TRS Hosting: TCP-Listener NICHT unterdrückt – Hosting abgebrochen");
				unpublish();
				throw new IllegalStateException("publish hook missing");
			}
			if (!ok && !s.isPublished()) throw new IllegalStateException("publish failed");
			if (offlineTest()) s.setUsesAuthentication(false);
			apply(o);
		}

		@Override
		public void apply(Options o) {
			IntegratedServer s = server();
			if (s == null) return;
			applyMax(s, o.maxPlayers);
			applyPvp(s, o.pvp);
		}

		@Override
		public void unpublish() {
			IntegratedServer s = server();
			maxPlayers = 0;
			if (s == null) return;
			try {
				//? if <1.21.9 {
				if (vanillaMax >= 0) ((dev.theredstonee.trsclient.mixin.HostingPlayerListAccessor) s.getPlayerList()).trsclient$setMaxPlayers(vanillaMax);
				//?}
				vanillaMax = -1;
				//? if >=26.2 {
				/*s.unpublishServer();
				*///?} else
				((dev.theredstonee.trsclient.mixin.HostingServerAccessor) s).trsclient$setPublishedPort(-1);
			} catch (RuntimeException | LinkageError e) {
				log("TRS Hosting: Zurücknehmen: " + e);
			}
		}

		@Override
		public List<Player> players() {
			IntegratedServer s = server();
			if (s == null) return Collections.emptyList();
			try {
				List<ServerPlayer> list = new ArrayList<ServerPlayer>(s.getPlayerList().getPlayers());
				List<Player> out = new ArrayList<Player>(list.size());
				for (ServerPlayer p : list) out.add(new Player(hex(p.getUUID()), p.getName().getString()));
				lastPlayers = out;
				return out;
			} catch (RuntimeException e) {
				// Liste wurde gerade vom Server-Thread geändert – letzten Stand nehmen.
				return lastPlayers;
			}
		}

		@Override
		public void applyRights(final String uuid, final String gameMode, final Boolean op) {
			final IntegratedServer s = server();
			if (s == null) return;
			s.execute(new Runnable() {
				@Override
				public void run() {
					ServerPlayer p = player(s, uuid);
					if (p == null) return;
					try {
						if (gameMode != null) p.setGameMode(gameType(gameMode));
						if (op != null) {
							//? if >=1.21.9 {
							/*net.minecraft.server.players.NameAndId id = new net.minecraft.server.players.NameAndId(p.getGameProfile());
							*///?} else
							com.mojang.authlib.GameProfile id = p.getGameProfile();
							boolean is = s.getPlayerList().isOp(id);
							if (op && !is) s.getPlayerList().op(id);
							else if (!op && is) s.getPlayerList().deop(id);
						}
					} catch (RuntimeException | LinkageError e) {
						log("TRS Hosting: Rechte: " + e);
					}
				}
			});
		}

		@Override
		public boolean disconnect(final String uuid, final String message) {
			final IntegratedServer s = server();
			if (s == null) return false;
			s.execute(new Runnable() {
				@Override
				public void run() {
					ServerPlayer p = player(s, uuid);
					if (p != null) p.connection.disconnect(text(message));
				}
			});
			return true;
		}

		@Override
		public void backup(final Done done) {
			final Minecraft m = mc();
			final IntegratedServer s = server();
			if (s == null) {
				done.done(null, "hosting.error.no_world");
				return;
			}
			final Path world = worldDir(s);
			final Path backups = m.getLevelSource().getBackupPath();
			final String id = world.getFileName() == null ? "world" : world.getFileName().toString();
			s.execute(new Runnable() {
				@Override
				public void run() {
					try {
						s.getPlayerList().saveAll();
						s.saveAllChunks(true, true, true);
					} catch (RuntimeException | LinkageError e) {
						log("TRS Hosting: Speichern vor dem Backup: " + e);
					}
					Thread t = new Thread(new Runnable() {
						@Override
						public void run() {
							Path result = null;
							String error = null;
							try {
								result = WorldBackup.zip(world, backups, id);
							} catch (IOException | RuntimeException e) {
								log("TRS Hosting: Backup fehlgeschlagen: " + e);
								error = "hosting.error.backup_failed";
							}
							final Path r = result;
							final String err = error;
							m.execute(new Runnable() {
								@Override
								public void run() {
									done.done(r, err);
								}
							});
						}
					}, "TRS-Hosting-Backup");
					t.setDaemon(true);
					t.start();
				}
			});
		}

		@Override
		public void connect(String address, String label) {
			SocialHooks.joinServer(address, label);
		}

		@Override
		public boolean channelConnect() {
			// Der Verbindungs-Mixin hängt eine Marker-Schnittstelle an Connection (fehlt er, gibt es kein Hosting).
			return HostingMarker.class.isAssignableFrom(Connection.class);
		}

		@Override
		public boolean inWorld() {
			return mc().level != null;
		}

		@Override
		public boolean readyForJoin() {
			Minecraft m = mc();
			return Mc.overlay() == null && m.level == null && Mc.screen() != null;
		}

		@Override
		public void copy(String text) {
			try {
				mc().keyboardHandler.setClipboard(text);
			} catch (RuntimeException | LinkageError ignored) {
				// egal
			}
		}

		@Override
		public void log(String message) {
			HostingHooks.log(message);
		}
	};

	// --- Zeichnen ---

	/** HUD: rotes Abzeichen „Öffentlicher Link aktiv“ (auch wenn die TRS-Anzeigen ruhen; nicht bei F1). */
	public static void hud(final Gfx g) {
		if (!HostingOverlay.linkActive() || Mc.hudHidden()) return;
		draw(g, false, -1, -1);
	}

	/** Pausemenü: Abzeichen über dem Knopf „Deaktivieren“ (Lage aus VanillaMenus). */
	public static void overPause(Gfx g, int x, int y) {
		if (!HostingOverlay.linkActive() || !(Mc.screen() instanceof PauseScreen)) return;
		draw(g, true, x, y);
	}

	private static void draw(final Gfx g, boolean raise, final int x, final int y) {
		try {
			if (raise) {
				g.overlayLayer();
				g.push();
				g.raise(460f);
			}
			final GfxCanvas c = GfxCanvas.of(g, Mc.mc().font);
			final int w = g.width();
			final int h = g.height();
			try {
				g.managed(new Runnable() {
					@Override
					public void run() {
						if (x < 0) HostingOverlay.renderHud(c, w, h);
						else HostingOverlay.renderAt(c, x, y);
					}
				});
			} finally {
				if (raise) g.pop();
			}
		} catch (RuntimeException | LinkageError ignored) {
			// darf nie stören
		}
	}
}
