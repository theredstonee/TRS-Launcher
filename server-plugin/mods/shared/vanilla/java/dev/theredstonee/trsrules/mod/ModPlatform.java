package dev.theredstonee.trsrules.mod;

import net.minecraft.server.level.ServerPlayer;

/** What differs between Fabric, NeoForge and Forge: payload delivery and (optional) permission systems. */
public interface ModPlatform {
	/** Loader name for log messages ("Fabric", "NeoForge", "Forge"). */
	String name();

	/** True if the client registered the {@code trsclient:rules} channel (or the loader cannot tell). */
	boolean canReceive(ServerPlayer player);

	/** Sends a {@code trsclient:rules} payload (raw UTF-8 JSON bytes). Only called if {@link #canReceive} is true. */
	void send(ServerPlayer player, byte[] payload);

	/**
	 * Permission check through a permission system of the loader, if one is installed (Fabric: fabric-permissions-api,
	 * e.g. LuckPerms). Returns {@code null} when there is none – the caller then falls back to OP levels.
	 */
	Boolean permission(ServerPlayer player, String permission);
}
