package dev.theredstonee.trsclient.map;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.MapSampler;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.map.ChunkReader;
import dev.theredstonee.trsclient.core.map.MapEntity;
import dev.theredstonee.trsclient.core.map.MapPlatform;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
//? if >=1.9 {
/*import net.minecraft.util.math.BlockPos;
*///?} else
import net.minecraft.util.BlockPos;

import java.util.List;

/**
 * Die Karte ({@code core.map}) sieht Minecraft 1.8.9–1.12.2 nur durch diese Klasse: Welt/Dimension,
 * Spielerposition, Chunks ({@link MapSampler}), Spieler und Kreaturen, Biom, Tageszeit, Wegpunkte.
 */
public final class MapBridge implements MapPlatform {
	private static final int MAX_SIGHT_CHECKS = 48;
	private final MapSampler sampler = new MapSampler();

	private static Minecraft mc() {
		return Minecraft.getMinecraft();
	}

	@Override
	public boolean inWorld() {
		return Mc.world() != null && Mc.player() != null;
	}

	@Override
	public String worldKey() {
		TrsClient client = TrsClient.get();
		return client == null || client.waypoints() == null ? "" : client.waypoints().worldKey();
	}

	@Override
	public String dimension() {
		return Mc.dimensionId();
	}

	@Override
	public double x() {
		return Mc.player().posX;
	}

	@Override
	public double y() {
		return Mc.player().posY;
	}

	@Override
	public double z() {
		return Mc.player().posZ;
	}

	@Override
	public float yaw() {
		return Mc.player() == null ? 0f : Mc.player().rotationYaw;
	}

	@Override
	public ChunkReader reader() {
		return sampler;
	}

	@Override
	public int renderDistance() {
		return mc().gameSettings.renderDistanceChunks;
	}

	@Override
	public int skyLight() {
		World w = Mc.world();
		EntityPlayer p = Mc.player();
		if (w == null || p == null) return 15;
		BlockPos head = new BlockPos((int) Math.floor(p.posX), (int) Math.floor(p.posY + 1.6), (int) Math.floor(p.posZ));
		return w.getLightFor(EnumSkyBlock.SKY, head);
	}

	@Override
	public void entities(EntitySink sink, double radius, boolean lineOfSightOnly) {
		World w = Mc.world();
		EntityPlayer self = Mc.player();
		if (w == null || self == null) return;
		double r2 = radius * radius;
		int checks = 0;
		List<Entity> list = w.loadedEntityList;
		for (int i = 0, n = list.size(); i < n; i++) {
			Entity e = list.get(i);
			if (e == self || !(e instanceof EntityLivingBase) || e instanceof EntityArmorStand || e.isDead) continue;
			double dx = e.posX - self.posX, dz = e.posZ - self.posZ;
			if (dx * dx + dz * dz > r2) continue;
			if (lineOfSightOnly) {
				if (checks++ >= MAX_SIGHT_CHECKS || !self.canEntityBeSeen(e)) continue;
			}
			MapEntity m = sink.add();
			if (m == null) return;
			int type = e instanceof EntityPlayer ? MapEntity.PLAYER : (e instanceof IMob ? MapEntity.HOSTILE : MapEntity.PASSIVE);
			m.set(type, e.prevPosX, e.prevPosZ, e.posX, e.posY, e.posZ, e.rotationYaw);
			if (type == MapEntity.PLAYER) {
				m.uuid = e.getUniqueID();
				m.name = e.getName();
				if (e instanceof AbstractClientPlayer) {
					try {
						m.skin = new TextureRef(((AbstractClientPlayer) e).getLocationSkin(), 64, 64);
					} catch (RuntimeException ex) {
						m.skin = null;
					}
				}
			}
		}
	}

	@Override
	public String biome() {
		World w = Mc.world();
		EntityPlayer p = Mc.player();
		if (w == null || p == null) return "";
		return Mc.biomeName(w, Mc.blockPos(p));
	}

	@Override
	public long dayTime() {
		World w = Mc.world();
		return w == null ? -1 : w.getWorldTime();
	}

	@Override
	public double guiScale() {
		return Mc.scaledResolution().getScaleFactor();
	}

	@Override
	public WaypointStore waypoints() {
		TrsClient client = TrsClient.get();
		return client == null || client.waypoints() == null ? null : client.waypoints().store();
	}

	@Override
	public String waypointWorldKey() {
		return worldKey();
	}

	@Override
	public void waypointsChanged() {
		TrsClient client = TrsClient.get();
		if (client != null && client.waypoints() != null) client.waypoints().save();
	}

	@Override
	public String serverMotd() {
		ServerData data = mc().getCurrentServerData();
		return data == null ? null : data.serverMOTD;
	}
}
