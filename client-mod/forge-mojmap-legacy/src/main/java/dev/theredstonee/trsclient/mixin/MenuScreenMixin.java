package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.menus.VanillaMenus;
import dev.theredstonee.trsclient.menus.WidgetHost;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} elif >=1.20 {
/*import net.minecraft.client.gui.GuiGraphics;
*///?} elif >=1.16
import com.mojang.blaze3d.vertex.PoseStack;
//? if >=1.19.3 {
/*import net.minecraft.client.gui.components.Renderable;
*///?} elif >=1.17
/*import net.minecraft.client.gui.components.Widget;*/
//? if >=1.17 {
/*import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
*///?}

/**
 * Redstone-Stil für Vanilla-Menüs: ersetzt den Hintergrund gestylter Menüs, meldet das Ende von init()
 * (TRS-Knöpfe, Listen) und zeichnet Ladebildschirme nach dem ganzen Bild neu. Das Menü selbst – mit allen
 * Knöpfen, auch denen anderer Mods – bleibt unverändert. Logik in {@link VanillaMenus}.
 */
@Mixin(Screen.class)
public abstract class MenuScreenMixin implements WidgetHost {
	//? if >=1.19.3 {
	/*@Shadow
	protected abstract <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget);
	*///?} elif >=1.17 {
	/*@Shadow
	protected abstract <T extends GuiEventListener & Widget & NarratableEntry> T addRenderableWidget(T widget);
	*///?} else {
	@Shadow
	protected abstract <T extends AbstractWidget> T addButton(T widget);
	//?}

	@Override
	public void trsclient$addWidget(Object widget) {
		//? if >=1.17 {
		/*addRenderableWidget((AbstractWidget) widget);
		*///?} else
		addButton((AbstractWidget) widget);
	}

	// --- Hintergrund ---

	//? if >=26.1 {
	/*@Inject(method = "extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$background(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (VanillaMenus.drawBackground((Screen) (Object) this, Gfx.of(g))) ci.cancel();
	}
	*///?} elif >=1.20.2 {
	/*@Inject(method = "renderBackground(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$background(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (VanillaMenus.drawBackground((Screen) (Object) this, Gfx.of(g))) ci.cancel();
	}
	*///?} elif >=1.20 {
	/*@Inject(method = "renderBackground(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$background(GuiGraphics g, CallbackInfo ci) {
		if (VanillaMenus.drawBackground((Screen) (Object) this, Gfx.of(g))) ci.cancel();
	}
	*///?} elif >=1.16 {
	@Inject(method = "renderBackground(Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$background(PoseStack pose, CallbackInfo ci) {
		if (VanillaMenus.drawBackground((Screen) (Object) this, Gfx.of(pose))) ci.cancel();
	}
	//?} else {
	/*@Inject(method = "renderBackground()V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$background(CallbackInfo ci) {
		if (VanillaMenus.drawBackground((Screen) (Object) this, Gfx.of())) ci.cancel();
	}
	*///?}

	// --- Nach init() ---

	//? if >=1.21.11 {
	/*@Inject(method = "init(II)V", at = @At("TAIL"), require = 1)
	private void trsclient$afterInit(int width, int height, CallbackInfo ci) {
		VanillaMenus.afterInit((Screen) (Object) this, this);
	}
	*///?} else {
	@Inject(method = "init(Lnet/minecraft/client/Minecraft;II)V", at = @At("TAIL"), require = 1)
	private void trsclient$afterInit(net.minecraft.client.Minecraft mc, int width, int height, CallbackInfo ci) {
		VanillaMenus.afterInit((Screen) (Object) this, this);
	}
	//?}

	//? if >=1.19.4 {
	/*@Inject(method = "rebuildWidgets()V", at = @At("TAIL"), require = 1)
	private void trsclient$afterRebuild(CallbackInfo ci) {
		VanillaMenus.afterInit((Screen) (Object) this, this);
	}
	*///?}

	// --- Nach dem ganzen Bild (Ladebildschirme); bis 1.19.3 in LoadingScreenMixin ---

	//? if >=26.1 {
	/*@Inject(method = "extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("TAIL"), require = 1)
	private void trsclient$afterRender(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		VanillaMenus.afterRender((Screen) (Object) this, Gfx.of(g), mouseX, mouseY);
	}
	*///?} elif >=1.21.9 {
	/*@Inject(method = "renderWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"), require = 1)
	private void trsclient$afterRender(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		VanillaMenus.afterRender((Screen) (Object) this, Gfx.of(g), mouseX, mouseY);
	}
	*///?} elif >=1.20 {
	/*@Inject(method = "renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"), require = 1)
	private void trsclient$afterRender(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		VanillaMenus.afterRender((Screen) (Object) this, Gfx.of(g), mouseX, mouseY);
	}
	*///?} elif >=1.19.4 {
	/*@Inject(method = "renderWithTooltip(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", at = @At("TAIL"), require = 1)
	private void trsclient$afterRender(PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		VanillaMenus.afterRender((Screen) (Object) this, Gfx.of(pose), mouseX, mouseY);
	}
	*///?}
}
