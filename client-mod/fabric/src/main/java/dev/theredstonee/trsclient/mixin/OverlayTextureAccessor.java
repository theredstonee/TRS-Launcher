package dev.theredstonee.trsclient.mixin;

// Die Overlay-Textur gibt es erst ab 1.15 (in 1.14 nicht in trsclient.mixins.json eingetragen).
//? if >=1.15 {
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
//?}
