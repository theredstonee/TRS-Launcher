package dev.theredstonee.trsclient.mixin;

//? if >=1.15 {
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Zugriff auf die Overlay-Textur, deren obere Hälfte die Treffer-Einfärbung von Kreaturen bestimmt (ab 1.15). */
@Mixin(OverlayTexture.class)
public interface OverlayTextureAccessor {
	@Accessor("texture")
	DynamicTexture trsclient$getTexture();
}
//?} else {
/*/^* 1.14.4 hat noch keine Overlay-Textur (und Forge noch kein Mixin) – leer, wird nicht ausgeliefert. ^/
public interface OverlayTextureAccessor {
}
*///?}
