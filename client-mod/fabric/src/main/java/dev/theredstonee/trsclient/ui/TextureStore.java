package dev.theredstonee.trsclient.ui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import dev.theredstonee.trsclient.online.OnlineHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamische Texturen für {@code core.ui} ({@link Textures.Store}): ARGB-Pixel → NativeImage → DynamicTexture,
 * registriert als {@code trsclient:ui/<name>}. Gleicher Name und gleiche Größe = Pixel in place ersetzen.
 * Dazu Spiel-Texturen per Pfad und der Standard-Skin der Version. Nur Render-Thread.
 */
public final class TextureStore implements Textures.Store {
	public static final TextureStore INSTANCE = new TextureStore();

	private static final class Entry {
		final DynamicTexture texture;
		final TextureRef ref;

		Entry(DynamicTexture texture, TextureRef ref) {
			this.texture = texture;
			this.ref = ref;
		}
	}

	private final Map<String, Entry> entries = new HashMap<>();

	private TextureStore() {
	}

	@Override
	public TextureRef upload(String name, int width, int height, int[] argb) {
		if (!Textures.validName(name) || width <= 0 || height <= 0 || width > 4096 || height > 4096 || argb == null
				|| argb.length < width * height) return null;
		try {
			Entry old = entries.get(name);
			if (old != null && old.ref.width == width && old.ref.height == height && old.texture.getPixels() != null) {
				write(old.texture.getPixels(), width, height, argb);
				old.texture.upload();
				return old.ref;
			}
			if (old != null) release(old.ref);
			NativeImage img = new NativeImage(width, height, false);
			write(img, width, height, argb);
			//? if >=1.21.5 {
			/*DynamicTexture texture = new DynamicTexture(() -> "trsclient " + name, img);
			*///?} else
			DynamicTexture texture = new DynamicTexture(img);
			Object id = OnlineHooks.id("ui/" + name);
			//? if >=1.21.11 {
			/*Minecraft.getInstance().getTextureManager().register((net.minecraft.resources.Identifier) id, texture);
			*///?} else
			Minecraft.getInstance().getTextureManager().register((net.minecraft.resources.ResourceLocation) id, texture);
			TextureRef ref = new TextureRef(id, width, height);
			entries.put(name, new Entry(texture, ref));
			return ref;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static void write(NativeImage img, int width, int height, int[] argb) {
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int c = argb[y * width + x];
				//? if >=1.21.2 {
				/*img.setPixel(x, y, c);
				*///?} else
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
			//? if >=1.21.11 {
			/*Minecraft.getInstance().getTextureManager().release((net.minecraft.resources.Identifier) texture.id);
			*///?} else
			Minecraft.getInstance().getTextureManager().release((net.minecraft.resources.ResourceLocation) texture.id);
		} catch (RuntimeException e) {
			// schon weg
		}
	}

	@Override
	public TextureRef game(String location, int width, int height) {
		//? if >=1.21.11 {
		/*return new TextureRef(net.minecraft.resources.Identifier.parse(location), width, height);
		*///?} elif >=1.21 {
		return new TextureRef(net.minecraft.resources.ResourceLocation.parse(location), width, height);
		//?} else
		/*return new TextureRef(new net.minecraft.resources.ResourceLocation(location), width, height);*/
	}

	@Override
	public Textures.DefaultSkin defaultSkin(UUID uuid) {
		//? if >=1.21.9 {
		/*net.minecraft.world.entity.player.PlayerSkin skin = DefaultPlayerSkin.get(uuid);
		return new Textures.DefaultSkin(new TextureRef(skin.body().texturePath(), 64, 64),
				skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM);
		*///?} elif >=1.20.2 {
		net.minecraft.client.resources.PlayerSkin skin = DefaultPlayerSkin.get(uuid);
		return new Textures.DefaultSkin(new TextureRef(skin.texture(), 64, 64),
				skin.model() == net.minecraft.client.resources.PlayerSkin.Model.SLIM);
		//?} else {
		/*return new Textures.DefaultSkin(new TextureRef(DefaultPlayerSkin.getDefaultSkin(uuid), 64, 64),
				"slim".equals(DefaultPlayerSkin.getSkinModelName(uuid)));
		*///?}
	}
}
