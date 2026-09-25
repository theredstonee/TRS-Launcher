package dev.theredstonee.trsclient.core.ui;

/**
 * Eine Textur, die der {@link Canvas} zeichnen kann: die Kennung der jeweiligen Minecraft-Version
 * (ResourceLocation/Identifier) und ihre Größe in Texeln. Die Größe braucht das Zeichnen, um
 * Texel-Koordinaten in Anteile umzurechnen (Umhänge gibt es z. B. in 64×32 bis 512×256).
 *
 * <p>Entsteht über {@link Textures.Store#upload} (eigene Pixel) oder {@link Textures.Store#game}
 * (Textur aus den Spiel-Ressourcen). Unveränderlich.
 */
public final class TextureRef {
	/** Kennung der Version (ResourceLocation, Identifier …) – für {@code core} undurchsichtig. */
	public final Object id;
	public final int width;
	public final int height;

	public TextureRef(Object id, int width, int height) {
		if (id == null) throw new IllegalArgumentException("id");
		if (width <= 0 || height <= 0) throw new IllegalArgumentException("Größe " + width + "×" + height);
		this.id = id;
		this.width = width;
		this.height = height;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof TextureRef)) return false;
		TextureRef t = (TextureRef) o;
		return width == t.width && height == t.height && id.equals(t.id);
	}

	@Override
	public int hashCode() {
		return (id.hashCode() * 31 + width) * 31 + height;
	}

	@Override
	public String toString() {
		return id + " " + width + "×" + height;
	}
}
