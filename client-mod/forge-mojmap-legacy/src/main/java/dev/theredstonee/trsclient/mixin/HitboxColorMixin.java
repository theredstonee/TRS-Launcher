package dev.theredstonee.trsclient.mixin;

//? if >=1.15 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
//? if >=1.17 {
/*import net.minecraft.world.phys.AABB;
*///?}

/**
 * Farbe der Hitboxen (Modul "Hitboxen"). Die Trefferbox wird über {@code renderLineBox} gezeichnet;
 * die letzten vier Argumente sind immer Rot/Grün/Blau/Deckkraft. Bis 1.16.5 nimmt der Aufruf in
 * {@code renderHitbox} sechs Koordinaten, ab 1.17 eine AABB – deshalb zwei Ziele (Forge braucht die
 * volle Signatur, weil jede Überladung einen eigenen SRG-Namen hat).
 * Ab 1.17 ist {@code renderHitbox} statisch, davor eine Instanzmethode – die Hilfsmethode muss dazu passen.
 *
 * <p>Bewusst {@code @Redirect} statt {@code @ModifyArgs} (wie {@link BlockOutlineMixin}): Für
 * {@code @ModifyArgs} erzeugt Mixin zur Laufzeit eine Klasse in {@code org.spongepowered.asm.synthetic.args},
 * die der Modul-Classloader von Forge ab 1.17 nicht laden kann – das Spiel stürzt dann beim Start ab
 * ({@code NoClassDefFoundError: org/spongepowered/asm/synthetic/args/Args$1}).
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class HitboxColorMixin {
	//? if >=1.17 {
	/*@Redirect(method = "renderHitbox",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLineBox("
					+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
					+ "Lnet/minecraft/world/phys/AABB;FFFF)V"), require = 1)
	private static void trsclient$hitboxColor(PoseStack pose, VertexConsumer consumer, AABB box,
			float red, float green, float blue, float alpha) {
		int rgb = trsclient$color();
		if (rgb >= 0) {
			red = ((rgb >> 16) & 0xFF) / 255f;
			green = ((rgb >> 8) & 0xFF) / 255f;
			blue = (rgb & 0xFF) / 255f;
		}
		LevelRenderer.renderLineBox(pose, consumer, box, red, green, blue, alpha);
	}
	*///?} else {
	@Redirect(method = "renderHitbox",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLineBox("
					+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
					+ "DDDDDDFFFF)V"), require = 1)
	private void trsclient$hitboxColor(PoseStack pose, VertexConsumer consumer,
			double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
			float red, float green, float blue, float alpha) {
		int rgb = trsclient$color();
		if (rgb >= 0) {
			red = ((rgb >> 16) & 0xFF) / 255f;
			green = ((rgb >> 8) & 0xFF) / 255f;
			blue = (rgb & 0xFF) / 255f;
		}
		LevelRenderer.renderLineBox(pose, consumer, minX, minY, minZ, maxX, maxY, maxZ, red, green, blue, alpha);
	}
	//?}

	/** Eingestellte Hitbox-Farbe (RGB) oder -1, wenn das Modul aus ist bzw. der Client noch nicht steht. */
	private static int trsclient$color() {
		TrsClient client = TrsClient.get();
		if (client == null) return -1;
		TrsModules modules = client.modules();
		return modules.hitboxes.isEnabled() ? modules.hitboxColor.rgb() : -1;
	}
}
//?} else {
/*/^* Minecraft 1.14.4 zeichnet die Hitboxen noch ohne PoseStack (und Forge dort kein Mixin) – leer. ^/
public final class HitboxColorMixin {
	private HitboxColorMixin() {
	}
}
*///?}
