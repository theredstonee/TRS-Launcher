package dev.theredstonee.trsclient.core.perf;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.menu.ModulePanel;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Die Leistungs-Funktionen des TRS Clients, versionsunabhängig. Der Loader ruft je Client-Tick
 * {@link #tick}, je Bild {@link #frameLimit} und aus seinen Hooks die Abfragen
 * ({@link #allowParticle}, {@link #cullEntity}, {@link #active} …) auf. Alle Abfragen sind billig:
 * Schalter und Entfernungen werden einmal je Tick in Felder übernommen.
 */
public final class Performance {
	/** Arten von Wesen für die Entfernungsgrenzen. */
	public enum EntityKind {
		MOB, PLAYER, ITEM, FRAME
	}

	private final TrsModules m;
	private final PerfCompat compat;
	private final DynamicFps dynamicFps = new DynamicFps();
	private final FramePacer pacer = new FramePacer();
	private final FpsMeter meter = new FpsMeter();
	private final ParticleGate particles = new ParticleGate();
	private final Occlusion occlusion = new Occlusion();
	private GameOptions game;

	// je Tick übernommen
	private final boolean[] active = new boolean[PerfFeature.values().length];
	private double mobDistSq;
	private double itemDistSq;
	private double frameDistSq;
	private double blockEntityDistSq;
	private double nameTagDistSq;
	private int particleLimit;
	private int afkFps;
	private boolean afkBefore;
	private double particleAmount = 1;
	private boolean keepPlayers = true;
	private long ticks;
	private boolean lastMaster;
	private boolean masterKnown;

	// je Bild
	private volatile int frameLimit;
	private volatile float volume = 1f;
	private double gameFps;

	private List<PerfCheck.Finding> findings = Collections.emptyList();
	private long findingsAt;
	private String message;
	private long messageUntil;

	public Performance(TrsModules modules, PerfCompat compat) {
		this.m = modules;
		this.compat = compat;
		refresh();
		ModulePanel.Registry.set(m.fpsBoost, new PerfPanel(this));
		for (Module module : new Module[]{m.dynamicFps, m.entityCulling, m.particles, m.worldDetails}) {
			ModulePanel.Registry.set(module, new PerfPanel.Notice(this, module));
		}
		ModulePanel.Registry.set(m.builtinOptimizations, new PerfPanel.Bundled(m.builtinOptimizations));
	}

	/** Vanilla-Optionen dieser Version (sobald Minecraft sie geladen hat). */
	public void setGame(GameOptions game) {
		this.game = game;
	}

	public GameOptions game() {
		return game;
	}

	public PerfCompat compat() {
		return compat;
	}

	public TrsModules modules() {
		return m;
	}

	public FpsMeter meter() {
		return meter;
	}

	public FramePacer pacer() {
		return pacer;
	}

	public Occlusion occlusion() {
		return occlusion;
	}

	public DynamicFps dynamicFps() {
		return dynamicFps;
	}

	// --- Tick ---

	/**
	 * Einmal je Client-Tick.
	 * @param screenOpen ein Bildschirm (Menü) ist offen – dann zählt die Bildrate nicht als Spiel-FPS
	 */
	public void tick(long nowMillis, boolean screenOpen) {
		ticks++;
		occlusion.tick(ticks);
		refresh();
		boolean master = m.fpsBoost.isEnabled();
		if (masterKnown && master != lastMaster) {
			meter.compare(nowMillis, I18n.tr(master ? "perf.compare.on" : "perf.compare.off"));
		}
		lastMaster = master;
		masterKnown = true;
		if (!screenOpen) {
			double recent = meter.recent(nowMillis, 3000);
			if (recent > 0) gameFps = recent;
		}
	}

	/** Welt verlassen/gewechselt. */
	public void worldChanged() {
		occlusion.clear();
	}

	/** Schalter und Grenzen aus den Modulen übernehmen. */
	public void refresh() {
		for (PerfFeature f : PerfFeature.values()) active[f.ordinal()] = compute(f);
		mobDistSq = sq(m.cullEntities.get());
		itemDistSq = sq(m.cullItems.get());
		frameDistSq = sq(m.cullFrames.get());
		blockEntityDistSq = sq(m.cullBlockEntities.get());
		nameTagDistSq = sq(m.cullNameTags.get());
		keepPlayers = m.cullKeepPlayers.get();
		particleLimit = m.particleLimit.getInt();
		afkFps = m.dynamicFpsAfk.getInt();
		particleAmount = m.particleAmount.get() / 100.0;
	}

	private boolean compute(PerfFeature f) {
		if (!m.fpsBoost.isEnabled() || !compat.ours(f)) return false;
		switch (f) {
			case DYNAMIC_FPS:
				return m.dynamicFps.isEnabled();
			case BACKGROUND_VOLUME:
				return m.dynamicFps.isEnabled() && m.dynamicFpsQuieter.get();
			case ENTITY_DISTANCE:
				return m.entityCulling.isEnabled() && m.cullEntities.get() > 0;
			case ENTITY_OCCLUSION:
				// Mit Shaderpack ruht das Ausblenden hinter Wänden: der Schatten-Durchgang braucht auch
				// Wesen, die die Kamera nicht sieht (sonst fehlen ihre Schatten).
				return m.entityCulling.isEnabled() && m.cullOcclusion.get() && !ShaderPacks.active();
			case BLOCK_ENTITY_DISTANCE:
				return m.entityCulling.isEnabled() && m.cullBlockEntities.get() > 0;
			case NAMETAG_DISTANCE:
				return m.entityCulling.isEnabled() && m.cullNameTags.get() > 0;
			case ITEM_DISTANCE:
				return m.entityCulling.isEnabled() && m.cullItems.get() > 0;
			case FRAME_DISTANCE:
				return m.entityCulling.isEnabled() && m.cullFrames.get() > 0;
			case PARTICLE_LIMIT:
				return m.particles.isEnabled() && m.particleLimit.get() > 0;
			case PARTICLE_AMOUNT:
				return m.particles.isEnabled() && m.particleAmount.get() < 100;
			case PARTICLE_EXPLOSIONS:
				return m.particles.isEnabled() && m.particleNoExplosions.get();
			case PARTICLE_RAIN:
				return m.particles.isEnabled() && m.particleNoRain.get();
			case PARTICLE_SMOKE:
				return m.particles.isEnabled() && m.particleNoSmoke.get();
			case SKY:
				return m.worldDetails.isEnabled() && m.detailNoSky.get();
			case STARS:
				return m.worldDetails.isEnabled() && m.detailNoStars.get();
			case FOG:
				return m.worldDetails.isEnabled() && m.detailNoFog.get();
			case WEATHER:
				return m.worldDetails.isEnabled() && m.detailNoWeather.get();
			case TEXTURE_ANIMATIONS:
				return m.worldDetails.isEnabled() && m.detailNoAnimations.get();
			default:
				return false;
		}
	}

	/** Läuft die TRS-Variante dieser Funktion gerade (Modul an, Einstellung an, nicht übernommen)? */
	public boolean active(PerfFeature f) {
		return active[f.ordinal()];
	}

	/** Zu welchem Modul eine Funktion gehört. */
	public Module moduleOf(PerfFeature f) {
		switch (f) {
			case DYNAMIC_FPS:
			case BACKGROUND_VOLUME:
				return m.dynamicFps;
			case PARTICLE_LIMIT:
			case PARTICLE_AMOUNT:
			case PARTICLE_EXPLOSIONS:
			case PARTICLE_RAIN:
			case PARTICLE_SMOKE:
				return m.particles;
			case SKY:
			case STARS:
			case FOG:
			case WEATHER:
			case TEXTURE_ANIMATIONS:
				return m.worldDetails;
			default:
				return m.entityCulling;
		}
	}

	// --- Bild (Dynamische FPS) ---

	/**
	 * Je Bild vor dem Zeichnen: Fensterzustand und Eingaben melden.
	 * @return Bildraten-Grenze für dieses Bild (0 = keine)
	 */
	public int frameLimit(long nowMillis, boolean focused, boolean minimized, double mouseX, double mouseY, boolean anyKey) {
		boolean afk = afkActive();
		// Gerade eingeschaltet: AFK-Zeit ab jetzt zählen (nicht ab der letzten Eingabe vor dem Ausschalten).
		if (afk && !afkBefore) dynamicFps.touch(nowMillis);
		afkBefore = afk;
		if (afk) dynamicFps.input(nowMillis, mouseX, mouseY, anyKey);
		DynamicFps.State state = dynamicFps.update(nowMillis, focused, minimized, afk ? m.dynamicFpsAfkMinutes.get() : 0);
		int limit = 0;
		float vol = 1f;
		if (active(PerfFeature.DYNAMIC_FPS)) {
			limit = DynamicFps.limit(state, m.dynamicFpsUnfocused.getInt(), m.dynamicFpsMinimized.getInt(), m.dynamicFpsAfk.getInt());
		}
		if (active(PerfFeature.BACKGROUND_VOLUME)) vol = DynamicFps.volume(state, true, m.dynamicFpsVolume.get());
		frameLimit = limit;
		volume = vol;
		meter.frame(nowMillis, limit > 0);
		return limit;
	}

	/**
	 * Ist die AFK-Grenze an (Dynamische FPS aktiv und „FPS bei AFK“ &gt; 0)? Nur dann braucht
	 * {@link #frameLimit} überhaupt Maus und Tasten – sonst fragt der Loader sie gar nicht erst ab.
	 */
	public boolean afkActive() {
		return active[PerfFeature.DYNAMIC_FPS.ordinal()] && afkFps > 0;
	}

	/** Gewünschter Lautstärke-Faktor (1 = unverändert). */
	public float volume() {
		return volume;
	}

	/** Zuletzt bestimmte Bildraten-Grenze (0 = keine). */
	public int currentLimit() {
		return frameLimit;
	}

	/** Bildrate im Spiel (ohne offenes Menü), 0 = noch unbekannt. */
	public double gameFps() {
		return gameFps;
	}

	// --- Partikel ---

	/** Darf dieser neue Partikel entstehen? {@code count} = aktuell vorhandene (≤ 0 = unbekannt). */
	public boolean allowParticle(ParticleGate.Kind kind, int count) {
		boolean hide = false;
		switch (kind) {
			case EXPLOSION:
				hide = active[PerfFeature.PARTICLE_EXPLOSIONS.ordinal()];
				break;
			case RAIN:
				hide = active[PerfFeature.PARTICLE_RAIN.ordinal()];
				break;
			case SMOKE:
				hide = active[PerfFeature.PARTICLE_SMOKE.ordinal()];
				break;
			default:
				break;
		}
		int limit = active[PerfFeature.PARTICLE_LIMIT.ordinal()] ? particleLimit : 0;
		double amount = active[PerfFeature.PARTICLE_AMOUNT.ordinal()] ? particleAmount : 1;
		return particles.allow(kind, count, limit, amount, hide);
	}

	/** Ist diese Partikel-Art gerade ausgeschaltet? (ohne Obergrenze/Menge) */
	public boolean hidesParticle(ParticleGate.Kind kind) {
		switch (kind) {
			case EXPLOSION:
				return active[PerfFeature.PARTICLE_EXPLOSIONS.ordinal()];
			case RAIN:
				return active[PerfFeature.PARTICLE_RAIN.ordinal()];
			case SMOKE:
				return active[PerfFeature.PARTICLE_SMOKE.ordinal()];
			default:
				return false;
		}
	}

	/** Muss die Partikel-Zählung überhaupt gefragt werden? */
	public boolean particleLimitActive() {
		return active[PerfFeature.PARTICLE_LIMIT.ordinal()];
	}

	/** Obergrenze oder Menge aktiv (sonst kann der Hook in ParticleEngine#add sofort zurückkehren)? */
	public boolean particlesActive() {
		return active[PerfFeature.PARTICLE_LIMIT.ordinal()] || active[PerfFeature.PARTICLE_AMOUNT.ordinal()];
	}

	// --- Wesen ---

	/** Zu weit weg für seine Art? {@code distSq} = Abstand² zur Kamera. */
	public boolean cullEntity(EntityKind kind, double distSq) {
		switch (kind) {
			case ITEM:
				return active[PerfFeature.ITEM_DISTANCE.ordinal()] && distSq > itemDistSq;
			case FRAME:
				return active[PerfFeature.FRAME_DISTANCE.ordinal()] && distSq > frameDistSq;
			case PLAYER:
				return !keepPlayers && active[PerfFeature.ENTITY_DISTANCE.ordinal()] && distSq > mobDistSq;
			default:
				return active[PerfFeature.ENTITY_DISTANCE.ordinal()] && distSq > mobDistSq;
		}
	}

	/** Block-Entity (Truhe, Schild, Banner …) zu weit weg? */
	public boolean cullBlockEntity(double distSq) {
		return active[PerfFeature.BLOCK_ENTITY_DISTANCE.ordinal()] && distSq > blockEntityDistSq;
	}

	/** Namensschild zu weit weg? */
	public boolean hideNameTag(double distSq) {
		return active[PerfFeature.NAMETAG_DISTANCE.ordinal()] && distSq > nameTagDistSq;
	}

	/** Irgendeine Wesen-Funktion aktiv? */
	public boolean entitiesActive() {
		return active[PerfFeature.ENTITY_DISTANCE.ordinal()] || active[PerfFeature.ENTITY_OCCLUSION.ordinal()]
				|| active[PerfFeature.ITEM_DISTANCE.ordinal()] || active[PerfFeature.FRAME_DISTANCE.ordinal()];
	}

	// --- FPS-Boost, Leistungs-Check, Rückgängig ---

	/** Setzt eine Boost-Stufe (Module + Vanilla-Optionen, alles rückgängig machbar). */
	public void applyPreset(BoostPreset p, long nowMillis) {
		for (Module module : new Module[]{m.fpsBoost, m.dynamicFps, m.entityCulling, m.particles, m.worldDetails}) {
			m.perfUndo.rememberModule(module);
		}
		m.fpsBoost.setEnabled(true);
		m.dynamicFps.setEnabled(true);
		m.dynamicFpsUnfocused.set(p.unfocusedFps);
		m.dynamicFpsAfk.set(p.afkFps);
		m.entityCulling.setEnabled(true);
		m.cullOcclusion.set(true);
		m.cullEntities.set(p.entities);
		m.cullKeepPlayers.set(true);
		m.cullBlockEntities.set(p.blockEntities);
		m.cullNameTags.set(p.nameTags);
		m.cullItems.set(p.items);
		m.cullFrames.set(p.items);
		m.particles.setEnabled(true);
		m.particleLimit.set(p.particleLimit);
		m.particleAmount.set(p.particleAmount);
		m.particleNoExplosions.set(p.noExplosions);
		m.particleNoRain.set(p.noRain);
		m.particleNoSmoke.set(p.noSmoke);
		m.worldDetails.setEnabled(p.details);
		if (p.details) {
			m.detailNoStars.set(true);
			m.detailNoWeather.set(true);
			m.detailNoAnimations.set(true);
		}
		int changed = 0;
		if (game != null) {
			int view = game.get(GameOptions.Opt.VIEW_DISTANCE);
			boolean smoothBool = game.smoothLightingIsBoolean();
			GameOptions.Opt[] order = {GameOptions.Opt.VIEW_DISTANCE, GameOptions.Opt.VSYNC, GameOptions.Opt.MAX_FPS,
					GameOptions.Opt.SIMULATION_DISTANCE,
					GameOptions.Opt.GRAPHICS, GameOptions.Opt.CLOUDS, GameOptions.Opt.PARTICLES, GameOptions.Opt.MIPMAP,
					GameOptions.Opt.BIOME_BLEND, GameOptions.Opt.ENTITY_DISTANCE, GameOptions.Opt.SMOOTH_LIGHTING};
			for (GameOptions.Opt o : order) {
				int current = game.get(o);
				int target = p.target(o, current, view, smoothBool);
				if (target == GameOptions.NONE) continue;
				if (set(o, current, target)) changed++;
				if (o == GameOptions.Opt.VIEW_DISTANCE) view = game.get(GameOptions.Opt.VIEW_DISTANCE);
			}
			if (changed > 0) game.save();
		}
		refresh();
		findingsAt = 0;
		meter.compare(nowMillis, p.label());
		say(I18n.tr("perf.boost.applied", p.label(), changed));
	}

	/** Wendet Korrekturen des Leistungs-Checks an. */
	public void applyFixes(Map<GameOptions.Opt, Integer> fixes, long nowMillis) {
		if (game == null || fixes.isEmpty()) return;
		int changed = 0;
		for (Map.Entry<GameOptions.Opt, Integer> e : fixes.entrySet()) {
			if (set(e.getKey(), game.get(e.getKey()), e.getValue().intValue())) changed++;
		}
		if (changed > 0) game.save();
		findingsAt = 0;
		meter.compare(nowMillis, I18n.tr("perf.compare.fix"));
		say(I18n.tr("perf.check.fixed", changed));
	}

	private boolean set(GameOptions.Opt o, int current, int target) {
		if (current == GameOptions.NONE || current == target) return false;
		m.perfUndo.rememberOption(o, current);
		return game.set(o, target);
	}

	/** Gibt es etwas rückgängig zu machen? */
	public boolean canUndo() {
		return !m.perfUndo.isEmpty();
	}

	/** Stellt alle Werte von vor FPS-Boost/Leistungs-Check wieder her. */
	public int undo(long nowMillis) {
		int n = m.perfUndo.undo(game, m.registry);
		refresh();
		findingsAt = 0;
		meter.compare(nowMillis, I18n.tr("perf.compare.undo"));
		say(I18n.tr("perf.boost.undone", n));
		return n;
	}

	/** Funde des Leistungs-Checks (höchstens einmal je Sekunde neu berechnet). */
	public List<PerfCheck.Finding> findings(long nowMillis) {
		if (game == null) return Collections.emptyList();
		if (nowMillis - findingsAt > 1000) {
			double fps = gameFps > 0 ? gameFps : meter.recent(nowMillis, 3000);
			findings = PerfCheck.run(game, fps, GpuInfo.dedicatedAdapter(), compat.missingRecommended());
			findingsAt = nowMillis;
		}
		return findings;
	}

	/** Kurze Rückmeldung im Menü („3 Einstellungen gesenkt“). */
	private void say(String text) {
		message = text;
		messageUntil = System.currentTimeMillis() + 6000;
	}

	/** Aktuelle Rückmeldung oder null. */
	public String message(long nowMillis) {
		return nowMillis < messageUntil ? message : null;
	}

	private static double sq(double v) {
		return v * v;
	}
}
