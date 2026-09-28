package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.map.ChunkReader;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.TexturePalette;
import net.minecraft.block.Block;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * Chunk-Zugriff der Karte ({@link ChunkReader}) für Forge 1.7.10: Blöcke mit Metadaten statt Zuständen,
 * Kartenfarbe über {@code Block#getMapColor(meta)}, Tönung über {@code Block#colorMultiplier}. Nur geladene Chunks.
 * Unsichtbare Blöcke wie die Barriere gibt es in 1.7.10 noch nicht.
 *
 * <p>Texturfarben: Symbol der Oberseite ({@code Block#getIcon(1, meta)}); die Pixel verwirft 1.7.10 nach dem
 * Zusammensetzen des Atlas – {@link TexturePalette} liest und mittelt die Datei im Hintergrund.
 */
public final class MapSampler implements ChunkReader {
	private World world;
	private Chunk chunk;
	private int baseX, baseZ;

	private static Chunk loaded(World w, int chunkX, int chunkZ) {
		if (!w.getChunkProvider().chunkExists(chunkX, chunkZ)) return null;
		Chunk c = w.getChunkFromChunkCoords(chunkX, chunkZ);
		return c == null || c.isEmpty() ? null : c;
	}

	@Override
	public boolean isLoaded(int chunkX, int chunkZ) {
		World w = Minecraft.getMinecraft().theWorld;
		return w != null && loaded(w, chunkX, chunkZ) != null;
	}

	@Override
	public boolean open(int chunkX, int chunkZ) {
		world = Minecraft.getMinecraft().theWorld;
		chunk = world == null ? null : loaded(world, chunkX, chunkZ);
		baseX = chunkX << 4;
		baseZ = chunkZ << 4;
		return chunk != null;
	}

	@Override
	public int minY() {
		return 0;
	}

	@Override
	public int top(int localX, int localZ) {
		// Die Höhenkarte zählt nur lichtundurchlässige Blöcke – Schnee, Blumen, Glas liegen darüber.
		return Math.min(255, chunk.getHeightValue(localX, localZ) + 2);
	}

	@Override
	public int block(int localX, int y, int localZ) {
		if (y < 0 || y > 255) return AIR;
		Block b = chunk.getBlock(localX, y, localZ);
		if (b == null || b.getMaterial() == Material.air) return AIR;
		MapColor color = b.getMapColor(chunk.getBlockMetadata(localX, y, localZ));
		int rgb = color == null ? 0 : color.colorValue & 0xFFFFFF;
		return b.isOpaqueCube() ? rgb | OPAQUE : rgb;
	}

	@Override
	public boolean sectionEmpty(int y) {
		net.minecraft.world.chunk.storage.ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
		int i = y >> 4;
		if (i < 0 || i >= sections.length) return true;
		return sections[i] == null || sections[i].isEmpty();
	}

	@Override
	public int tint(int localX, int y, int localZ) {
		if (y < 0 || y > 255) return -1;
		Block b = chunk.getBlock(localX, y, localZ);
		if (b == null) return -1;
		int c = b.colorMultiplier(world, baseX + localX, y, baseZ + localZ);
		// Ungetönte Blöcke melden Weiß.
		if (c == -1 || (c & 0xFFFFFF) == 0xFFFFFF) return -1;
		return c & 0xFFFFFF;
	}

	/** Je Block und Metadaten ein fester Schlüssel für die Texturfarben (1.7.10 hat keine Zustands-Objekte). */
	private final java.util.Map<Block, Object[]> stateKeys = new java.util.IdentityHashMap<Block, Object[]>();

	@Override
	public int textureColor(int localX, int y, int localZ) {
		MapEngine engine = MapEngine.get();
		if (engine == null || chunk == null || y < 0 || y > 255) return -1;
		TexturePalette palette = engine.palette();
		Block b = chunk.getBlock(localX, y, localZ);
		if (b == null) return -1;
		int meta = chunk.getBlockMetadata(localX, y, localZ) & 15;
		Object[] keys = stateKeys.get(b);
		if (keys == null) {
			keys = new Object[16];
			stateKeys.put(b, keys);
		}
		if (keys[meta] == null) keys[meta] = new Object();
		Object key = keys[meta];
		int c = palette.lookup(key);
		if (c != TexturePalette.UNRESOLVED) return c;
		String sprite = null;
		try {
			net.minecraft.util.IIcon icon = b.getIcon(1, meta);
			String name = icon == null ? null : icon.getIconName();
			if (name != null && !name.isEmpty()) {
				int colon = name.indexOf(':');
				sprite = colon < 0 ? "minecraft:blocks/" + name : name.substring(0, colon) + ":blocks/" + name.substring(colon + 1);
			}
		} catch (RuntimeException | LinkageError e) {
			sprite = null;
		}
		boolean tinted = tint(localX, y, localZ) != -1;
		return palette.resolve(key, sprite, tinted);
	}
}
