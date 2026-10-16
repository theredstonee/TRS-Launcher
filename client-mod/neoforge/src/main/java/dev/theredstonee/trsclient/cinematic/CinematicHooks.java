package dev.theredstonee.trsclient.cinematic;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cinematic.CameraPose;
import dev.theredstonee.trsclient.core.cinematic.Cinematic;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;

import java.nio.file.Path;

/**
 * Kamera-Fahrten im Spiel (Logik in {@code core.cinematic}): Tasten, Welt-/Kamera-Zugriff und Partikel. Kamera und
 * Sichtfeld übernehmen {@code CameraMixin}/{@code FovMixin} über {@link #frame()}, die Maus {@code MouseHandlerMixin}
 * über {@link #playing()}.
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
	 * Fahrt, werden danach alle Tasten losgelassen – die Figur bleibt stehen, Eingaben wirken nicht.
	 */
	public static void tick(Minecraft mc) {
		Cinematic c = Cinematic.get();
		if (c == null) return;
		try {
			if (TrsKeys.cinematicPlay != null) {
				while (TrsKeys.cinematicPlay.consumeClick()) {
					if (c.playing() || Mc.screen() == null) c.togglePlay(HOST);
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
		} catch (RuntimeException e) {
			TrsClient.LOGGER.warn("Kamera-Fahrt: Fehler ignoriert", e);
			try {
				c.stop(HOST);
			} catch (RuntimeException ignored) {
				// nichts mehr zu retten
			}
		}
	}

	/** Kamera für dieses Bild (Position, Blick, Sichtfeld) oder null, wenn keine Fahrt läuft. */
	public static CameraPose frame() {
		Cinematic c = Cinematic.get();
		if (c == null) return null;
		try {
			return c.frame();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Läuft eine Fahrt? (Maus dreht dann nicht die Figur.) */
	public static boolean playing() {
		Cinematic c = Cinematic.get();
		return c != null && c.playing();
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
	}

	@Override
	public void particle(boolean keyframe, double x, double y, double z) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		mc.level.addParticle(keyframe ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.END_ROD, x, y, z, 0, 0, 0);
	}
}
