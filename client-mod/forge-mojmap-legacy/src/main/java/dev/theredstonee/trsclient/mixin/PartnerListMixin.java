package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.menus.PartnerList;
import dev.theredstonee.trsclient.menus.PartnerListHost;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if <1.21.9 {
import org.spongepowered.asm.mixin.Shadow;
//?} else {
/*import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} elif >=1.20 {
/*import net.minecraft.client.gui.GuiGraphics;
*///?} elif >=1.16 {
import com.mojang.blaze3d.vertex.PoseStack;
//?}
//? if <1.19.4
import com.mojang.blaze3d.vertex.Tesselator;

/**
 * Platz für die Partner-Server vor dem ersten Eintrag der Serverliste: bis 1.21.8 über Vanillas Listen-Kopf
 * (headerHeight/renderHeader), ab 1.21.9 (Einträge mit eigener Höhe, kein Kopf mehr) über die Lage des ersten
 * Eintrags und die Inhaltshöhe. Nur die Serverliste bekommt je eine Höhe ({@link PartnerList#update}).
 */
@Mixin(AbstractSelectionList.class)
public abstract class PartnerListMixin implements PartnerListHost {
	//? if <1.16 {
	/*@Shadow
	protected int headerHeight;
	@Shadow
	protected boolean renderHeader;
	*///?} elif <1.21.9 {
	@Shadow
	protected int headerHeight;
	@Shadow
	private boolean renderHeader;
	//?} else {
	/*@Unique
	private int trsclient$partnerH;
	*///?}

	@Override
	public void trsclient$partnerHeight(int height) {
		AbstractSelectionList<?> self = (AbstractSelectionList<?>) (Object) this;
		//? if <1.21.4 {
		if (headerHeight == height && renderHeader == (height > 0)) return;
		headerHeight = height;
		renderHeader = height > 0;
		self.setScrollAmount(self.getScrollAmount());
		//?} elif <1.21.9 {
		/*if (headerHeight == height && renderHeader == (height > 0)) return;
		headerHeight = height;
		renderHeader = height > 0;
		self.refreshScrollAmount();
		*///?} else {
		/*if (trsclient$partnerH == height) return;
		trsclient$partnerH = height;
		self.setScrollAmount(self.scrollAmount());
		*///?}
	}

	//? if >=26.1 {
	/*@Inject(method = "extractListItems(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("HEAD"), require = 1)
	private void trsclient$partners(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		trsclient$draw(Gfx.of(g));
	}
	*///?} elif >=1.21.9 {
	/*@Inject(method = "renderListItems(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"), require = 1)
	private void trsclient$partners(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		trsclient$draw(Gfx.of(g));
	}
	*///?} elif >=1.20 {
	/*@Inject(method = "renderHeader(Lnet/minecraft/client/gui/GuiGraphics;II)V", at = @At("HEAD"), require = 1)
	private void trsclient$partners(GuiGraphics g, int x, int y, CallbackInfo ci) {
		PartnerList.draw(this, Gfx.of(g), x, y, ((AbstractSelectionList<?>) (Object) this).getRowWidth());
	}
	*///?} elif >=1.19.4 {
	/*@Inject(method = "renderHeader(Lcom/mojang/blaze3d/vertex/PoseStack;II)V", at = @At("HEAD"), require = 1)
	private void trsclient$partners(PoseStack pose, int x, int y, CallbackInfo ci) {
		PartnerList.draw(this, Gfx.of(pose), x, y, ((AbstractSelectionList<?>) (Object) this).getRowWidth());
	}
	*///?} elif >=1.16 {
	@Inject(method = "renderHeader(Lcom/mojang/blaze3d/vertex/PoseStack;IILcom/mojang/blaze3d/vertex/Tesselator;)V", at = @At("HEAD"), require = 1)
	private void trsclient$partners(PoseStack pose, int x, int y, Tesselator t, CallbackInfo ci) {
		PartnerList.draw(this, Gfx.of(pose), x, y, ((AbstractSelectionList<?>) (Object) this).getRowWidth());
	}
	//?} else {
	/*@Inject(method = "renderHeader(IILcom/mojang/blaze3d/vertex/Tesselator;)V", at = @At("HEAD"), require = 1)
	private void trsclient$partners(int x, int y, Tesselator t, CallbackInfo ci) {
		PartnerList.draw(this, Gfx.of(), x, y, ((AbstractSelectionList<?>) (Object) this).getRowWidth());
	}
	*///?}

	//? if >=1.21.9 {
	/*@Unique
	private void trsclient$draw(Gfx g) {
		if (trsclient$partnerH <= 0) return;
		AbstractSelectionList<?> self = (AbstractSelectionList<?>) (Object) this;
		PartnerList.draw(this, g, self.getRowLeft() + 2, self.getY() + 4 - (int) self.scrollAmount(), self.getRowWidth() - 4);
	}

	@Inject(method = "getFirstEntryY()I", at = @At("RETURN"), cancellable = true, require = 1)
	private void trsclient$firstEntryY(CallbackInfoReturnable<Integer> cir) {
		if (trsclient$partnerH > 0) cir.setReturnValue(cir.getReturnValue() + trsclient$partnerH);
	}

	@Inject(method = "contentHeight()I", at = @At("RETURN"), cancellable = true, require = 1)
	private void trsclient$contentHeight(CallbackInfoReturnable<Integer> cir) {
		if (trsclient$partnerH > 0) cir.setReturnValue(cir.getReturnValue() + trsclient$partnerH);
	}
	*///?}
}
