package dev.theredstonee.trsclient.map;

import dev.theredstonee.trsclient.compat.MapSampler;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.map.ChunkReader;
import dev.theredstonee.trsclient.core.map.MapEntity;
import dev.theredstonee.trsclient.core.map.MapPlatform;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumLightType;
import net.minecraft.world.dimension.DimensionType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Die Karte ({@code core.map}) sieht Minecraft 1.13.2 nur durch diese Klasse. Diese Version hat kein eigenes
 * Wegpunkte-Modul – die Karte führt deshalb selbst die Wegpunkte (dieselbe Datei wie die anderen Versionen,
 * {@code config/trsclient-waypoints.json}) und den Todespunkt.
 */
public final class MapBridge implements MapPlatform {
	private static final int MAX_SIGHT_CHECKS = 48;
	private final MapSampler sampler = new MapSampler();
	private final WaypointStore store;
	private final TrsModules modules;
	private boolean wasDead;

	public MapBridge(TrsModules modules, Path waypointFile) {
		this.modules = modules;
		this.store = new WaypointStore(waypointFile);
		this.store.load();
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** Je Tick: Todespunkt setzen (letzter Tod). */
	public void tickDeath() {
		EntityPlayerSP p = mc().player;
		if (p == null || mc().world == null) {
			wasDead = false;
			return;
		}
		boolean dead = p.getHealth() <= 0;
		if (dead && !wasDead && modules.waypointDeath.get() && (modules.minimap.isEnabled() || modules.worldMap.isEnabled())) {
			String key = worldKey();
			if (!key.isEmpty()) {
				store.setDeath(key, (int) Math.floor(p.posX), (int) Math.floor(p.posY), (int) Math.floor(p.posZ), dimension(), 0xE0281E);
				waypointsChanged();
				if (mc().ingameGUI != null) mc().ingameGUI.setOverlayMessage(I18n.tr("waypoint.deathSet"), false);
			}
		}
		wasDead = dead;
	}

	@Override
	public boolean inWorld() {
		return mc().world != null && mc().player != null;
	}

	@Override
	public String worldKey() {
		if (mc().world == null) return "";
		if (mc().getIntegratedServer() != null) return WaypointStore.singleplayerKey(mc().getIntegratedServer().getWorldName());
		ServerData data = mc().getCurrentServerData();
		return data == null ? "" : WaypointStore.serverKey(data.serverIP);
	}

	@Override
	public String dimension() {
		WorldClient w = mc().world;
		if (w == null) return "";
		ResourceLocation id = DimensionType.getKey(w.dimension.getType());
		return id == null ? "dim" + w.dimension.getType().getId() : id.toString();
	}

	@Override
	public double x() {
		return mc().player.posX;
	}

	@Override
	public double y() {
		return mc().player.posY;
	}

	@Override
	public double z() {
		return mc().player.posZ;
	}

	@Override
	public float yaw() {
		return mc().player == null ? 0f : mc().player.rotationYaw;
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
		WorldClient w = mc().world;
		EntityPlayerSP p = mc().player;
		if (w == null || p == null) return 15;
		return w.getLightFor(EnumLightType.SKY, new BlockPos((int) Math.floor(p.posX), (int) Math.floor(p.posY + 1.6), (int) Math.floor(p.posZ)));
	}

	@Override
	public void entities(EntitySink sink, double radius, boolean lineOfSightOnly) {
		WorldClient w = mc().world;
		EntityPlayerSP self = mc().player;
		if (w == null || self == null) return;
		double r2 = radius * radius;
		int checks = 0;
		List<Entity> list = w.loadedEntityList;
		for (int i = 0, n = list.size(); i < n; i++) {
			Entity e = list.get(i);
			if (e == self || !(e instanceof EntityLivingBase) || e instanceof EntityArmorStand || !e.isAlive()) continue;
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
				m.name = e.getName().getString();
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
		WorldClient w = mc().world;
		EntityPlayerSP p = mc().player;
		if (w == null || p == null) return "";
		return w.getBiome(new BlockPos(p)).getDisplayName().getString();
	}

	@Override
	public long dayTime() {
		return mc().world == null ? -1 : mc().world.getDayTime();
	}

	@Override
	public double guiScale() {
		return mc().mainWindow.getGuiScaleFactor();
	}

	@Override
	public WaypointStore waypoints() {
		return store;
	}

	@Override
	public String waypointWorldKey() {
		return worldKey();
	}

	@Override
	public void waypointsChanged() {
		store.touch();
		try {
			store.saveIfDirty();
		} catch (IOException e) {
			// nächster Versuch beim nächsten Ändern
		}
	}

	@Override
	public String serverMotd() {
		ServerData data = mc().getCurrentServerData();
		return data == null ? null : data.serverMOTD;
	}
}
