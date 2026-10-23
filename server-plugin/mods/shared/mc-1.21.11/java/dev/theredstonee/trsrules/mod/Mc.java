package dev.theredstonee.trsrules.mod;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;

/**
 * Minecraft 1.21.11: {@code Identifier} (formerly ResourceLocation), {@code ResourceKey#identifier()}, permission sets
 * instead of int levels.
 */
public final class Mc {
	private Mc() {
	}

	public static Identifier id(String namespace, String path) {
		return Identifier.fromNamespaceAndPath(namespace, path);
	}

	/** Dimension id, e.g. {@code minecraft:the_nether}. */
	public static String dimension(ServerPlayer player) {
		return player.level().dimension().identifier().toString();
	}

	public static boolean hasLevel(CommandSourceStack source, int level) {
		return source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level)));
	}

	/** OP level 0–4. */
	public static int opLevel(ServerPlayer player) {
		PermissionSet set = player.permissions();
		if (set instanceof LevelBasedPermissionSet) return ((LevelBasedPermissionSet) set).level().id();
		for (int level = 4; level > 0; level--) {
			if (set.hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level)))) return level;
		}
		return 0;
	}
}
