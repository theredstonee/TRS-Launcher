package dev.theredstonee.trsclient.mixin;

import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
//? if <1.19.4 {
/*import dev.theredstonee.trsclient.menus.VanillaMenus;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}
//? if >=1.16 && <1.19.4
/*import com.mojang.blaze3d.vertex.PoseStack;*/

/**
 * Bis 1.19.3 (ohne {@code Screen#renderWithTooltip}): Ladebildschirme nach ihrem eigenen Zeichnen im
 * Redstone-Stil übermalen. Ab 1.19.4 erledigt das {@link MenuScreenMixin}; dann ist diese Klasse leer und
 * steht nicht in der Mixin-Liste.
 */
//? if <1.19.4 {
/*@Mixin({LevelLoadingScreen.class, ReceivingLevelScreen.class, ConnectScreen.class, ProgressScreen.class, GenericDirtMessageScreen.class})
*///?} else
@Mixin(LevelLoadingScreen.class)
public abstract class LoadingScreenMixin {
	//? if >=1.16 && <1.19.4 {
	/*@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", at = @At("TAIL"), require = 1)
	private void trsclient$afterRender(PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		VanillaMenus.afterRender((Screen) (Object) this, Gfx.of(pose), mouseX, mouseY);
	}
	*///?} elif <1.16 {
	/*@Inject(method = "render(IIF)V", at = @At("TAIL"), require = 1)
	private void trsclient$afterRender(int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		VanillaMenus.afterRender((Screen) (Object) this, Gfx.of(), mouseX, mouseY);
	}
	*///?}
}
