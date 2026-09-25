package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.map.ChunkReader;
import net.minecraft.block.Block;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * Chunk-Zugriff der Karte ({@link ChunkReader}) für Forge 1.7.10: Blöcke mit Metadaten statt Zuständen,
 * Kartenfarbe über {@code Block#getMapColor(meta)}, Tönung über {@code Block#colorMultiplier}. Nur geladene Chunks.
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
		return color == null ? 0 : color.colorValue & 0xFFFFFF;
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
}
