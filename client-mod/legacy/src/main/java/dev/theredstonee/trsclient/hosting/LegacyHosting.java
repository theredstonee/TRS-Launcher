package dev.theredstonee.trsclient.hosting;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.hosting.Hosting;
import dev.theredstonee.trsclient.core.hosting.HostingPlatform;
import dev.theredstonee.trsclient.core.hosting.WorldBackup;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
//? if >=1.10 {
/*import net.minecraft.world.GameType;
*///?} else
import net.minecraft.world.WorldSettings.GameType;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Welt-Hosting unter Minecraft 1.8.9–1.12.2 (Legacy-Forge, ohne Mixins): Gäste hängen über
 * {@code core.hosting.netty.ServerAttach} am {@code NetworkSystem} (dessen TCP-Initializer, ohne Port); „veröffentlicht“
 * wird ohne {@code shareToLAN} (das würde einen LAN-Port öffnen) direkt über das Feld {@code isPublic}; Spielergrenze
 * über {@code maxPlayers}. Als Gast verbindet Minecraft sich über eine einmalige Loopback-Brücke
 * ({@code LoopbackBridge}, nur 127.0.0.1), weil es ohne Mixin keinen Einstieg in den Verbindungsaufbau gibt.
 */
public final class LegacyHosting {
	private static String minecraftVersion = "unknown";
	private static Consumer<String> log;
	private static volatile List<HostingPlatform.Player> lastPlayers = Collections.emptyList();
	private static volatile int vanillaMax = -1;

	private LegacyHosting() {
	}

	public static void install(String mcVersion, Consumer<String> logger) {
		minecraftVersion = mcVersion;
		log = logger;
		try {
			Hosting.install(PLATFORM);
		} catch (RuntimeException | LinkageError e) {
			// ohne Hosting weiter
		}
	}

	static void log(String m) {
		Consumer<String> l = log;
		if (l != null) l.accept(m);
	}

	static IntegratedServer server() {
		return Mc.mc().getIntegratedServer();
	}

	//? if >=1.9 {
	/*static net.minecraft.server.management.PlayerList pl(IntegratedServer s) {
		return s.getPlayerList();
	}
	*///?} else {
	static net.minecraft.server.management.ServerConfigurationManager pl(IntegratedServer s) {
		return s.getConfigurationManager();
	}
	//?}

	@SuppressWarnings("unchecked")
	static List<EntityPlayerMP> playerList(IntegratedServer s) {
		//? if >=1.10 {
		/*return new ArrayList<EntityPlayerMP>(pl(s).getPlayers());
		*///?} else
		return new ArrayList<EntityPlayerMP>(pl(s).getPlayerList());
	}

