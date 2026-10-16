package dev.theredstonee.trsclient.cinematic;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.compat.Keys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cinematic.CameraPose;
import dev.theredstonee.trsclient.core.cinematic.Cinematic;
import dev.theredstonee.trsclient.core.cinematic.Freecam;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;

/**
 * Kamera-Fahrten und freie Kamera im Spiel (Logik in {@code core.cinematic}): Tasten, Welt-/Kamera-Zugriff, Blöcke
 * und Partikel. Kamera und Sichtfeld übernehmen {@code CameraMixin}/{@code FovMixin} über {@link #frame()}, die Maus
 * {@code MouseHandlerMixin} über {@link #turn}, das Mausrad {@code TrsClient#onScroll} über {@link #scroll}.
 *
 * <p>Freie Kamera: die Spielfigur bekommt eine leere Bewegungs-Eingabe (sie steht wie ein stehender Spieler), die
 * Kamera liest die echten Tasten; Angreifen/Benutzen wirken nicht.
 */
public final class CinematicHooks implements Cinematic.Host {
	private static final CinematicHooks HOST = new CinematicHooks();

	private CinematicHooks() {
	}

	/**
	 * Beim Start: Fahrten aus {@code config/trsclient-cinematic.json}. Die Tasten verbindet der Loader (wie die Notiz-Taste),
	 * weil sie je Loader zu verschiedenen Zeitpunkten registriert werden.
	 */
	public static void init(TrsModules modules, Path configDir) {
		Cinematic.init(modules.cinematic, configDir);
	}

