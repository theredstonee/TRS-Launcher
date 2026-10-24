package dev.theredstonee.trsclient.mixin;

// Oberflächen-Zustand des Bildes im GameRenderer: 1.21.6–1.21.11 (davor gibt es ihn nicht, ab 26.1 liegt er öffentlich
// im GameRenderState) – sonst nicht in der Mixin-Liste, Klasse leer.
//? if >=1.21.6 && <26.1 {
/*import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/^*
 * Zugriff auf den Oberflächen-Zustand des laufenden Bildes: ruft ein anderer Mod (RRLS) die Ladeüberblendung mit einer
 * Attrappe auf, zeichnet die TRS-Fläche über ein eigenes Zeichenziel auf demselben Zustand – sie landet so im selben Bild.
 *^/
@Mixin(GameRenderer.class)
public interface GuiStateAccessor {
	@Accessor("guiRenderState")
	GuiRenderState trsclient$guiRenderState();
}
*///?}
