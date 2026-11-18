package dev.theredstonee.trsrules.mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import dev.theredstonee.trsrules.mod.ModServerRules;
import net.minecraft.server.MinecraftServer;

/**
 * Hosting footer: the MOTD that goes into the status response (server list) gets the footer as line 2. Only the
 * status copy is changed – the server's own MOTD (server.properties, query) stays as it is.
 */
@Mixin(MinecraftServer.class)
public abstract class ServerStatusMixin {
	// buildServerStatus(): new ServerStatus(Component.nullToEmpty(motd), …) – same call in 1.20.1 – 1.21.11
	@ModifyArg(method = "buildServerStatus", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/chat/Component;nullToEmpty(Ljava/lang/String;)Lnet/minecraft/network/chat/Component;"))
	private String trsrules$motdFooter(String motd) {
		return ModServerRules.statusMotd(motd);
	}
}
