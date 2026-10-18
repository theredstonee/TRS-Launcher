package dev.theredstonee.trsclient.online;

import com.mojang.blaze3d.platform.NativeImage;
import dev.theredstonee.trsclient.core.cape.CapeFilter;

/**
 * Textur für echte HD-Umhänge („Automatisch“, siehe {@link CapeFilter}): linear gefiltert mit Mipmaps, damit Fotos,
 * Logos und gemalte Umhänge aus der Entfernung nicht flimmern. Pixel-Art bleibt eine scharfe DynamicTexture.
 *
 * <p>Vanilla stellt den Filter beim Zeichnen zurück (bis 1.21.5 {@code setFilter}, 1.21.6–1.21.10 {@code setUseMipmaps}) –
 * diese Textur bleibt dabei weich. Ab 1.21.11 entscheidet der Sampler der Textur. Die Mipmaps kommen aus
 * {@link CapeFilter#mipChain} (mit Deckkraft gewichtet) und werden einzeln hochgeladen; die Pixel bleiben danach nicht im
 * Arbeitsspeicher (bis 1.21.4 hält DynamicTexture Ebene 0 wie bisher).
 */
final class SmoothCapeTexture {
	private SmoothCapeTexture() {
	}

	/** Weiche Textur samt Mipmaps, oder null (dann wie bisher scharf). */
	static net.minecraft.client.renderer.texture.AbstractTexture create(String label, int width, int height, int[] argb) {
		int[][] chain = CapeFilter.mipChain(argb, width, height);
		NativeImage[] levels = new NativeImage[chain.length];
		boolean owned = false;
		try {
			for (int l = 0; l < chain.length; l++) levels[l] = OnlineHooks.Textures.image(width >> l, height >> l, chain[l]);
			Texture texture = new Texture(label, levels);
			owned = true;
			return texture;
		} catch (RuntimeException e) {
			return null;
		} finally {
			// Ebene 0 gehört bis 1.21.4 der DynamicTexture; alles andere ist hochgeladen und wird frei.
			for (int l = owned ? keptLevels() : 0; l < levels.length; l++) {
				if (levels[l] != null) levels[l].close();
			}
		}
	}

	//? if >=1.21.5 {
	/*private static int keptLevels() {
		return 0;
	}
	*///?} else {
	private static int keptLevels() {
		return 1;
	}
	//?}

