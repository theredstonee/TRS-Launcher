package dev.theredstonee.trsclient.cinematic;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cinematic.CameraPose;
import dev.theredstonee.trsclient.core.cinematic.Cinematic;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.MovementInput;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.nio.file.Path;

/**
 * Kamera-Fahrten in Legacy-Forge 1.8.9–1.12.2 (Logik in {@code core.cinematic}). Ohne Mixins: Während der Fahrt ist eine
 * unsichtbare Hilfs-Entity (Rüstungsständer, nie in der Welt) die Kamera ({@code setRenderViewEntity}); sie wird in jedem
 * Bild vor dem Zeichnen auf die Lage der Fahrt gesetzt (Position und Blick ohne Zwischenwerte, also unabhängig von
 * Ticks). Die Figur bleibt stehen (eigene leere Bewegungs-Eingabe), Hand und HUD sind aus, das Sichtfeld kommt über
 * {@code FOVModifier} ({@link #fov()}), die Maus über {@code ZoomMouseHelper} ({@link #playing()}).
 */
public final class LegacyCinematic implements Cinematic.Host {
	private static final LegacyCinematic HOST = new LegacyCinematic();

	private EntityArmorStand camera;
	/** Welt, für die {@link #camera} gebaut wurde. */
	private Object cameraWorld;
	private MovementInput savedInput;
	private Integer savedView;

	private LegacyCinematic() {
	}

	/** Beim Start: Fahrten aus {@code config/trsclient-cinematic.json}. */
	public static void init(TrsModules modules, Path configDir) {
		Cinematic.init(modules.cinematic, configDir);
	}

	public static LegacyCinematic get() {
		return HOST;
	}

	/** Für Selbsttests: die Steuerung mit der Spiel-Anbindung. */
	public static Cinematic.Host host() {
		return HOST;
	}

	/** Läuft eine Fahrt? */
	public static boolean playing() {
		Cinematic c = Cinematic.get();
		return c != null && c.playing();
	}

	/** Sichtfeld der Fahrt für dieses Bild oder −1 (keine Fahrt). */
	public static float fov() {
		Cinematic c = Cinematic.get();
		if (c == null) return -1;
		CameraPose p = c.frame();
		return p == null ? -1 : p.fov;
	}

	@SubscribeEvent(priority = EventPriority.HIGH)
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.START) return;
		Cinematic c = Cinematic.get();
		if (c == null) return;
		try {
			Minecraft mc = Minecraft.getMinecraft();
			if (TrsKeys.cinematicPlay != null) {
				while (TrsKeys.cinematicPlay.isPressed()) {
					if (c.playing() || mc.currentScreen == null) c.togglePlay(HOST);
				}
				boolean free = !c.playing() && mc.currentScreen == null && Mc.player() != null;
				while (TrsKeys.cinematicAdd.isPressed()) {
					if (free) c.addKeyframe(HOST);
				}
				while (TrsKeys.cinematicRemove.isPressed()) {
					if (free) c.removeLast(HOST);
				}
				while (TrsKeys.cinematicClear.isPressed()) {
					if (free) c.clear(HOST);
				}
			}
			c.tick(HOST, mc.currentScreen != null);
			if (c.playing()) KeyBinding.unPressAllKeys();
		} catch (RuntimeException e) {
			TrsClient.LOGGER.warn("Kamera-Fahrt: Fehler ignoriert", e);
			try {
				c.stop(HOST);
			} catch (RuntimeException ignored) {
				// nichts mehr zu retten
			}
		}
	}

	/** Vor jedem Bild: Hilfs-Kamera auf die Lage der Fahrt setzen. */
	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent event) {
		if (event.phase != TickEvent.Phase.START) return;
		Cinematic c = Cinematic.get();
		if (c == null || !c.playing()) return;
		try {
			CameraPose p = c.frame();
			Minecraft mc = Minecraft.getMinecraft();
			if (p == null || Mc.world() == null) return;
			if (camera == null || cameraWorld != Mc.world()) {
				camera = new CameraEntity(Mc.world());
				cameraWorld = Mc.world();
			}
			double y = p.y - camera.getEyeHeight();
			camera.posX = camera.prevPosX = camera.lastTickPosX = p.x;
			camera.posY = camera.prevPosY = camera.lastTickPosY = y;
			camera.posZ = camera.prevPosZ = camera.lastTickPosZ = p.z;
			camera.rotationYaw = camera.prevRotationYaw = p.yaw;
			camera.rotationPitch = camera.prevRotationPitch = p.pitch;
			camera.rotationYawHead = camera.prevRotationYawHead = p.yaw;
			if (mc.getRenderViewEntity() != camera) mc.setRenderViewEntity(camera);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.warn("Kamera-Fahrt: Kamera nicht gesetzt", e);
		}
	}

	/** Keine Hand vor der Kamera, solange die Fahrt läuft. */
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onRenderHand(RenderHandEvent event) {
		if (playing()) event.setCanceled(true);
	}

	/** Unsichtbare Hilfs-Entity als Kamera (nie in der Welt, nie gezeichnet). */
	static final class CameraEntity extends EntityArmorStand {
		CameraEntity(net.minecraft.world.World world) {
			super(world);
			setInvisible(true);
		}

		@Override
		public float getEyeHeight() {
			return 1.62F;
		}
	}

	// --- Cinematic.Host ---

	@Override
	public String worldKey() {
		if (Mc.world() == null) return null;
		String address = Mc.serverAddress();
		if (address != null) return WaypointStore.serverKey(address);
		String level = Mc.levelName();
		return level == null ? null : WaypointStore.singleplayerKey(level);
	}

	@Override
	public String dimension() {
		return Mc.dimensionId();
	}

	@Override
	public boolean camera(CameraPose out) {
		Entity e = Mc.viewEntity();
		if (e == null || Mc.world() == null) return false;
		out.set(e.posX, e.posY + e.getEyeHeight(), e.posZ, e.rotationYaw, e.rotationPitch, (float) TrsClient.get().worldFov());
		return true;
	}

	@Override
	public void actionBar(String text) {
		Mc.actionBar(text);
	}

	@Override
	public boolean hudHidden() {
		return Mc.hudHidden();
	}

	@Override
	public void setHudHidden(boolean hidden) {
		Minecraft.getMinecraft().gameSettings.hideGUI = hidden;
	}

	@Override
	public void particle(boolean keyframe, double x, double y, double z) {
		if (Mc.world() == null) return;
		Mc.world().spawnParticle(keyframe ? EnumParticleTypes.VILLAGER_HAPPY : EnumParticleTypes.REDSTONE, x, y, z, 0, 0, 0);
	}

	@Override
	public void detachView(boolean detached) {
		Minecraft mc = Minecraft.getMinecraft();
		EntityPlayerSP player = Mc.player();
		if (detached) {
			// Erste Person: die Hilfs-Kamera sitzt genau auf der Fahrt (dritte Person zöge sie 4 Blöcke zurück).
			savedView = mc.gameSettings.thirdPersonView;
			mc.gameSettings.thirdPersonView = 0;
			if (player != null) {
				savedInput = player.movementInput;
				player.movementInput = new MovementInput();
			}
		} else {
			if (savedView != null) mc.gameSettings.thirdPersonView = savedView;
			savedView = null;
			if (player != null && savedInput != null) player.movementInput = savedInput;
			savedInput = null;
			if (player != null) mc.setRenderViewEntity(player);
			camera = null;
			cameraWorld = null;
		}
	}
}
