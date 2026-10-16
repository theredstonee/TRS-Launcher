package dev.theredstonee.trsclient.cinematic;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cinematic.CameraPose;
import dev.theredstonee.trsclient.core.cinematic.Cinematic;
import dev.theredstonee.trsclient.core.cinematic.Freecam;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;
import net.minecraft.block.Block;
import net.minecraft.block.BlockIce;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
//? if >=1.9 {
/*import net.minecraft.util.math.BlockPos;
*///?} else
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.MovementInput;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.nio.file.Path;

/**
 * Kamera-Fahrten und freie Kamera in Legacy-Forge 1.8.9–1.12.2 (Logik in {@code core.cinematic}). Ohne Mixins: Während
 * der Fahrt bzw. in der freien Kamera ist eine unsichtbare Hilfs-Entity (Rüstungsständer, nie in der Welt) die Kamera
 * ({@code setRenderViewEntity}); sie wird in jedem Bild vor dem Zeichnen auf die Lage gesetzt (Position und Blick ohne
 * Zwischenwerte, also unabhängig von Ticks). Die eigene Figur wird dabei gezeichnet und bleibt stehen (eigene leere
 * Bewegungs-Eingabe), Hand und HUD sind aus, das Sichtfeld kommt über {@code FOVModifier} ({@link #fov()}), die Maus über
 * {@code ZoomMouseHelper} ({@link #turn}), das Mausrad über {@link #scroll}.
 */
public final class LegacyCinematic implements Cinematic.Host {
	private static final LegacyCinematic HOST = new LegacyCinematic();

	private EntityArmorStand camera;
	/** Welt, für die {@link #camera} gebaut wurde. */
	private Object cameraWorld;
	private MovementInput savedInput;
	/** Leere Bewegungs-Eingabe, die die Figur stattdessen bekommt. */
	private MovementInput emptyInput;
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

	/** Fahrt oder freie Kamera: die Kamera zeigt nicht die Sicht der Figur. */
	public static boolean overridden() {
		Cinematic c = Cinematic.get();
		return c != null && c.overridden();
	}

	/**
	 * Mausbewegung (Werte wie für {@code Entity#setAngles}, Blick nach unten positiv): dreht die freie Kamera bzw.
	 * wirkt während einer Fahrt nicht.
	 *
	 * @return true = verbraucht
	 */
	public static boolean turn(double yaw, double pitch) {
		Cinematic c = Cinematic.get();
		return c != null && c.turn(yaw, pitch);
	}

	/** Mausrad in der freien Kamera: Geschwindigkeit. true = verbraucht. */
	public static boolean scroll(int wheel) {
		Cinematic c = Cinematic.get();
		if (c == null || Minecraft.getMinecraft().currentScreen != null) return false;
		try {
			return c.scroll(HOST, wheel);
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** Sichtfeld der Fahrt für dieses Bild oder −1 (keine Fahrt; die freie Kamera behält das normale Sichtfeld). */
	public static float fov() {
		Cinematic c = Cinematic.get();
		if (c == null) return -1;
		CameraPose p = c.frame(HOST);
		return p == null || p.fov <= 0 ? -1 : p.fov;
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
				while (TrsKeys.cinematicFreecam.isPressed()) {
					if (!c.playing() && (c.freecam() || (mc.currentScreen == null && Mc.player() != null))) c.toggleFreecam(HOST);
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
			else if (c.freecam()) blockInteraction(mc.gameSettings);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.warn("Kamera-Fahrt: Fehler ignoriert", e);
			try {
				c.stop(HOST);
			} catch (RuntimeException ignored) {
				// nichts mehr zu retten
			}
		}
	}

	/** Freie Kamera: Klicks verwerfen und Tasten loslassen – die Figur baut nichts ab und benutzt nichts. */
	private static void blockInteraction(GameSettings o) {
		KeyBinding[] keys = {o.keyBindAttack, o.keyBindUseItem, o.keyBindPickBlock};
		for (KeyBinding k : keys) {
			while (k.isPressed()) {
				// verwerfen
			}
			KeyBinding.setKeyBindState(k.getKeyCode(), false);
		}
	}

	/** Vor jedem Bild: Hilfs-Kamera auf die Lage der Fahrt bzw. der freien Kamera setzen. */
	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent event) {
		if (event.phase != TickEvent.Phase.START) return;
		Cinematic c = Cinematic.get();
		if (c == null || !c.overridden()) return;
		try {
			CameraPose p = c.frame(HOST);
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

	/** Keine Hand vor der Kamera, solange die Fahrt läuft bzw. die freie Kamera an ist. */
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onRenderHand(RenderHandEvent event) {
		if (overridden()) event.setCanceled(true);
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
			holdInput(player);
		} else {
			if (savedView != null) mc.gameSettings.thirdPersonView = savedView;
			savedView = null;
			if (player != null && savedInput != null && player.movementInput == emptyInput) player.movementInput = savedInput;
			savedInput = null;
			emptyInput = null;
			if (player != null) mc.setRenderViewEntity(player);
			camera = null;
			cameraWorld = null;
		}
	}

	@Override
	public void holdView() {
		// Neue Figur (Respawn) wieder festhalten; F5 würde die Hilfs-Kamera zurückziehen.
		holdInput(Mc.player());
		Minecraft.getMinecraft().gameSettings.thirdPersonView = 0;
	}

	private void holdInput(EntityPlayerSP player) {
		if (player == null || (emptyInput != null && player.movementInput == emptyInput)) return;
		savedInput = player.movementInput;
		emptyInput = new MovementInput();
		player.movementInput = emptyInput;
	}

	@Override
	public boolean solid(int x, int y, int z) {
		World world = Mc.world();
		if (world == null) return false;
		IBlockState state = world.getBlockState(new BlockPos(x, y, z));
		Block block = state.getBlock();
		// Laub (auch bei „Schnell“-Grafik undurchsichtig gezeichnet) und klares Eis bleiben durchlässig.
		if (block instanceof BlockLeaves || block instanceof BlockIce) return false;
		// Undurchsichtige volle Würfel (Stein, Erde, Holz, Erze …) = Wand; Glas, Scheiben, Türen, Pflanzen, Wasser nicht.
		//? if >=1.9 {
		/*return state.isOpaqueCube();
		*///?} else
		return block.isOpaqueCube();
	}

	@Override
	public void freecamInput(Freecam.Input in) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.currentScreen != null) return;
		GameSettings o = mc.gameSettings;
		in.forward = held(o.keyBindForward);
		in.back = held(o.keyBindBack);
		in.left = held(o.keyBindLeft);
		in.right = held(o.keyBindRight);
		in.up = held(o.keyBindJump);
		in.down = held(o.keyBindSneak);
		in.boost = held(o.keyBindSprint);
	}

	/** Echte Taste bzw. Maustaste (Codes unter 0: Maus, −100 + Knopf). */
	private static boolean held(KeyBinding key) {
		int code = key.getKeyCode();
		if (code > 0 && code < Keyboard.KEYBOARD_SIZE) return Keyboard.isKeyDown(code);
		if (code < 0) return Mouse.isButtonDown(code + 100);
		return false;
	}

	@Override
	public String serverAddress() {
		return Mc.serverAddress();
	}

	@Override
	public String serverMotd() {
		ServerData data = Minecraft.getMinecraft().getCurrentServerData();
		return data == null ? null : data.serverMOTD;
	}
}
