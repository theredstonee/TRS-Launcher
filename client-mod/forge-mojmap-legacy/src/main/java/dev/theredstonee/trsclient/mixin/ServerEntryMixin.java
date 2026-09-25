package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.menus.VanillaMenus;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Final;
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

/**
 * Serverkarten im Redstone-Stil: Karte hinter dem Vanilla-Eintrag (Symbol, Name, MOTD, Spielerzahl bleiben von
 * Minecraft), danach Ping als Staub-Balken, Fackel für angeheftete Server und Gesichter von TRS-Freunden, die
 * dort gerade spielen.
 */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class ServerEntryMixin {
	@Shadow
	@Final
	private ServerData serverData;

	//? if >=26.1 {
	/*@Inject(method = "extractContent(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIZF)V", at = @At("HEAD"), require = 1)
	private void trsclient$before(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partialTick, CallbackInfo ci) {
		ServerSelectionList.OnlineServerEntry e = (ServerSelectionList.OnlineServerEntry) (Object) this;
		VanillaMenus.serverEntryBefore(Gfx.of(g), this, serverData, e.getContentX(), e.getContentY(), e.getContentWidth(), e.getContentHeight(), hovered);
	}

	@Inject(method = "extractContent(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIZF)V", at = @At("TAIL"), require = 1)
	private void trsclient$after(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partialTick, CallbackInfo ci) {
		ServerSelectionList.OnlineServerEntry e = (ServerSelectionList.OnlineServerEntry) (Object) this;
		VanillaMenus.serverEntryAfter(Gfx.of(g), this, serverData, e.getContentX(), e.getContentY(), e.getContentWidth(), e.getContentHeight(), hovered);
	}
	*///?} elif >=1.21.9 {
	/*@Inject(method = "renderContent(Lnet/minecraft/client/gui/GuiGraphics;IIZF)V", at = @At("HEAD"), require = 1)
	private void trsclient$before(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float partialTick, CallbackInfo ci) {
		ServerSelectionList.OnlineServerEntry e = (ServerSelectionList.OnlineServerEntry) (Object) this;
		VanillaMenus.serverEntryBefore(Gfx.of(g), this, serverData, e.getContentX(), e.getContentY(), e.getContentWidth(), e.getContentHeight(), hovered);
	}

	@Inject(method = "renderContent(Lnet/minecraft/client/gui/GuiGraphics;IIZF)V", at = @At("TAIL"), require = 1)
	private void trsclient$after(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float partialTick, CallbackInfo ci) {
		ServerSelectionList.OnlineServerEntry e = (ServerSelectionList.OnlineServerEntry) (Object) this;
		VanillaMenus.serverEntryAfter(Gfx.of(g), this, serverData, e.getContentX(), e.getContentY(), e.getContentWidth(), e.getContentHeight(), hovered);
	}
	*///?} elif >=1.20 {
	/*@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIIIIIZF)V", at = @At("HEAD"), require = 1)
	private void trsclient$before(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
			boolean hovered, float partialTick, CallbackInfo ci) {
		VanillaMenus.serverEntryBefore(Gfx.of(g), this, serverData, left, top, width, height, hovered);
	}

	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIIIIIZF)V", at = @At("TAIL"), require = 1)
	private void trsclient$after(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
			boolean hovered, float partialTick, CallbackInfo ci) {
		VanillaMenus.serverEntryAfter(Gfx.of(g), this, serverData, left, top, width, height, hovered);
	}
	*///?} elif >=1.16 {
	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIIIIZF)V", at = @At("HEAD"), require = 1)
	private void trsclient$before(PoseStack pose, int index, int top, int left, int width, int height, int mouseX, int mouseY,
			boolean hovered, float partialTick, CallbackInfo ci) {
		VanillaMenus.serverEntryBefore(Gfx.of(pose), this, serverData, left, top, width, height, hovered);
	}

	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIIIIZF)V", at = @At("TAIL"), require = 1)
	private void trsclient$after(PoseStack pose, int index, int top, int left, int width, int height, int mouseX, int mouseY,
			boolean hovered, float partialTick, CallbackInfo ci) {
		VanillaMenus.serverEntryAfter(Gfx.of(pose), this, serverData, left, top, width, height, hovered);
	}
	//?} else {
	/*@Inject(method = "render(IIIIIIIZF)V", at = @At("HEAD"), require = 1)
	private void trsclient$before(int index, int top, int left, int width, int height, int mouseX, int mouseY,
			boolean hovered, float partialTick, CallbackInfo ci) {
		VanillaMenus.serverEntryBefore(Gfx.of(), this, serverData, left, top, width, height, hovered);
	}

	@Inject(method = "render(IIIIIIIZF)V", at = @At("TAIL"), require = 1)
	private void trsclient$after(int index, int top, int left, int width, int height, int mouseX, int mouseY,
			boolean hovered, float partialTick, CallbackInfo ci) {
		VanillaMenus.serverEntryAfter(Gfx.of(), this, serverData, left, top, width, height, hovered);
	}
	*///?}
}