	/**
	 * Am Anfang jedes Client-Ticks (vor der Bewegung des Spielers): Tasten, Ende/Weltwechsel, Vorschau. Läuft eine
	 * Fahrt, werden danach alle Tasten losgelassen – die Figur bleibt stehen, Eingaben wirken nicht. In der freien
	 * Kamera werden nur Angreifen/Benutzen/Auswählen verworfen (die Bewegungstasten braucht die Kamera).
	 */
	public static void tick(Minecraft mc) {
		Cinematic c = Cinematic.get();
		if (c == null) return;
		try {
			if (TrsKeys.cinematicPlay != null) {
				while (TrsKeys.cinematicPlay.consumeClick()) {
					if (c.playing() || Mc.screen() == null) c.togglePlay(HOST);
				}
				while (TrsKeys.cinematicFreecam.consumeClick()) {
					if (!c.playing() && (c.freecam() || (Mc.screen() == null && mc.player != null))) c.toggleFreecam(HOST);
				}
				boolean free = !c.playing() && Mc.screen() == null && mc.player != null;
				while (TrsKeys.cinematicAdd.consumeClick()) {
					if (free) c.addKeyframe(HOST);
				}
				while (TrsKeys.cinematicRemove.consumeClick()) {
					if (free) c.removeLast(HOST);
				}
				while (TrsKeys.cinematicClear.consumeClick()) {
					if (free) c.clear(HOST);
				}
			}
			c.tick(HOST, Mc.screen() != null);
			if (c.playing()) KeyMapping.releaseAll();
			else if (c.freecam()) blockInteraction(mc);
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
	private static void blockInteraction(Minecraft mc) {
		KeyMapping[] keys = {mc.options.keyAttack, mc.options.keyUse, mc.options.keyPickItem};
		for (KeyMapping k : keys) {
			while (k.consumeClick()) {
				// verwerfen
			}
			//? if >=1.15 {
			k.setDown(false);
			//?} else
			/*((dev.theredstonee.trsclient.mixin.KeyMappingAccessor) k).trsclient$setDown(false);*/
		}
	}

	/** Kamera für dieses Bild (Position, Blick, Sichtfeld; Sichtfeld 0 = unverändert) oder null. */
	public static CameraPose frame() {
		Cinematic c = Cinematic.get();
		if (c == null) return null;
		try {
			return c.frame(HOST);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Läuft eine Fahrt? */
	public static boolean playing() {
		Cinematic c = Cinematic.get();
		return c != null && c.playing();
	}

	/**
	 * Mausbewegung (Werte für {@code LocalPlayer#turn}): dreht die freie Kamera bzw. wirkt während einer Fahrt nicht.
	 *
	 * @return true = verbraucht (die Figur dreht sich nicht)
	 */
	public static boolean turn(double yaw, double pitch) {
		Cinematic c = Cinematic.get();
		return c != null && c.turn(yaw, pitch);
	}

	/** Mausrad in der freien Kamera: Geschwindigkeit. true = verbraucht. */
	public static boolean scroll(double amount) {
		Cinematic c = Cinematic.get();
		if (c == null || Mc.screen() != null) return false;
		try {
			return c.scroll(HOST, amount);
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** Für Selbsttests: die Steuerung mit der Spiel-Anbindung. */
	public static Cinematic.Host host() {
		return HOST;
	}

	// --- Cinematic.Host ---

	@Override
	public String worldKey() {
		if (Minecraft.getInstance().level == null) return null;
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
		if (Minecraft.getInstance().level == null) return false;
		out.set(Mc.cameraX(), Mc.cameraY(), Mc.cameraZ(), Mc.cameraYaw(), Mc.cameraPitch(), (float) TrsClient.get().worldFov());
		return true;
	}

	@Override
	public void actionBar(String text) {
		Mc.actionBar(dev.theredstonee.trsclient.online.OnlineHooks.text(text));
	}

	@Override
	public boolean hudHidden() {
		return Mc.hudHidden();
	}

	@Override
	public void setHudHidden(boolean hidden) {
		Minecraft mc = Minecraft.getInstance();
		//? if >=26.2 {
		/*if (mc.gui.hud.isHidden() != hidden) mc.gui.hud.toggle();
		*///?} else
		mc.options.hideGui = hidden;
	}

	/** Kamera-Ansicht vor der Fahrt (wird danach wiederhergestellt). */
	private Object viewBefore;
	/** Bewegungs-Eingabe der Figur vor dem Lösen und die leere, die sie stattdessen bekommt. */
	private Object inputBefore;
	private Object emptyInput;

	@Override
	public void detachView(boolean detached) {
		Minecraft mc = Minecraft.getInstance();
		//? if >=1.16 {
		if (detached) {
			viewBefore = mc.options.getCameraType();
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
		} else if (viewBefore != null) {
			mc.options.setCameraType((net.minecraft.client.CameraType) viewBefore);
			viewBefore = null;
		}
		//?} else {
		/*if (detached) {
			viewBefore = mc.options.thirdPersonView;
			mc.options.thirdPersonView = 1;
		} else if (viewBefore != null) {
			mc.options.thirdPersonView = (Integer) viewBefore;
			viewBefore = null;
		}
		*///?}
		if (detached) {
			holdInput(mc);
		} else {
			if (mc.player != null && inputBefore != null && mc.player.input == emptyInput) {
				//? if >=1.21.2 {
				/*mc.player.input = (net.minecraft.client.player.ClientInput) inputBefore;
				*///?} else
				mc.player.input = (net.minecraft.client.player.Input) inputBefore;
			}
			inputBefore = null;
			emptyInput = null;
		}
	}

	@Override
	public void holdView() {
		Minecraft mc = Minecraft.getInstance();
		holdInput(mc);
		// F5 in der ersten Person zeigte Hand und Innenseite der Figur – zurück in die gelöste Ansicht.
		//? if >=1.16 {
		if (mc.options.getCameraType().isFirstPerson()) mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
		//?} else
		/*if (mc.options.thirdPersonView == 0) mc.options.thirdPersonView = 1;*/
	}

	/** Die (ggf. neue, z. B. nach Respawn) Figur bekommt die leere Eingabe: sie steht. */
	private void holdInput(Minecraft mc) {
		if (mc.player == null || (emptyInput != null && mc.player.input == emptyInput)) return;
		inputBefore = mc.player.input;
		//? if >=1.21.2 {
		/*net.minecraft.client.player.ClientInput empty = new net.minecraft.client.player.ClientInput();
		*///?} else
		net.minecraft.client.player.Input empty = new net.minecraft.client.player.Input();
		emptyInput = empty;
		mc.player.input = empty;
	}

	@Override
	public boolean solid(int x, int y, int z) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return false;
		BlockPos pos = new BlockPos(x, y, z);
		BlockState state = mc.level.getBlockState(pos);
		// Durchsichtiges (Glas, Scheiben, Gitter, Türen, Pflanzen, Flüssigkeiten …) lässt keine Sicht verbergen.
		if (!state.canOcclude()) return false;
		Block block = state.getBlock();
		if (block instanceof LeavesBlock || block == Blocks.ICE) return false;
		// Volle Würfel (Kollision oder Umriss, z. B. Seelensand): Stein, Erde, Holz, Erze … = Wand.
		return Block.isShapeFullBlock(state.getCollisionShape(mc.level, pos)) || Block.isShapeFullBlock(state.getShape(mc.level, pos));
	}

	@Override
	public void freecamInput(Freecam.Input in) {
		Minecraft mc = Minecraft.getInstance();
		if (Mc.screen() != null) return;
		in.forward = held(mc.options.keyUp);
		in.back = held(mc.options.keyDown);
		in.left = held(mc.options.keyLeft);
		in.right = held(mc.options.keyRight);
		in.up = held(mc.options.keyJump);
		in.down = held(mc.options.keyShift);
		in.boost = held(mc.options.keySprint);
	}

	/** Echte Taste (Umschalt-Sprinten/-Schleichen halten die Belegung sonst dauerhaft gedrückt). */
	private static boolean held(KeyMapping key) {
		String name = key.saveString();
		if (name != null && name.startsWith("key.keyboard.")) return !name.equals("key.keyboard.unknown") && Keys.isDown(name);
		return key.isDown();
	}

	@Override
	public String serverAddress() {
		return Mc.serverAddress();
	}

	@Override
	public String serverMotd() {
		net.minecraft.client.multiplayer.ServerData data = Minecraft.getInstance().getCurrentServer();
		if (data == null || data.motd == null) return null;
		//? if >=1.16 {
		return data.motd.getString();
		//?} else
		/*return data.motd;*/
	}

	@Override
	public void particle(boolean keyframe, double x, double y, double z) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		mc.level.addParticle(keyframe ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.END_ROD, x, y, z, 0, 0, 0);
	}
}
