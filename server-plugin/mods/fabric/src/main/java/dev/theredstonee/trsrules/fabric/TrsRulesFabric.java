package dev.theredstonee.trsrules.fabric;

import java.lang.reflect.Method;

import dev.theredstonee.trsrules.mod.ModPlatform;
import dev.theredstonee.trsrules.mod.ModServerRules;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.S2CPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * TRS Server Rules for Fabric dedicated servers. Only a {@code server} entrypoint: the mod is inactive on clients and
 * in single player (rules there would be pointless, and the TRS Client registers the same payload ids itself).
 */
public final class TrsRulesFabric implements DedicatedServerModInitializer {
	@Override
	public void onInitializeServer() {
		final ModServerRules mod = new ModServerRules(new FabricPlatform(), FabricLoader.getInstance().getConfigDir());
		if (!FabricNet.register(mod)) return;

		ServerLifecycleEvents.SERVER_STARTING.register(mod::serverStarting);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> mod.serverStopped());
		ServerTickEvents.END_SERVER_TICK.register(server -> mod.tick());

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> mod.onJoin(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> mod.onQuit(handler.player));
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> mod.onWorldChange(player));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> mod.onWorldChange(newPlayer));
		// The client announced its channels (minecraft:register) – rules that were waiting for it can go out now.
		S2CPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> mod.onChannelsRegistered(handler.player));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> mod.registerCommand(dispatcher));
	}

	/** Fabric: payloads via Fabric API, permissions via fabric-permissions-api (LuckPerms & co.) if installed. */
	private static final class FabricPlatform implements ModPlatform {
		private Method check;
		private boolean checkResolved;

		@Override
		public String name() {
			return "Fabric";
		}

		@Override
		public boolean canReceive(ServerPlayer player) {
			return FabricNet.canReceive(player);
		}

		@Override
		public void send(ServerPlayer player, byte[] payload) {
			FabricNet.send(player, payload);
		}

		@Override
		public Boolean permission(ServerPlayer player, String permission) {
			Method m = permissionCheck();
			if (m == null) return null;
			try {
				return (Boolean) m.invoke(null, player, permission, Boolean.FALSE);
			} catch (ReflectiveOperationException | RuntimeException e) {
				return null;
			}
		}

		/** me.lucko.fabric.api.permissions.v0.Permissions#check(Entity, String, boolean), looked up once. */
		private synchronized Method permissionCheck() {
			if (!checkResolved) {
				checkResolved = true;
				if (FabricLoader.getInstance().isModLoaded("fabric-permissions-api-v0")) {
					try {
						check = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions")
								.getMethod("check", Entity.class, String.class, boolean.class);
						ModServerRules.log().info("Using fabric-permissions-api for trsrules.admin / bypass-permission");
					} catch (ReflectiveOperationException | LinkageError e) {
						ModServerRules.log().warn("fabric-permissions-api found but not usable: {}", e.toString());
					}
				}
			}
			return check;
		}
	}
}
