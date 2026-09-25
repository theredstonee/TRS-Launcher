package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.skin.SkinImage;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Texturen für {@code core.ui} unter 1.7.10: ein Textur-Viereck über den Tessellator mit der OpenGL-Matrix,
 * hochladen über {@link DynamicTexture}. 1.7.10 kennt nur den alten 64×32-Steve – er wird einmal ins
 * 64×64-Format umgebaut ({@link SkinImage#normalize}), damit die Figur dieselbe UV-Aufteilung nutzt.
 */
public final class GfxImage {
	private GfxImage() {
	}

	/** Textur-Ausschnitt (u, v, w×h Texel) ins Rechteck (0, 0)–(w, h), eingefärbt mit ARGB. */
	public static void blit(TextureRef t, float u, float v, int w, int h, int argb) {
		Minecraft.getMinecraft().getTextureManager().bindTexture((ResourceLocation) t.id);
		GL11.glEnable(GL11.GL_BLEND);
		OpenGlHelper.glBlendFunc(770, 771, 1, 0);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glColor4f(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f,
				((argb >>> 24) & 0xFF) / 255f);
		float u0 = u / t.width;
		float v0 = v / t.height;
		float u1 = (u + w) / t.width;
		float v1 = (v + h) / t.height;
		Tessellator tes = Tessellator.instance;
		tes.startDrawingQuads();
		tes.addVertexWithUV(0, h, 0, u0, v1);
		tes.addVertexWithUV(w, h, 0, u1, v1);
		tes.addVertexWithUV(w, 0, 0, u1, v0);
		tes.addVertexWithUV(0, 0, 0, u0, v0);
		tes.draw();
		GL11.glColor4f(1f, 1f, 1f, 1f);
	}

	public static void rotate(float radians) {
		GL11.glRotatef((float) Math.toDegrees(radians), 0f, 0f, 1f);
	}

	public static void scale(float sx, float sy) {
		GL11.glScalef(sx, sy, 1f);
	}

	/** Dynamische Texturen und Standard-Skin ({@link Textures.Store}). */
	public static final class Store implements Textures.Store {
		public static final Store INSTANCE = new Store();

		private static final class Entry {
			final DynamicTexture texture;
			final TextureRef ref;

			Entry(DynamicTexture texture, TextureRef ref) {
				this.texture = texture;
				this.ref = ref;
			}
		}

		private final Map<String, Entry> entries = new HashMap<String, Entry>();
		private Textures.DefaultSkin steve;

		private Store() {
		}

		@Override
		public TextureRef upload(String name, int width, int height, int[] argb) {
			if (!Textures.validName(name) || width <= 0 || height <= 0 || width > 4096 || height > 4096 || argb == null
					|| argb.length < width * height) return null;
			try {
				Entry old = entries.get(name);
				if (old != null && old.ref.width == width && old.ref.height == height) {
					System.arraycopy(argb, 0, old.texture.getTextureData(), 0, width * height);
					old.texture.updateDynamicTexture();
					return old.ref;
				}
				if (old != null) release(old.ref);
				DynamicTexture texture = new DynamicTexture(width, height);
				System.arraycopy(argb, 0, texture.getTextureData(), 0, width * height);
				texture.updateDynamicTexture();
				ResourceLocation id = new ResourceLocation("trsclient", "ui/" + name);
				Minecraft.getMinecraft().getTextureManager().loadTexture(id, texture);
				TextureRef ref = new TextureRef(id, width, height);
				entries.put(name, new Entry(texture, ref));
				return ref;
			} catch (RuntimeException e) {
				return null;
			}
		}

		@Override
		public void release(TextureRef texture) {
			if (texture == null) return;
			String found = null;
			for (Map.Entry<String, Entry> e : entries.entrySet()) {
				if (e.getValue().ref.equals(texture)) found = e.getKey();
			}
			if (found == null) return;
			entries.remove(found);
			try {
				Minecraft.getMinecraft().getTextureManager().deleteTexture((ResourceLocation) texture.id);
			} catch (RuntimeException e) {
				// schon weg
			}
		}

		@Override
		public TextureRef game(String location, int width, int height) {
			return new TextureRef(new ResourceLocation(location), width, height);
		}

		/** Steve (64×32) einmal gelesen, ins 64×64-Format gebracht und hochgeladen; Alex gibt es in 1.7.10 nicht. */
		@Override
		public Textures.DefaultSkin defaultSkin(UUID uuid) {
			if (steve != null) return steve;
			TextureRef ref = null;
			try {
				InputStream in = Minecraft.getMinecraft().getResourceManager()
						.getResource(AbstractClientPlayer.locationStevePng).getInputStream();
				try {
					ByteArrayOutputStream out = new ByteArrayOutputStream();
					byte[] buf = new byte[4096];
					int n;
					while ((n = in.read(buf)) > 0 && out.size() < 256 * 1024) out.write(buf, 0, n);
					PngDecoder.Image img = PngDecoder.decode(out.toByteArray());
					if (SkinImage.validSize(img.width, img.height)) {
						ref = upload("skins/steve", 64, 64, SkinImage.normalize(img.width, img.height, img.argb));
					}
				} finally {
					in.close();
				}
			} catch (Exception e) {
				ref = null;
			}
			if (ref == null) ref = new TextureRef(AbstractClientPlayer.locationStevePng, 64, 64);
			steve = new Textures.DefaultSkin(ref, false);
			return steve;
		}
	}
}
