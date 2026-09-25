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
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Die Karte ({@code core.map}) sieht Minecraft 1.7.10 nur durch diese Klasse. Diese Version hat kein eigenes
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
		return Minecraft.getMinecraft();
	}

	/** Je Tick: Todespunkt setzen (letzter Tod). */
	public void tickDeath() {
		EntityClientPlayerMP p = mc().thePlayer;
		if (p == null || mc().theWorld == null) {
			wasDead = false;
			return;
		}
		boolean dead = p.getHealth() <= 0;
		if (dead && !wasDead && modules.waypointDeath.get() && (modules.minimap.isEnabled() || modules.worldMap.isEnabled())) {
			String key = worldKey();
			if (!key.isEmpty()) {
				store.setDeath(key, (int) Math.floor(p.posX), (int) Math.floor(p.boundingBox.minY), (int) Math.floor(p.posZ),
						dimension(), 0xE0281E);
				waypointsChanged();
				if (mc().ingameGUI != null) mc().ingameGUI.func_110326_a(I18n.tr("waypoint.deathSet"), false);
			}
		}
		wasDead = dead;
	}

	@Override
	public boolean inWorld() {
		return mc().theWorld != null && mc().thePlayer != null;
	}

	@Override
	public String worldKey() {
		if (mc().theWorld == null) return "";
		if (mc().getIntegratedServer() != null) return WaypointStore.singleplayerKey(mc().getIntegratedServer().getFolderName());
		ServerData data = mc().func_147104_D();
		return data == null ? "" : WaypointStore.serverKey(data.serverIP);
	}

	@Override
	public String dimension() {
		World w = mc().theWorld;
		return w == null ? "" : "dim" + w.provider.dimensionId;
	}

	/** In 1.7.10 ist posY des eigenen Spielers die Augenhöhe – Füße über die Hitbox. */
	@Override
	public double x() {
		return mc().thePlayer.posX;
	}

	@Override
	public double y() {
		return mc().thePlayer.boundingBox.minY;
	}

	@Override
	public double z() {
		return mc().thePlayer.posZ;
	}

	@Override
	public float yaw() {
		return mc().thePlayer == null ? 0f : mc().thePlayer.rotationYaw;
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
		World w = mc().theWorld;
		EntityClientPlayerMP p = mc().thePlayer;
		if (w == null || p == null) return 15;
		return w.getSavedLightValue(EnumSkyBlock.Sky, (int) Math.floor(p.posX), (int) Math.floor(p.boundingBox.minY + 1.6),
				(int) Math.floor(p.posZ));
	}

	@Override
	public void entities(EntitySink sink, double radius, boolean lineOfSightOnly) {
		World w = mc().theWorld;
		EntityClientPlayerMP self = mc().thePlayer;
		if (w == null || self == null) return;
		double r2 = radius * radius;
		int checks = 0;
		List<?> list = w.loadedEntityList;
		for (int i = 0, n = list.size(); i < n; i++) {
			Object o = list.get(i);
			if (!(o instanceof EntityLivingBase)) continue;
			Entity e = (Entity) o;
			if (e == self || e.isDead) continue;
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
				m.name = e.getCommandSenderName();
				if (e instanceof AbstractClientPlayer) {
					try {
						// 1.7.10-Skins sind 64×32 – das Gesicht liegt trotzdem bei (8, 8).
						m.skin = new TextureRef(((AbstractClientPlayer) e).getLocationSkin(), 64, 32);
					} catch (RuntimeException ex) {
						m.skin = null;
					}
				}
			}
		}
	}

	@Override
	public String biome() {
		World w = mc().theWorld;
		EntityClientPlayerMP p = mc().thePlayer;
		if (w == null || p == null) return "";
		return w.getBiomeGenForCoords((int) Math.floor(p.posX), (int) Math.floor(p.posZ)).biomeName;
	}

	@Override
	public long dayTime() {
		return mc().theWorld == null ? -1 : mc().theWorld.getWorldTime();
	}

	@Override
	public double guiScale() {
		return new ScaledResolution(mc(), mc().displayWidth, mc().displayHeight).getScaleFactor();
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
		ServerData data = mc().func_147104_D();
		return data == null ? null : data.serverMOTD;
	}
}
