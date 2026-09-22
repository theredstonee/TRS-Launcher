package dev.theredstonee.trsclient.mixin;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Zugriff auf die Overlay-Textur, deren obere Hälfte die Treffer-Einfärbung von Kreaturen bestimmt. */
@Mixin(OverlayTexture.class)
public interface OverlayTextureAccessor {
	@Accessor("texture")
	DynamicTexture trsclient$getTexture();
}
