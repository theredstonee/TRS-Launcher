package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Angriff auf eine Kreatur/einen Spieler: meldet Ziel und Trefferentfernung an die
 * Reichweiten- und Combo-Anzeige. Der Angriff selbst wird nicht verändert.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class AttackMixin {
	@Inject(method = "attack", at = @At("HEAD"), require = 1)
	private void trsclient$onAttack(Player player, Entity target, CallbackInfo ci) {
		TrsClient client = TrsClient.get();
		if (client != null) client.pvp().onAttack(player, target);
	}
}