	static GameType gameType(String mode) {
		try {
			return GameType.valueOf(mode == null ? "SURVIVAL" : mode.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return GameType.SURVIVAL;
		}
	}

	static UUID parse(String hex) {
		if (hex == null || !hex.matches("[0-9a-f]{32}")) return null;
		return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
	}

	static String hex(UUID u) {
		return u == null ? null : u.toString().replace("-", "");
	}

	/** {@code isPublic} setzen und über {@code getPublic()} prüfen (MCP-, dann SRG-Name). */
	static void setPublic(IntegratedServer s, boolean value) {
		try {
			Field f = ReflectionHelper.findField(IntegratedServer.class, "isPublic", "field_71346_p");
			f.setBoolean(s, value);
			if (s.getPublic() != value) throw new IllegalStateException("isPublic");
		} catch (IllegalAccessException | RuntimeException e) {
			throw new IllegalStateException("publish", e);
		}
	}

	/** Spielergrenze ({@code maxPlayers}, protected int). */
	static void setMax(IntegratedServer s, int max) {
		try {
			Object list = pl(s);
			Field f = ReflectionHelper.findField(list.getClass().getSuperclass() == Object.class ? list.getClass()
					: net.minecraft.server.management.
					//? if >=1.9 {
					/*PlayerList
					*///?} else
					ServerConfigurationManager
					.class, "maxPlayers", "field_72405_c");
			if (vanillaMax < 0) vanillaMax = f.getInt(list);
			f.setInt(list, max > 0 ? Math.max(max, vanillaMax) : vanillaMax);
		} catch (IllegalAccessException | RuntimeException e) {
			log("TRS Hosting: Spielergrenze: " + e);
		}
	}

	/** {@code saveAllWorlds(false)} – in 1.8.9 protected, daher per Reflection (MCP- bzw. SRG-Name). */
	static void saveAll(IntegratedServer s) throws ReflectiveOperationException {
		for (String name : new String[] { "saveAllWorlds", "func_71267_a" }) {
			try {
				Method m = net.minecraft.server.MinecraftServer.class.getDeclaredMethod(name, boolean.class);
				m.setAccessible(true);
				m.invoke(s, false);
				return;
			} catch (NoSuchMethodException ignored) {
				// nächster Name
			}
		}
		throw new NoSuchMethodException("saveAllWorlds");
	}

	static Path worldDir(IntegratedServer s) {
		return new File(new File(Mc.gameDir(), "saves"), s.getFolderName()).toPath().toAbsolutePath().normalize();
	}

	static final HostingPlatform PLATFORM = new HostingPlatform() {
		@Override
		public boolean canHost() {
			IntegratedServer s = server();
			return s != null && Mc.world() != null && Mc.player() != null && s.isServerRunning();
		}

		@Override
		public String worldName() {
			return Mc.levelName();
		}

		@Override
		public String worldKey() {
			IntegratedServer s = server();
			return s == null ? null : System.identityHashCode(s) + ":" + s.getFolderName();
		}

		@Override
		public String minecraftVersion() {
			return minecraftVersion;
		}

		@Override
		public String loader() {
			return "forge";
		}

		@Override
		public Object connectionListener() {
			IntegratedServer s = server();
			return s == null ? null : s.getNetworkSystem();
		}

		@Override
		public void publish(Options o) {
			IntegratedServer s = server();
			if (s == null) throw new IllegalStateException("no world");
			setPublic(s, true);
			if (offlineTest()) s.setOnlineMode(false);
			apply(o);
		}

		@Override
		public void apply(Options o) {
			IntegratedServer s = server();
			if (s == null) return;
			setMax(s, o.maxPlayers);
			s.setAllowPvp(o.pvp);
		}

		@Override
		public void unpublish() {
			IntegratedServer s = server();
			if (s == null) return;
			try {
				if (vanillaMax >= 0) setMax(s, vanillaMax);
				vanillaMax = -1;
				setPublic(s, false);
			} catch (RuntimeException e) {
				log("TRS Hosting: Zurücknehmen: " + e);
			}
		}

		@Override
		public List<Player> players() {
			IntegratedServer s = server();
			if (s == null) return Collections.emptyList();
			try {
				List<Player> out = new ArrayList<Player>();
				for (EntityPlayerMP p : playerList(s)) out.add(new Player(hex(p.getUniqueID()), p.getName()));
				lastPlayers = out;
				return out;
			} catch (RuntimeException e) {
				return lastPlayers;
			}
		}

		@Override
		public void applyRights(final String uuid, final String gameMode, final Boolean op) {
			final IntegratedServer s = server();
			if (s == null) return;
			s.addScheduledTask(new Runnable() {
				@Override
				public void run() {
					UUID id = parse(uuid);
					EntityPlayerMP p = id == null ? null : pl(s).getPlayerByUUID(id);
					if (p == null) return;
					try {
						if (gameMode != null) p.setGameType(gameType(gameMode));
						if (op != null) {
							boolean is = pl(s).canSendCommands(p.getGameProfile());
							if (op && !is) pl(s).addOp(p.getGameProfile());
							else if (!op && is) pl(s).removeOp(p.getGameProfile());
						}
					} catch (RuntimeException e) {
						log("TRS Hosting: Rechte: " + e);
					}
				}
			});
		}

		@Override
		public boolean disconnect(String uuid, String message) {
			// Getrennt wird über den Kanal (Hosting schließt ihn) – die Kick-Methode heißt je Version anders.
			return false;
		}

		@Override
		public void backup(final Done done) {
			final Minecraft m = Mc.mc();
			final IntegratedServer s = server();
			if (s == null) {
				done.done(null, "hosting.error.no_world");
				return;
			}
			final Path world = worldDir(s);
			final Path backups = new File(Mc.gameDir(), "backups").toPath();
			final String id = s.getFolderName();
			s.addScheduledTask(new Runnable() {
				@Override
				public void run() {
					try {
						pl(s).saveAllPlayerData();
						saveAll(s);
					} catch (Exception | LinkageError e) {
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
							m.addScheduledTask(new Runnable() {
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
			Mc.leaveWorld();
			Mc.connect(address, label);
		}

		@Override
		public boolean channelConnect() {
			return false;
		}

		@Override
		public boolean inWorld() {
			return Mc.world() != null;
		}

		@Override
		public boolean readyForJoin() {
			return Mc.world() == null && Mc.screen() != null;
		}

		@Override
		public void copy(String text) {
			Mc.setClipboard(text);
		}

		@Override
		public void log(String message) {
			LegacyHosting.log(message);
		}
	};
}
