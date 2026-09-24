package dev.theredstonee.trsclient.core.online;

import dev.theredstonee.trsclient.core.cape.CapePhysics;
import dev.theredstonee.trsclient.core.cape.CapeSettings;
import dev.theredstonee.trsclient.core.cape.CapeTextures;
import dev.theredstonee.trsclient.core.cape.ClothMesh;
import dev.theredstonee.trsclient.core.cape.ClothSim;
import dev.theredstonee.trsclient.core.module.TrsModules;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Alles rund um TRS-Abzeichen, TRS-Umhänge und Umhang-Physik an einer Stelle – versionsunabhängig.
 * Jede Minecraft-Version liefert nur: Konto/Server ({@link OnlinePlatform}), das Hochladen von Texturen
 * ({@link CapeTextures.Backend}), einmal pro Tick die sichtbaren Spieler, und die Hooks zum Zeichnen.
 *
 * @param <T> Textur-Kennung der Version (z. B. ResourceLocation)
 */
public final class OnlineFeatures<T> {
	private final TrsModules modules;
	private final TrsOnline online;
	private final CapeTextures<T> textures;
	private final CapePhysics physics = new CapePhysics();
	private final ClothMesh mesh = new ClothMesh();
	private final dev.theredstonee.trsclient.core.emote.EmoteController emotes;
	private volatile Thread gameThread;
	/** Wiederverwendet: Umhang-Einstellungen je Tick (keine Allokation). */
	private final CapeSettings capeSettings = new CapeSettings();

	public OnlineFeatures(TrsModules modules, TrsOnline online, CapeTextures.Backend<T> backend) {
		this.modules = modules;
		this.online = online;
		this.textures = new CapeTextures<>(backend, online::loadCape);
		this.emotes = new dev.theredstonee.trsclient.core.emote.EmoteController(modules, online);
	}

	/** Standard-Aufbau: Konfiguration aus {@code configDir}, HTTP über HttpURLConnection. */
	public static <T> OnlineFeatures<T> create(TrsModules modules, Path configDir, OnlinePlatform platform,
			String modVersion, CapeTextures.Backend<T> backend) {
		return new OnlineFeatures<>(modules, TrsOnline.create(configDir, platform, modVersion), backend);
	}

	/**
	 * Einmal pro Client-Tick (Spiel-Thread): API-Verkehr anstoßen, Texturen aufräumen, Umhänge simulieren.
	 *
	 * @param visible UUIDs der Spieler in Tabliste und Sichtweite ({@code null} = unverändert, siehe {@link TrsOnline#tick})
	 * @param samples Zustand der Spieler für die Physik (darf leer sein)
	 */
	public void tick(Collection<UUID> visible, List<CapePhysics.Sample> samples) {
		gameThread = Thread.currentThread();
		long now = System.currentTimeMillis();
		// Nichts hiervon darf je das Spiel abstürzen lassen.
		try {
			online.tick(now, visible, modules.trsOnline.isEnabled());
			textures.cleanup(now);
		} catch (RuntimeException e) {
			online.reportError(e);
		}
		try {
			physics.tick(samples, modules.capePhysics.isEnabled(),
					modules.capeScope.get() == TrsModules.CapeScope.OWN, modules.capeSettings(capeSettings));
		} catch (RuntimeException e) {
			physics.clear();
			online.reportError(e);
		}
	}

	/**
	 * Textur des TRS-Umhangs eines Spielers (aktuelles Animationsbild) oder null (dann Vanilla/OptiFine).
	 * Nur im Spiel-/Render-Thread wirksam – aus anderen Threads immer null.
	 */
	public T capeTexture(UUID uuid) {
		if (Thread.currentThread() != gameThread) return null;
		if (!modules.trsOnline.isEnabled() || !modules.trsCapes.get()) return null;
		PlayerInfo info = online.info(uuid);
		if (info.cape == null) return null;
		try {
			return textures.texture(info.cape, System.currentTimeMillis());
		} catch (RuntimeException e) {
			online.reportError(e);
			return null;
		}
	}

	/** Zeigt der Spieler das TRS-Abzeichen? {@code tab}: Tabliste, sonst Namensschild. */
	public boolean badge(UUID uuid, boolean tab) {
		if (!modules.trsOnline.isEnabled()) return false;
		if (tab ? !modules.badgeTab.get() : !modules.badgeNametag.get()) return false;
		return online.info(uuid).badge;
	}

	/** Umhang-Simulation einer Entity (null = starr/Vanilla zeichnen). */
	public ClothSim sim(int entityId) {
		if (!modules.capePhysics.isEnabled()) return null;
		return physics.sim(entityId);
	}

	/**
	 * Zeichnet den simulierten Umhang im eingestellten Stil (glatt oder Stufen) – für alle Loader gleich.
	 */
	public void emitCape(ClothSim sim, float partial, ClothMesh.QuadSink sink) {
		mesh.emit(sim, partial, physics.blocky(), sink);
	}

	/** Gemeinsamer Mesh-Baukasten (nur Render-Thread). */
	public ClothMesh mesh() {
		return mesh;
	}

	/** Emotes (Rad, Animationen, Ereignisse anderer Spieler). */
	public dev.theredstonee.trsclient.core.emote.EmoteController emotes() {
		return emotes;
	}

	public TrsOnline online() {
		return online;
	}

	public CapePhysics physics() {
		return physics;
	}
}
