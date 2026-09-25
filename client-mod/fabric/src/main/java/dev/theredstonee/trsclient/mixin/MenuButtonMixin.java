package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.menus.VanillaMenus;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} elif >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} elif >=1.16
/*import com.mojang.blaze3d.vertex.PoseStack;*/
//? if <1.16 {
/*import org.spongepowered.asm.mixin.Shadow;
*///?}

/**
 * Knopfflächen im Redstone-Stil, solange ein gestyltes Vanilla-Menü offen ist. Ab 1.21.11 wird nur die
 * Standard-Fläche ersetzt (Beschriftung und Symbole bleiben die des Knopfs); davor wird die Fläche ersetzt
 * und die Vanilla-Beschriftung darüber gezeichnet. Knöpfe mit eigener Darstellung (auch von Mods) bleiben,
 * wie sie sind.
 */
//? if >=1.19.4 {
@Mixin(AbstractButton.class)
//?} else
/*@Mixin(AbstractWidget.class)*/
public abstract class MenuButtonMixin {
	//? if <1.16 {
	/*@Shadow
	protected int height;
	*///?}

	//? if >=26.1 {
	/*@Inject(method = "extractDefaultSprite(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$sprite(GuiGraphicsExtractor g, CallbackInfo ci) {
		AbstractWidget w = (AbstractWidget) (Object) this;
		if (VanillaMenus.drawButton(this, Gfx.of(g), w.getX(), w.getY(), w.getWidth(), w.getHeight(), w.isHoveredOrFocused(), w.active)) ci.cancel();
	}
	*///?} elif >=1.21.11 {
	/*@Inject(method = "renderDefaultSprite(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$sprite(GuiGraphics g, CallbackInfo ci) {
		AbstractWidget w = (AbstractWidget) (Object) this;
		if (VanillaMenus.drawButton(this, Gfx.of(g), w.getX(), w.getY(), w.getWidth(), w.getHeight(), w.isHoveredOrFocused(), w.active)) ci.cancel();
	}
	*///?} elif >=1.20 {
	@Inject(method = "renderWidget(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$widget(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		AbstractButton b = (AbstractButton) (Object) this;
		if (VanillaMenus.drawButton(this, Gfx.of(g), b.getX(), b.getY(), b.getWidth(), b.getHeight(), b.isHoveredOrFocused(), b.active)) {
			b.renderString(g, net.minecraft.client.Minecraft.getInstance().font, b.active ? 0xFFFFFFFF : 0xFFA0A0A0);
			ci.cancel();
		}
	}
	//?} elif >=1.19.4 {
	/*@Inject(method = "renderWidget(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$widget(PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		AbstractButton b = (AbstractButton) (Object) this;
		if (VanillaMenus.drawButton(this, Gfx.of(pose), b.getX(), b.getY(), b.getWidth(), b.getHeight(), b.isHoveredOrFocused(), b.active)) {
			b.renderString(pose, net.minecraft.client.Minecraft.getInstance().font, b.active ? 0xFFFFFFFF : 0xFFA0A0A0);
			ci.cancel();
		}
	}
	*///?} elif >=1.19 {
	/*@Inject(method = "renderButton(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$button(PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!((Object) this instanceof AbstractButton)) return;
		AbstractWidget w = (AbstractWidget) (Object) this;
		Gfx g = Gfx.of(pose);
		if (VanillaMenus.drawButton(this, g, w.x, w.y, w.getWidth(), w.getHeight(), w.isHoveredOrFocused(), w.active)) {
			VanillaMenus.label(g, w.getMessage().getString(), w.x, w.y, w.getWidth(), w.getHeight(), w.active);
			ci.cancel();
		}
	}
	*///?} elif >=1.16 {
	/*@Inject(method = "renderButton(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$button(PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!((Object) this instanceof AbstractButton)) return;
		AbstractWidget w = (AbstractWidget) (Object) this;
		Gfx g = Gfx.of(pose);
		if (VanillaMenus.drawButton(this, g, w.x, w.y, w.getWidth(), w.getHeight(), w.isHovered(), w.active)) {
			VanillaMenus.label(g, w.getMessage().getString(), w.x, w.y, w.getWidth(), w.getHeight(), w.active);
			ci.cancel();
		}
	}
	*///?} else {
	/*@Inject(method = "renderButton(IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$button(int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!((Object) this instanceof AbstractButton)) return;
		AbstractWidget w = (AbstractWidget) (Object) this;
		Gfx g = Gfx.of();
		if (VanillaMenus.drawButton(this, g, w.x, w.y, w.getWidth(), height, w.isHovered(), w.active)) {
			VanillaMenus.label(g, w.getMessage(), w.x, w.y, w.getWidth(), height, w.active);
			ci.cancel();
		}
	}
	*///?}
}
