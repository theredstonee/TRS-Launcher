package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.menus.VanillaMenus;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} elif >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} elif >=1.16
/*import com.mojang.blaze3d.vertex.PoseStack;*/

/**
 * Textfelder im Redstone-Stil, solange ein gestyltes Vanilla-Menü offen ist (Direkt verbinden, Server
 * hinzufügen, Suche in den Welten …). In allen Versionen fragt das Feld zuerst {@code isBordered()} und
 * zeichnet nur dann den Rahmen (bis 1.20.1 zwei Rechtecke, danach ein Sprite): direkt davor zeichnen wir die
 * eigene Fläche und schalten den Rahmen für genau diesen Aufruf ab, direkt danach wieder an – Text,
 * Schreibmarke, Auswahl und Platzhalter bleiben die von Vanilla an derselben Stelle.
 */
@Mixin(EditBox.class)
public abstract class MenuEditBoxMixin {
	@Shadow
	private boolean bordered;

	@Unique
	private boolean trsclient$borderHidden;

	//? if >=26.1 {
	/*@Inject(method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;isBordered()Z", ordinal = 0))
	private void trsclient$field(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		trsclient$draw(Gfx.of(g), mouseX, mouseY, false);
	}

	@Inject(method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;isBordered()Z", ordinal = 0, shift = At.Shift.AFTER))
	private void trsclient$fieldDone(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		trsclient$restore();
	}
	*///?} elif >=1.20.2 {
	@Inject(method = "renderWidget(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;isBordered()Z", ordinal = 0))
	private void trsclient$field(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		trsclient$draw(Gfx.of(g), mouseX, mouseY, false);
	}

	@Inject(method = "renderWidget(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;isBordered()Z", ordinal = 0, shift = At.Shift.AFTER))
	private void trsclient$fieldDone(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		trsclient$restore();
	}
	//?} elif >=1.20 {
	/*@Inject(method = "renderWidget(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;isBordered()Z", ordinal = 0))
	private void trsclient$field(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		trsclient$draw(Gfx.of(g), mouseX, mouseY, true);
	}

	@Inject(method = "renderWidget(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;isBordered()Z", ordinal = 0, shift = At.Shift.AFTER))
	private void trsclient$fieldDone(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		trsclient$restore();
	}
	*///?} elif >=1.19.4 {
	/*@Inject(method = "renderWidget(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;isBordered()Z", ordinal = 0))
	private void trsclient$field(PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		trsclient$draw(Gfx.of(pose), mouseX, mouseY, true);
	}

	@Inject(method = "renderWidget(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;isBordered()Z", ordinal = 0, shift = At.Shift.AFTER))
	private void trsclient$fieldDone(PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		trsclient$restore();
	}
	*///?} elif >=1.16 {
	/*@Inject(method = "renderButton(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;isBordered()Z", ordinal = 0))
	private void trsclient$field(PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		trsclient$draw(Gfx.of(pose), mouseX, mouseY, true);
	}

	@Inject(method = "renderButton(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;isBordered()Z", ordinal = 0, shift = At.Shift.AFTER))
	private void trsclient$fieldDone(PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		trsclient$restore();
	}
	*///?} else {
	/*@Inject(method = "renderButton(IIF)V", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;isBordered()Z", ordinal = 0))
	private void trsclient$field(int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		trsclient$draw(Gfx.of(), mouseX, mouseY, true);
	}

	@Inject(method = "renderButton(IIF)V", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;isBordered()Z", ordinal = 0, shift = At.Shift.AFTER))
	private void trsclient$fieldDone(int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		trsclient$restore();
	}
	*///?}

	/** {@code outer}: Vanilla-Rahmen liegt 1 Pixel außerhalb des Felds (bis 1.20.1), sonst genau auf dem Feld. */
	@Unique
	private void trsclient$draw(Gfx g, int mouseX, int mouseY, boolean outer) {
		trsclient$borderHidden = false;
		if (!bordered) return;
		AbstractWidget w = (AbstractWidget) (Object) this;
		int[] r = VanillaMenus.rect(w);
		boolean hover = mouseX >= r[0] && mouseY >= r[1] && mouseX < r[0] + r[2] && mouseY < r[1] + r[3];
		int d = outer ? 1 : 0;
		if (VanillaMenus.drawTextField(g, r[0] - d, r[1] - d, r[2] + 2 * d, r[3] + 2 * d, w.isFocused(), hover, w.active)) {
			bordered = false;
			trsclient$borderHidden = true;
		}
	}

	@Unique
	private void trsclient$restore() {
		if (trsclient$borderHidden) {
			bordered = true;
			trsclient$borderHidden = false;
		}
	}
}
