package dev.theredstonee.trsrules.mod;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Minecraft 1.21 – 1.21.4 (used for 1.21.1 and 1.21.4): {@code ResourceLocation.fromNamespaceAndPath}, int levels. */
public final class Mc {
	private Mc() {
	}

	public static ResourceLocation id(String namespace, String path) {
		return ResourceLocation.fromNamespaceAndPath(namespace, path);
	}

	/** Dimension id, e.g. {@code minecraft:the_nether}. */
	public static String dimension(ServerPlayer player) {
		return player.serverLevel().dimension().location().toString();
	}

	public static boolean hasLevel(CommandSourceStack source, int level) {
		return source.hasPermission(level);
	}

	/** OP level 0–4. */
	public static int opLevel(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		return server == null ? 0 : server.getProfilePermissions(player.getGameProfile());
	}
}
