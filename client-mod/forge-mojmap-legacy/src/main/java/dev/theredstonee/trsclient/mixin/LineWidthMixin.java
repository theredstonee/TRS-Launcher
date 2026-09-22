package dev.theredstonee.trsclient.mixin;

//? if >=1.15 {
import com.mojang.blaze3d.systems.RenderSystem;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Stärke der Umrandungslinien (Block-Umrandung und Hitboxen). Minecraft setzt alle Linienstärken
 * über {@code RenderSystem#lineWidth}; die Vanilla-Grundstärke 2,5 wird hier auf den eingestellten
 * Wert skaliert. Die Methode gibt es erst ab 1.15 – in 1.14.4 bleibt die Stärke unverändert.
 */
@Mixin(RenderSystem.class)
public abstract class LineWidthMixin {
	/** Vanilla-Grundstärke der Linien. */
	private static final float BASE = 2.5F;

	@ModifyVariable(method = "lineWidth", at = @At("HEAD"), argsOnly = true, require = 1)
	private static float trsclient$lineWidth(float width) {
		TrsClient client = TrsClient.get();
		if (client == null) return width;
		TrsModules modules = client.modules();
		if (!modules.blockOutline.isEnabled()) return width;
		return width * (float) modules.blockOutlineWidth.get() / BASE;
	}
}
//?} else {
/*/^* RenderSystem#lineWidth gibt es erst ab 1.15 (und Forge dort kein Mixin) – leer. ^/
public final class LineWidthMixin {
	private LineWidthMixin() {
	}
}
*///?}
