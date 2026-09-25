package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Texturen für {@code core.ui} unter 1.8.9–1.12.2: Zeichnen über {@link Gui#drawModalRectWithCustomSizedTexture}
 * mit der OpenGL-Matrix (Drehen/Skalieren per {@link GlStateManager}), hochladen über {@link DynamicTexture}
 * ({@code trsclient:ui/<name>}). In allen Versionen dieses Baums gleich.
 */
public final class GfxImage {
	private GfxImage() {
	}

	/** Textur-Ausschnitt (u, v, w×h Texel) ins Rechteck (0, 0)–(w, h), eingefärbt mit ARGB. */
	public static void blit(TextureRef t, float u, float v, int w, int h, int argb) {
		Minecraft.getMinecraft().getTextureManager().bindTexture((ResourceLocation) t.id);
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
		GlStateManager.enableAlpha();
		GlStateManager.color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f,
				((argb >>> 24) & 0xFF) / 255f);
		Gui.drawModalRectWithCustomSizedTexture(0, 0, u, v, w, h, t.width, t.height);
		GlStateManager.color(1f, 1f, 1f, 1f);
	}

	public static void rotate(float radians) {
		GlStateManager.rotate((float) Math.toDegrees(radians), 0f, 0f, 1f);
	}

	public static void scale(float sx, float sy) {
		GlStateManager.scale(sx, sy, 1f);
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

		@Override
		public Textures.DefaultSkin defaultSkin(UUID uuid) {
			return new Textures.DefaultSkin(new TextureRef(DefaultPlayerSkin.getDefaultSkin(uuid), 64, 64),
					"slim".equals(DefaultPlayerSkin.getSkinType(uuid)));
		}
	}
}
