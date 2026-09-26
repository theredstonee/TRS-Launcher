package dev.theredstonee.trsclient.mixin;

// Schild-Position ab 26.3: Das Modell der Hand-Gegenstände wird schon beim Auslesen des Zustands gewählt
// (FirstPersonHandsAndItems#extractRenderState). Bei einem Schild bekommt Minecraft dort eine Kopie des Stapels –
// Vanillas Bedingung „blockt gerade“ vergleicht per Identität, so bleibt es beim normalen Modell und die
// Block-Haltung fährt core.shield.ShieldPosition weich an (siehe ShieldHandMixin). Nur in der Mixin-Liste ab 26.3.
//? if >=26.3 {
/*import dev.theredstonee.trsclient.render.ShieldHooks;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(FirstPersonHandsAndItems.class)
public abstract class ShieldModelMixin {
	@ModifyArg(method = "extractRenderState(Lnet/minecraft/client/player/LocalPlayer;FLnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemModelResolver;updateForTopItem(Lnet/minecraft/client/renderer/item/ItemStackRenderState;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/ItemOwner;I)V"),
			index = 1, require = 1)
	private ItemStack trsclient$shieldModel(ItemStack stack) {
		return ShieldHooks.modelStack(stack);
	}
}
*///?}
