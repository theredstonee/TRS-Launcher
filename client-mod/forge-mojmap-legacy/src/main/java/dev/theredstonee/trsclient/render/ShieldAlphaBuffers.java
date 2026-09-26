package dev.theredstonee.trsclient.render;

// Nur 1.15 – 1.21.8: dort zeichnet der Hand-Renderer über eine MultiBufferSource. Ab 1.21.9 regelt
// ShieldHooks#renderType/#tint die Deckkraft im Submit-Sammler, in 1.14 die GL-Farbe.
//? if >=1.15 && <1.21.9 {
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;

// Schild beim Blocken halb durchsichtig (Modul Schild-Position): liefert statt der deckenden Schild-Textur
// (entity_solid) die durchsichtige Variante (entity_translucent) und multipliziert die Deckkraft jedes Punkts –
// auch Muster und Glanz werden so durchsichtig. Alles andere geht unverändert an den echten Puffer.
public final class ShieldAlphaBuffers implements MultiBufferSource {
	private final MultiBufferSource inner;
	private final float alpha;

	public ShieldAlphaBuffers(MultiBufferSource inner, float alpha) {
		this.inner = inner;
		this.alpha = alpha;
	}

	@Override
	public VertexConsumer getBuffer(RenderType type) {
		RenderType mapped = type;
		if (type.equals(RenderType.entitySolid(Sheets.SHIELD_SHEET))) mapped = RenderType.entityTranslucent(Sheets.SHIELD_SHEET);
		return new Alpha(inner.getBuffer(mapped), alpha);
	}

	// Multipliziert die Deckkraft jedes Punkts; alles andere geht unverändert durch.
	static final class Alpha implements VertexConsumer {
		private final VertexConsumer inner;
		private final float alpha;

		Alpha(VertexConsumer inner, float alpha) {
			this.inner = inner;
			this.alpha = alpha;
		}

		private int a(int value) {
			return Math.max(0, Math.min(255, Math.round(value * alpha)));
		}

		//? if >=1.21 {
		/*@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			inner.addVertex(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			inner.setColor(r, g, b, a(a));
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			inner.setUv(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			inner.setUv1(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			inner.setUv2(u, v);
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			inner.setNormal(x, y, z);
			return this;
		}
		*///?} else {
		@Override
		public VertexConsumer vertex(double x, double y, double z) {
			inner.vertex(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer color(int r, int g, int b, int a) {
			inner.color(r, g, b, a(a));
			return this;
		}

		@Override
		public VertexConsumer uv(float u, float v) {
			inner.uv(u, v);
			return this;
		}

		@Override
		public VertexConsumer overlayCoords(int u, int v) {
			inner.overlayCoords(u, v);
			return this;
		}

		@Override
		public VertexConsumer uv2(int u, int v) {
			inner.uv2(u, v);
			return this;
		}

		@Override
		public VertexConsumer normal(float x, float y, float z) {
			inner.normal(x, y, z);
			return this;
		}

		@Override
		public void endVertex() {
			inner.endVertex();
		}
		//?}

		//? if >=1.17 && <1.21 {
		/*@Override
		public void defaultColor(int r, int g, int b, int a) {
			inner.defaultColor(r, g, b, a(a));
		}

		@Override
		public void unsetDefaultColor() {
			inner.unsetDefaultColor();
		}
		*///?}
	}
}
//?}