	//? if >=26.3 {
	/*static final class Texture extends net.minecraft.client.renderer.texture.AbstractTexture {
		Texture(String label, NativeImage[] levels) {
			int w = levels[0].getWidth();
			int h = levels[0].getHeight();
			boolean mipmapped = levels.length > 1;
			var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
			this.texture = device.createTexture(() -> label,
					com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_DST | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_TEXTURE_BINDING,
					com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM, w, h, 1, levels.length);
			this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
					.getRepeat(com.mojang.renderpearl.api.textures.FilterMode.LINEAR, mipmapped);
			this.textureView = device.createTextureView(this.texture);
			var encoder = device.createCommandEncoder();
			for (int l = 0; l < levels.length; l++) encoder.writeToTexture(this.texture, levels[l], l, 0, 0, 0);
		}
	}
	*///?} elif >=26.2 {
	/*static final class Texture extends net.minecraft.client.renderer.texture.AbstractTexture {
		Texture(String label, NativeImage[] levels) {
			int w = levels[0].getWidth();
			int h = levels[0].getHeight();
			boolean mipmapped = levels.length > 1;
			var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
			this.texture = device.createTexture(() -> label,
					com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST | com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING,
					com.mojang.blaze3d.GpuFormat.RGBA8_UNORM, w, h, 1, levels.length);
			this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
					.getRepeat(com.mojang.blaze3d.textures.FilterMode.LINEAR, mipmapped);
			this.textureView = device.createTextureView(this.texture);
			var encoder = device.createCommandEncoder();
			for (int l = 0; l < levels.length; l++) encoder.writeToTexture(this.texture, levels[l], l, 0, 0, 0);
		}
	}
	*///?} elif >=1.21.11 {
	/*static final class Texture extends net.minecraft.client.renderer.texture.AbstractTexture {
		Texture(String label, NativeImage[] levels) {
			int w = levels[0].getWidth();
			int h = levels[0].getHeight();
			boolean mipmapped = levels.length > 1;
			var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
			this.texture = device.createTexture(() -> label,
					com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST | com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING,
					com.mojang.blaze3d.textures.TextureFormat.RGBA8, w, h, 1, levels.length);
			this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
					.getRepeat(com.mojang.blaze3d.textures.FilterMode.LINEAR, mipmapped);
			this.textureView = device.createTextureView(this.texture);
			var encoder = device.createCommandEncoder();
			for (int l = 0; l < levels.length; l++) encoder.writeToTexture(this.texture, levels[l], l, 0, 0, 0, w >> l, h >> l, 0, 0);
		}
	}
	*///?} elif >=1.21.6 {
	/*static final class Texture extends net.minecraft.client.renderer.texture.AbstractTexture {
		private final boolean mipmapped;

		Texture(String label, NativeImage[] levels) {
			int w = levels[0].getWidth();
			int h = levels[0].getHeight();
			this.mipmapped = levels.length > 1;
			var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
			this.texture = device.createTexture(() -> label,
					com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST | com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING,
					com.mojang.blaze3d.textures.TextureFormat.RGBA8, w, h, 1, levels.length);
			this.texture.setTextureFilter(com.mojang.blaze3d.textures.FilterMode.LINEAR, mipmapped);
			this.textureView = device.createTextureView(this.texture);
			var encoder = device.createCommandEncoder();
			for (int l = 0; l < levels.length; l++) encoder.writeToTexture(this.texture, levels[l], l, 0, 0, 0, w >> l, h >> l, 0, 0);
		}

		// Vanilla schaltet beim Zeichnen (entitySolid) die Mipmaps ab – hier bleiben sie an.
		@Override
		public void setUseMipmaps(boolean useMipmaps) {
			super.setUseMipmaps(mipmapped);
		}

		@Override
		public void setFilter(boolean blur, boolean mipmap) {
			super.setFilter(true, mipmapped);
		}
	}
	*///?} elif >=1.21.5 {
	/*static final class Texture extends net.minecraft.client.renderer.texture.AbstractTexture {
		private final boolean mipmapped;

		Texture(String label, NativeImage[] levels) {
			int w = levels[0].getWidth();
			int h = levels[0].getHeight();
			this.mipmapped = levels.length > 1;
			var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
			this.texture = device.createTexture(() -> label, com.mojang.blaze3d.textures.TextureFormat.RGBA8, w, h, levels.length);
			var encoder = device.createCommandEncoder();
			for (int l = 0; l < levels.length; l++) encoder.writeToTexture(this.texture, levels[l], l, 0, 0, w >> l, h >> l, 0, 0);
			this.texture.setTextureFilter(com.mojang.blaze3d.textures.FilterMode.LINEAR, mipmapped);
		}

		// Vanilla stellt beim Zeichnen (entitySolid) auf scharf ohne Mipmaps – hier bleibt es weich.
		@Override
		public void setFilter(boolean blur, boolean mipmap) {
			super.setFilter(true, mipmapped);
		}
	}
	*///?} else {
	static final class Texture extends net.minecraft.client.renderer.texture.DynamicTexture {
		private final boolean mipmapped;

		Texture(String label, NativeImage[] levels) {
			// Lädt Ebene 0 hoch und behält sie (wie jede DynamicTexture); danach Platz für alle Ebenen und hochladen.
			super(levels[0]);
			int w = levels[0].getWidth();
			int h = levels[0].getHeight();
			this.mipmapped = levels.length > 1;
			com.mojang.blaze3d.platform.TextureUtil.prepareImage(getId(), levels.length - 1, w, h);
			for (int l = 0; l < levels.length; l++) levels[l].upload(l, 0, 0, false);
			super.setFilter(true, mipmapped);
		}

		// Vanilla stellt beim Zeichnen (entitySolid) auf scharf ohne Mipmaps – hier bleibt es weich.
		@Override
		public void setFilter(boolean blur, boolean mipmap) {
			super.setFilter(true, mipmapped);
		}
	}
	//?}
}
