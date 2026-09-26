package dev.theredstonee.trsclient.mixin;

// TRS-Kopf-Kosmetik (Quietscheente) in 1.14: Spieler werden dort noch mit festen GL-Aufrufen gezeichnet – eigener
// Weg über den Tesselator, eingehängt am Umhang-Layer (läuft für jeden Spieler). Nur in der Mixin-Liste für 1.14.
//? if <1.15 {
/*import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import dev.theredstonee.trsclient.core.cosmetic.Wearer;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import dev.theredstonee.trsclient.online.OnlineHooks;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(CapeLayer.class)
public abstract class HatGlMixin {
	@Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFFFFFF)V", at = @At("HEAD"), require = 0)
	private void trsclient$hat(AbstractClientPlayer p, float limbSwing, float limbSwingAmount, float partial, float age,
			float headYaw, float headPitch, float scale, CallbackInfo ci) {
		OnlineFeatures<Object> features = OnlineHooks.features();
		if (features == null || p.isInvisible()) return;
		UUID id = p.getUUID();
		Object tex = features.hatTexture(id);
		if (tex == null) return;
		double dx = p.x - p.xo;
		double dz = p.z - p.zo;
		Wearer w = new Wearer().set(p.getId(), (float) Math.sqrt(dx * dx + dz * dz), (float) (p.y - p.yo), p.onGround,
				p.isSneaking(), p.isSprinting(), p.isInWater(), p.yHeadRot, p.xRot, false,
				!p.getItemBySlot(EquipmentSlot.HEAD).isEmpty());
		@SuppressWarnings("unchecked")
		RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> self =
				(RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>) (Object) this;
		GlStateManager.color4f(1f, 1f, 1f, 1f);
		self.bindTexture((ResourceLocation) tex);
		GlStateManager.pushMatrix();
		GlStateManager.disableCull();
		try {
			if (p.isSneaking()) GlStateManager.translatef(0f, 0.2f, 0f);
			self.getParentModel().head.translateTo(0.0625f);
			Tesselator tesselator = Tesselator.getInstance();
			BufferBuilder buffer = tesselator.getBuilder();
			buffer.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX_NORMAL);
			try {
				features.emitHat(id, w, (x, y, z, u, v, nx, ny, nz) -> buffer.vertex(x, y, z).uv(u, v).normal(nx, ny, nz).endVertex());
			} finally {
				tesselator.end();
			}
		} catch (RuntimeException e) {
			features.online().reportError(e);
		} finally {
			GlStateManager.enableCull();
			GlStateManager.popMatrix();
		}
	}
}
*///?}
