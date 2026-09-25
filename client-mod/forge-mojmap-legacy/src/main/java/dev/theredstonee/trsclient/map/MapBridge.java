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
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;

/**
 * Die Karte ({@code core.map}) sieht Minecraft nur durch diese Klasse: Welt/Dimension, Spielerposition,
 * Chunks ({@link MapSampler}), Spieler und Kreaturen, Biom, Tageszeit, Wegpunkte. Mojmap-Bäume teilen die Datei.
 */
public final class MapBridge implements MapPlatform {
	/** Sichtlinien-Prüfungen je Tick (Fair Play) – jede ist ein Strahl durch die Welt. */
	private static final int MAX_SIGHT_CHECKS = 48;

	private final MapSampler sampler = new MapSampler();

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	@Override
	public boolean inWorld() {
		return mc().level != null && mc().player != null;
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
		return x(mc().player);
	}

	@Override
	public double y() {
		return y(mc().player);
	}

	@Override
	public double z() {
		return z(mc().player);
	}

	// Position/Drehung je Version (Felder bis 1.14 bzw. 1.16, danach Getter).
	private static double x(Entity e) {
		//? if >=1.15 {
		return e.getX();
		//?} else
		/*return e.x;*/
	}

	private static double y(Entity e) {
		//? if >=1.15 {
		return e.getY();
		//?} else
		/*return e.y;*/
	}

	private static double z(Entity e) {
		//? if >=1.15 {
		return e.getZ();
		//?} else
		/*return e.z;*/
	}

	private static float yRot(Entity e) {
		//? if >=1.17 {
		/*return e.getYRot();
		*///?} else
		return e.yRot;
	}

	@Override
	public float yaw() {
		return mc().player == null ? 0f : yRot(mc().player);
	}

	@Override
	public ChunkReader reader() {
		return sampler;
	}

	@Override
	public int renderDistance() {
		//? if >=1.19 {
		/*return mc().options.renderDistance().get();
		*///?} else
		return mc().options.renderDistance;
	}

	@Override
	public int skyLight() {
		if (mc().level == null || mc().player == null) return 15;
		BlockPos head = new BlockPos((int) Math.floor(x(mc().player)), (int) Math.floor(y(mc().player) + 1.6),
				(int) Math.floor(z(mc().player)));
		return mc().level.getChunkSource().getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(head);
	}

	@Override
	public void entities(EntitySink sink, double radius, boolean lineOfSightOnly) {
		if (mc().level == null || mc().player == null) return;
		Player self = mc().player;
		double px = x(self), pz = z(self);
		double r2 = radius * radius;
		int checks = 0;
		for (Entity e : mc().level.entitiesForRendering()) {
			if (e == self || !(e instanceof LivingEntity) || e instanceof ArmorStand || !e.isAlive()) continue;
			double dx = x(e) - px, dz = z(e) - pz;
			if (dx * dx + dz * dz > r2) continue;
			if (lineOfSightOnly) {
				if (checks++ >= MAX_SIGHT_CHECKS || !canSee(self, e)) continue;
			}
			MapEntity m = sink.add();
			if (m == null) return;
			int type = e instanceof Player ? MapEntity.PLAYER : (e instanceof Enemy ? MapEntity.HOSTILE : MapEntity.PASSIVE);
			m.set(type, e.xo, e.zo, x(e), y(e), z(e), yRot(e));
			if (type == MapEntity.PLAYER) {
				m.uuid = e.getUUID();
				m.name = e.getName().getString();
				if (e instanceof AbstractClientPlayer) m.skin = skin((AbstractClientPlayer) e);
			}
		}
	}

	private static boolean canSee(Player self, Entity e) {
		//? if >=1.17 {
		/*return self.hasLineOfSight(e);
		*///?} else
		return self.canSee(e);
	}

	private static TextureRef skin(AbstractClientPlayer p) {
		try {
			//? if >=1.21.9 {
			/*return new TextureRef(p.getSkin().body().texturePath(), 64, 64);
			*///?} elif >=1.20.2 {
			/*return new TextureRef(p.getSkin().texture(), 64, 64);
			*///?} else
			return new TextureRef(p.getSkinTextureLocation(), 64, 64);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	@Override
	public String biome() {
		if (mc().player == null || mc().level == null) return "";
		BlockPos pos = new BlockPos((int) Math.floor(x(mc().player)), (int) Math.floor(y(mc().player)), (int) Math.floor(z(mc().player)));
		//? if >=1.21.11 {
		/*return mc().level.getBiome(pos).unwrapKey().map(k -> biomeText(k.identifier().getNamespace(), k.identifier().getPath())).orElse("");
		*///?} elif >=1.18.2 {
		/*return mc().level.getBiome(pos).unwrapKey().map(k -> biomeText(k.location().getNamespace(), k.location().getPath())).orElse("");
		*///?} elif >=1.16 {
		net.minecraft.resources.ResourceLocation id = mc().level.registryAccess()
				.registryOrThrow(net.minecraft.core.Registry.BIOME_REGISTRY).getKey(mc().level.getBiome(pos));
		return id == null ? "" : biomeText(id.getNamespace(), id.getPath());
		//?} else
		/*return mc().level.getBiome(pos).getName().getString();*/
	}

	private static String biomeText(String namespace, String path) {
		return net.minecraft.client.resources.language.I18n.get("biome." + namespace + "." + path);
	}

	@Override
	public long dayTime() {
		if (mc().level == null) return -1;
		//? if >=26.1 {
		/*return mc().level.getDefaultClockTime();
		*///?} else
		return mc().level.getDayTime();
	}

	@Override
	public double guiScale() {
		return Mc.window().getGuiScale();
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
		net.minecraft.client.multiplayer.ServerData data = mc().getCurrentServer();
		if (data == null || data.motd == null) return null;
		//? if >=1.16 {
		return data.motd.getString();
		//?} else
		/*return data.motd;*/
	}
}
