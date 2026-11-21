package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.menus.PartnerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.21.4 {
/*import net.minecraft.client.gui.components.AbstractContainerWidget;
*///?} else
import net.minecraft.client.gui.components.AbstractSelectionList;
//? if >=1.21.9
/*import net.minecraft.client.input.MouseButtonEvent;*/

/** Klicks auf den Partner-Abschnitt der Serverliste (ab 1.21.4 liegt mouseClicked im Container-Widget). */
//? if >=1.21.4 {
/*@Mixin(AbstractContainerWidget.class)
*///?} else
@Mixin(AbstractSelectionList.class)
public abstract class PartnerClickMixin {
	//? if >=1.21.9 {
	/*@Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$partnerClick(MouseButtonEvent e, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
		if (PartnerList.click(this, e.x(), e.y(), e.button())) cir.setReturnValue(true);
	}
	*///?} else {
	@Inject(method = "mouseClicked(DDI)Z", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$partnerClick(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (PartnerList.click(this, mouseX, mouseY, button)) cir.setReturnValue(true);
	}
	//?}
}
