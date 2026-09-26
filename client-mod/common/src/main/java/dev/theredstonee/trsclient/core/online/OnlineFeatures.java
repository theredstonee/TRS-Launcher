package dev.theredstonee.trsclient.core.online;

import dev.theredstonee.trsclient.core.cape.CapePhysics;
import dev.theredstonee.trsclient.core.cape.CapeSettings;
import dev.theredstonee.trsclient.core.cape.CapeTextures;
import dev.theredstonee.trsclient.core.cape.ClothMesh;
import dev.theredstonee.trsclient.core.cape.ClothSim;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.skin.LocalSkin;
import dev.theredstonee.trsclient.core.skin.PlayerLook;
import dev.theredstonee.trsclient.core.ui.TextureRef;

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
	/** Kopf-Kosmetik (Quietscheente): Rig-Zustände je Träger und Mesh-Baukasten (nur Render-Thread). */
	private final dev.theredstonee.trsclient.core.cosmetic.DuckRig hatRig = new dev.theredstonee.trsclient.core.cosmetic.DuckRig();
	private final dev.theredstonee.trsclient.core.cosmetic.CosmeticMesh hatMesh = new dev.theredstonee.trsclient.core.cosmetic.CosmeticMesh();
	private final dev.theredstonee.trsclient.core.emote.EmoteController emotes;
	private volatile Thread gameThread;
	/** Wiederverwendet: Umhang-Einstellungen je Tick (keine Allokation). */
	private final CapeSettings capeSettings = new CapeSettings();

	/** Eigener Skin für Menüs (null in Tests ohne Konfig-Ordner). */
	private final LocalSkin localSkin;
	/** Zuletzt gelieferter TRS-Umhang als {@link TextureRef} (keine Allokation je Bild). */
	private TextureRef lastCapeRef;

	public OnlineFeatures(TrsModules modules, TrsOnline online, CapeTextures.Backend<T> backend) {
		this(modules, online, backend, null);
	}

	public OnlineFeatures(TrsModules modules, TrsOnline online, CapeTextures.Backend<T> backend, LocalSkin localSkin) {
		this.modules = modules;
		this.online = online;
		this.textures = new CapeTextures<>(backend, online::loadCape);
		this.emotes = new dev.theredstonee.trsclient.core.emote.EmoteController(modules, online);
		this.localSkin = localSkin;
	}

	/** Standard-Aufbau: Konfiguration aus {@code configDir}, HTTP über HttpURLConnection. */
	public static <T> OnlineFeatures<T> create(TrsModules modules, Path configDir, OnlinePlatform platform,
			String modVersion, CapeTextures.Backend<T> backend) {
		String userAgent = "TRS-Client/" + modVersion + " (Minecraft " + platform.minecraftVersion() + "; " + platform.loader() + ")";
		java.util.concurrent.ThreadPoolExecutor skinWorker = new java.util.concurrent.ThreadPoolExecutor(1, 1, 30,
				java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<Runnable>(4), r -> {
					Thread t = new Thread(r, "TRS-Skin");
					t.setDaemon(true);
					t.setPriority(Thread.MIN_PRIORITY + 1);
					return t;
				});
		skinWorker.allowCoreThreadTimeOut(true);
		LocalSkin skin = new LocalSkin(configDir.resolve("trsclient").resolve("skins"), platform::session,
				new Http.UrlConnection(userAgent), skinWorker, platform::log);
		TrsOnline online = TrsOnline.create(configDir, platform, modVersion);
		OnlineFeatures<T> features = new OnlineFeatures<>(modules, online, backend, skin);
		// Client-Sync (Einstellungen je TRS-Konto, Launcher-Aussehen live) – läuft über die Anmeldung von TrsOnline.
		features.sync = dev.theredstonee.trsclient.core.sync.ClientSync.create(modules, online, configDir, userAgent, modVersion,
				platform::log);
		return features;
	}

	/** Client-Sync (null in Tests ohne Konfig-Ordner). */
	private dev.theredstonee.trsclient.core.sync.ClientSync sync;

	public dev.theredstonee.trsclient.core.sync.ClientSync sync() {
		return sync;
	}

	/**
	 * Aussehen des eigenen Spielers für Menüs (Startbildschirm, Garderobe): Name, Skin (eigener oder Standard),
	 * Umhang – der TRS-Umhang (aktuelles Bild) hat Vorrang vor dem Mojang-Umhang. Nur Render-Thread; null ohne Skin-Quelle.
	 */
	public PlayerLook look() {
		if (localSkin == null) return null;
		long now = System.currentTimeMillis();
		PlayerLook look;
		try {
			look = localSkin.look(now);
		} catch (RuntimeException e) {
			online.reportError(e);
			return null;
		}
		UUID self = localSkin.uuid();
		TextureRef trs = self == null ? null : capeRef(self);
		if (trs != null) {
			look.cape = trs;
			look.trsCape = true;
		}
		return look;
	}

	/** Eigener Skin für Menüs (null in Tests). */
	public LocalSkin localSkin() {
		return localSkin;
	}

	/** TRS-Umhang eines Spielers (aktuelles Animationsbild) als zeichenbare Textur, sonst null. */
	public TextureRef capeRef(UUID uuid) {
		T tex = capeTexture(uuid);
		if (tex == null) return null;
		CapeInfo info = online.info(uuid).cape;
		int w = textures.width(info);
		int h = textures.height(info);
		if (w <= 0 || h <= 0) return null;
		TextureRef last = lastCapeRef;
		if (last != null && last.id.equals(tex) && last.width == w && last.height == h) return last;
		lastCapeRef = new TextureRef(tex, w, h);
		return lastCapeRef;
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
			online.wantBadges(modules.badgeTab.get() || modules.badgeNametag.get());
			online.tick(now, visible, modules.trsOnline.isEnabled());
			textures.cleanup(now);
			if (sync != null) sync.tick(now);
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

	/**
	 * Textur der Kopf-Kosmetik eines Spielers oder null (nichts tragen, Modul/Einstellung aus, noch nicht geladen,
	 * Vorlage unbekannt). Nur im Spiel-/Render-Thread wirksam.
	 */
	public T hatTexture(UUID uuid) {
		if (Thread.currentThread() != gameThread) return null;
		if (!modules.trsOnline.isEnabled() || !modules.trsCosmetics.get()) return null;
		PlayerInfo info = online.info(uuid);
		if (info.hat == null || dev.theredstonee.trsclient.core.cosmetic.CosmeticModels.get(info.hat.template) == null) return null;
		try {
			return textures.texture(info.hat.texture, System.currentTimeMillis());
		} catch (RuntimeException e) {
			online.reportError(e);
			return null;
		}
	}

	/**
	 * Zeichnet die Kopf-Kosmetik (nach {@link #hatTexture}) in den Kopf-Raum des Modells; Tier-Vorlagen bewegen
	 * sich nach {@code wearer}. Fehler landen im Fehlerbericht, nie im Spiel.
	 */
	public void emitHat(UUID uuid, dev.theredstonee.trsclient.core.cosmetic.Wearer wearer, ClothMesh.QuadSink sink) {
		PlayerInfo info = online.info(uuid);
		if (info.hat == null) return;
		dev.theredstonee.trsclient.core.cosmetic.CosmeticModel model =
				dev.theredstonee.trsclient.core.cosmetic.CosmeticModels.get(info.hat.template);
		if (model == null) return;
		try {
			wearer.emote = emotes.animating(uuid);
			dev.theredstonee.trsclient.core.cosmetic.DuckRig.Pose pose = model.rig != null
					? hatRig.update(wearer, System.nanoTime()) : null;
			hatMesh.emit(model, pose, System.currentTimeMillis(), wearer.helmet, sink);
		} catch (RuntimeException e) {
			online.reportError(e);
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
