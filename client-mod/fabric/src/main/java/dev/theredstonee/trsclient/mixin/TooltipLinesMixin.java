package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.comfort.ComfortHooks;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.20.5
import net.minecraft.world.item.Item;

import java.util.List;

/**
 * Bessere Tooltips: Text-Zusätze an der fertigen Tooltip-Liste (Haltbarkeit als Zahl/Prozent, kompakte
 * Verzauberungen mit „Umschalt für Details“). Nur Anzeige; Logik in {@link ComfortHooks#lines}.
 */
@Mixin(ItemStack.class)
public abstract class TooltipLinesMixin {
	//? if >=1.20.5 {
	@Inject(method = "getTooltipLines(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;)Ljava/util/List;",
			at = @At("RETURN"), require = 1)
	private void trsclient$tooltipLines(Item.TooltipContext context, Player player, TooltipFlag flag, CallbackInfoReturnable<List<Component>> cir) {
		ComfortHooks.lines((ItemStack) (Object) this, cir.getReturnValue(), flag.isAdvanced());
	}
	//?} else {
	/*@Inject(method = "getTooltipLines(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;)Ljava/util/List;",
			at = @At("RETURN"), require = 1)
	private void trsclient$tooltipLines(Player player, TooltipFlag flag, CallbackInfoReturnable<List<Component>> cir) {
		ComfortHooks.lines((ItemStack) (Object) this, cir.getReturnValue(), flag.isAdvanced());
	}
	*///?}
}
