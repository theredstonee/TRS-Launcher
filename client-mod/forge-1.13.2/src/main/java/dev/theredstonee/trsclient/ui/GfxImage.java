package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Texturen für {@code core.ui} unter 1.13.2 (MCP): Zeichnen über {@link Gui#drawModalRectWithCustomSizedTexture}
 * mit der OpenGL-Matrix, hochladen über NativeImage → {@link DynamicTexture} ({@code trsclient:ui/<name>}).
 */
public final class GfxImage {
	private GfxImage() {
	}

	/** Textur-Ausschnitt (u, v, w×h Texel) ins Rechteck (0, 0)–(w, h), eingefärbt mit ARGB. */
	public static void blit(TextureRef t, float u, float v, int w, int h, int argb) {
		Minecraft.getInstance().getTextureManager().bindTexture((ResourceLocation) t.id);
		GlStateManager.enableBlend();
		GlStateManager.blendFuncSeparate(770, 771, 1, 0);
		GlStateManager.enableAlphaTest();
		GlStateManager.color4f(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f,
				((argb >>> 24) & 0xFF) / 255f);
		Gui.drawModalRectWithCustomSizedTexture(0, 0, u, v, w, h, t.width, t.height);
		GlStateManager.color4f(1f, 1f, 1f, 1f);
	}

	public static void rotate(float radians) {
		GlStateManager.rotatef((float) Math.toDegrees(radians), 0f, 0f, 1f);
	}

	public static void scale(float sx, float sy) {
		GlStateManager.scalef(sx, sy, 1f);
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
		private Object seenManager;
		private int generation;
		private boolean reloadHooked;

		private Store() {
		}

		@Override
		public TextureRef upload(String name, int width, int height, int[] argb) {
			if (!Textures.validName(name) || width <= 0 || height <= 0 || width > 4096 || height > 4096 || argb == null
					|| argb.length < width * height) return null;
			try {
				Entry old = entries.get(name);
				if (old != null && old.ref.width == width && old.ref.height == height && old.texture.getTextureData() != null) {
					write(old.texture.getTextureData(), width, height, argb);
					old.texture.updateDynamicTexture();
					return old.ref;
				}
				if (old != null) release(old.ref);
				NativeImage img = new NativeImage(width, height, false);
				write(img, width, height, argb);
				DynamicTexture texture = new DynamicTexture(img);
				ResourceLocation id = new ResourceLocation("trsclient", "ui/" + name);
				Minecraft.getInstance().getTextureManager().loadTexture(id, texture);
				TextureRef ref = new TextureRef(id, width, height);
				entries.put(name, new Entry(texture, ref));
				return ref;
			} catch (RuntimeException e) {
				return null;
			}
		}

		/** NativeImage erwartet ABGR. */
		private static void write(NativeImage img, int width, int height, int[] argb) {
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int c = argb[y * width + x];
					img.setPixelRGBA(x, y, (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16));
				}
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
				Minecraft.getInstance().getTextureManager().deleteTexture((ResourceLocation) texture.id);
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

		@Override
		public byte[] gameBytes(String location) {
			int colon = location == null ? -1 : location.indexOf(':');
			if (colon <= 0 || colon >= location.length() - 1) return null;
			try {
				net.minecraft.resources.IResource res = Minecraft.getInstance().getResourceManager()
						.getResource(new ResourceLocation(location.substring(0, colon), location.substring(colon + 1)));
				try (java.io.InputStream in = res.getInputStream()) {
					return readAll(in);
				} finally {
					res.close();
				}
			} catch (Throwable ignored) {
				return null;
			}
		}

		@Override
		public int resourceGeneration() {
			try {
				net.minecraft.resources.IResourceManager manager = Minecraft.getInstance().getResourceManager();
				if (manager != seenManager) {
					seenManager = manager;
					generation++;
					reloadHooked = false;
				}
				if (!reloadHooked && manager instanceof net.minecraft.resources.IReloadableResourceManager) {
					reloadHooked = true;
					((net.minecraft.resources.IReloadableResourceManager) manager).addReloadListener(
							new net.minecraft.resources.IResourceManagerReloadListener() {
								@Override
								public void onResourceManagerReload(net.minecraft.resources.IResourceManager resourceManager) {
									generation++;
								}
							});
				}
			} catch (Throwable ignored) {
			}
			return generation;
		}

		private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(4096);
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				if (out.size() > (4 << 20)) return null;
			}
			return out.toByteArray();
		}
	}
}
