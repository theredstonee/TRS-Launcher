package dev.theredstonee.trsclient.core.qol;

import dev.theredstonee.trsclient.core.alert.DeathCompass;
import dev.theredstonee.trsclient.core.alert.Notices;
import dev.theredstonee.trsclient.core.alert.Warnings;
import dev.theredstonee.trsclient.core.alert.WindowAttention;
import dev.theredstonee.trsclient.core.chat.ChatMentions;
import dev.theredstonee.trsclient.core.chat.ChatText;
import dev.theredstonee.trsclient.core.connect.AutoReconnect;
import dev.theredstonee.trsclient.core.connect.QueueWatcher;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.QolModules;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.pvp.HitFeedback;
import dev.theredstonee.trsclient.core.pvp.ItemCounter;
import dev.theredstonee.trsclient.core.pvp.TotemPops;
import dev.theredstonee.trsclient.core.streamer.StreamerMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Versionsunabhängiger Kern des Komfort-/PvP-Pakets: hält alle Zustände (Erwähnungen, Warteschlange, Auto-Reconnect,
 * Hinweise, Warnungen, Zähler, Treffer, Streamer-Modus) und entscheidet. Jeder Loader liefert nur eine kleine
 * {@link Platform} (Ton, Fensterfokus, Fenster-Handle) und ruft die Haken aus seinen Mixins/Ereignissen auf. Alle
 * Methoden laufen im Spiel-Thread; Fehler werfen nie bis ins Spiel durch (Aufrufer fangen ab).
 */
public final class Qol {
	/** Was jeder Loader bereitstellt. */
	public interface Platform {
		/** Spielt einen UI-Klang (Lautstärke 0–1); fehlt der Klang in dieser Version, passiert nichts. */
		void play(QolSound sound, float volume, float pitch);

		/** Hat das Spielfenster gerade den Fokus? */
		boolean focused();

		/** Fenster-Handle für das Taskleisten-Blinken (0 = keines, z. B. LWJGL 2). */
		long windowHandle();
	}

	private static Qol instance;

	public final TrsModules modules;
	public final QolModules m;
	private final Platform platform;

	public final ChatMentions mentions = new ChatMentions();
	public final QueueWatcher queue = new QueueWatcher();
	public final AutoReconnect reconnect = new AutoReconnect();
	public final Notices notices = new Notices();
	public final Warnings warnings = new Warnings();
	private final Warnings.Config warnConfig = new Warnings.Config();
	public final DeathCompass death = new DeathCompass();
	public final ItemCounter counter = new ItemCounter();
	public final TotemPops pops = new TotemPops();
	public final HitFeedback hits = new HitFeedback();
	public final StreamerMode streamer = new StreamerMode();

	/** Abklingzeit der Hintergrund-Hinweise je Art. */
	private final long[] lastAlert = new long[4];
	private static final long ALERT_GAP_MS = 10_000;
	private String ownName = "";

	private Qol(TrsModules modules, Platform platform) {
		this.modules = modules;
		this.m = modules.qol;
		this.platform = platform;
	}

	/** Einmal beim Start des Loaders. */
	public static Qol init(TrsModules modules, Platform platform) {
		instance = new Qol(modules, platform);
		return instance;
	}

	/** Laufende Instanz oder null (vor dem Start / Benchmark ohne TRS). */
	public static Qol get() {
		return instance;
	}

	// --- Tick ---

	/**
	 * Einmal je Client-Tick. {@code ownName} = eigener Spielername (auch außerhalb einer Welt bekannt).
	 */
	public void tick(String ownName, long now) {
		this.ownName = ownName == null ? "" : ownName;
		streamer.configure(m.streamer.isEnabled(), this.ownName, m.streamerName.get(), m.streamerOthers.get());
		queue.configure(m.queuePatterns.get());
		notices.prune(now);
		if (hits.takeSound()) {
			QolSound s = QolSound.of(m.hitSound.get());
			if (s != null && m.hitFeedback.isEnabled()) platform.play(s, volume(m.hitVolume.get()), 1.0f);
		}
	}

	/** Warnungen auswerten, nachdem der Loader {@code warnings.input()} gefüllt hat. */
	public void tickWarnings(long now) {
		if (!m.warnings.isEnabled()) {
			warnings.tick(disabledConfig(), now);
			return;
		}
		Warnings.Config c = warnConfig;
		c.armor = m.warnArmor.get();
		c.tools = m.warnTools.get();
		c.durabilityPercent = m.warnDurability.getInt();
		c.hunger = m.warnHunger.get();
		c.hungerLevel = m.warnHungerLevel.getInt() * 2;
		c.health = m.warnHealth.get();
		c.healthLevel = m.warnHealthLevel.getInt() * 2;
		c.inventory = m.warnInventory.get();
		c.cooldownMs = (long) (m.warnCooldown.get() * 1000);
		List<Warnings.Alert> alerts = warnings.tick(c, now);
		for (int i = 0; i < alerts.size(); i++) {
			Warnings.Alert a = alerts.get(i);
			notices.show("warn:" + a.kind + a.slot, level(a.kind), warningText(a), icon(a.kind), now);
		}
		if (!alerts.isEmpty() && m.warnSound.get()) platform.play(QolSound.BASS, 0.6f, 1.2f);
	}

	private Warnings.Config disabled;

	private Warnings.Config disabledConfig() {
		if (disabled == null) {
			disabled = new Warnings.Config();
			disabled.armor = disabled.tools = disabled.hunger = disabled.health = disabled.inventory = false;
		}
		return disabled;
	}

	private static Notices.Level level(Warnings.Kind k) {
		return k == Warnings.Kind.HEALTH ? Notices.Level.DANGER : Notices.Level.WARN;
	}

	private static String icon(Warnings.Kind k) {
		switch (k) {
			case ARMOR:
				return "shield";
			case TOOL:
				return "sword";
			case HUNGER:
				return "heartOutline";
			case HEALTH:
				return "heart";
			default:
				return "packs";
		}
	}

	/** Text einer Warnung in der Sprache des Spielers. */
	public static String warningText(Warnings.Alert a) {
		switch (a.kind) {
			case ARMOR: {
				String item = a.item != null && !a.item.isEmpty() ? a.item : I18n.tr("warn.slot." + a.slot);
				return I18n.tr("warn.armor", item, a.value);
			}
			case TOOL:
				return I18n.tr("warn.tool", a.item == null ? "" : a.item, a.value);
			case HUNGER:
				return I18n.tr("warn.hunger");
			case HEALTH:
				return I18n.tr("warn.health");
			default:
				return I18n.tr("warn.inventory");
		}
	}

	// --- Chat ---

	/** Soll die Nachricht ausgeblendet werden (Chat-Filter)? */
	public boolean hideChat(String plain) {
		if (!m.chatFilter.isEnabled()) return false;
		return ChatText.containsAny(plain, ChatText.words(m.chatFilterWords.get()));
	}

	/** Aktuelle Wortliste der Erwähnungen (leer = aus). */
	public List<String> mentionWords() {
		if (!m.mentions.isEnabled()) return new ArrayList<String>();
		List<String> words = mentions.words(ownName, m.mentionsOwnName.get(), m.mentionsWords.get());
		if (!streamer.active() || !m.mentionsOwnName.get()) return words;
		// Im Streamer-Modus steht statt des eigenen Namens der Ersatzname in der Zeile – den hervorheben.
		String shown = streamer.name(ownName);
		if (shown == null || shown.length() < ChatText.MIN_WORD) return words;
		List<String> withShown = new ArrayList<String>(words);
		String lower = shown.toLowerCase(java.util.Locale.ROOT);
		if (!withShown.contains(lower)) withShown.add(lower);
		return withShown;
	}

	/**
	 * Eingehende Chat-Zeile (Rohtext mit Farbcodes): Warteschlange, Erwähnung (Ton + Hintergrund-Hinweis).
	 *
	 * @return true = die Zeile ist eine Erwähnung (hervorheben)
	 */
	public boolean onChat(String raw, long now) {
		if (raw == null) return false;
		onServerText(raw, now);
		// Erkennen mit den echten Wörtern (der Ersatzname des Streamer-Modus zählt hier nicht).
		List<String> words = m.mentions.isEnabled() ? mentions.words(ownName, m.mentionsOwnName.get(), m.mentionsWords.get())
				: new ArrayList<String>();
		if (words.isEmpty()) return false;
		if (ChatMentions.mentions(raw, words, ownName).isEmpty()) return false;
		if (m.mentionsSound.get() && mentions.soundAllowed(now)) {
			platform.play(QolSound.of(m.mentionsSoundType.get()), volume(m.mentionsVolume.get()), 1.0f);
		}
		if (m.queueAlerts.isEnabled() && m.alertMention.get() && !platform.focused()) {
			String text = ChatText.strip(raw).trim();
			if (text.length() > 60) text = text.substring(0, 57) + "...";
			background(0, Notices.Level.INFO, "bell", I18n.tr("alert.mention", text), now);
		}
		return true;
	}

	/** Aktionsleiste oder Titel vom Server (nur Warteschlange). */
	public void onServerText(String raw, long now) {
		if (!m.queueAlerts.isEnabled() || raw == null) return;
		QueueWatcher.Event e = queue.onText(raw, now, m.queuePosition.getInt());
		if (e == QueueWatcher.Event.ALMOST) {
			alert(1, Notices.Level.INFO, "clock", I18n.tr("queue.almost", queue.position()), now, true);
		} else if (e == QueueWatcher.Event.DONE) {
			alert(1, Notices.Level.INFO, "check", I18n.tr("queue.done"), now, true);
		}
	}

	// --- Hintergrund-Hinweise ---

	/** Eigener Tod (einmal je Tod). */
	public void onOwnDeath(long now) {
		if (m.queueAlerts.isEnabled() && m.alertDeath.get() && !platform.focused()) {
			background(2, Notices.Level.DANGER, "heart", I18n.tr("alert.death"), now);
		}
	}

	/** Getrennt-Bildschirm erschienen. */
	public void onKicked(String reason, long now) {
		if (m.queueAlerts.isEnabled() && m.alertKick.get() && !platform.focused()) {
			String r = ChatText.strip(reason == null ? "" : reason).trim();
			if (r.length() > 60) r = r.substring(0, 57) + "...";
			background(3, Notices.Level.DANGER, "leave", I18n.tr("alert.kick", r), now);
		}
	}

	/** Nur, wenn das Spiel im Hintergrund ist (sonst sieht man es ja). */
	private void background(int type, Notices.Level level, String icon, String text, long now) {
		alert(type, level, icon, text, now, false);
	}

	/**
	 * Hinweis + Ton + Taskleisten-Blinken (nur im Hintergrund), mit Abklingzeit je Art.
	 *
	 * @param always auch bei Fokus einen Hinweis zeigen (Warteschlange)
	 */
	private void alert(int type, Notices.Level level, String icon, String text, long now, boolean always) {
		boolean focused = platform.focused();
		if (!always && focused) return;
		if (now - lastAlert[type] < ALERT_GAP_MS && lastAlert[type] != 0) return;
		lastAlert[type] = now;
		notices.show("alert:" + type, level, text, icon, now);
		if (m.alertSound.get()) platform.play(QolSound.BELL, 0.8f, 1.0f);
		if (!focused && m.alertFlash.get()) WindowAttention.request(platform.windowHandle());
	}

	// --- Treffer ---

	/** Eigener Schlag hat getroffen (Ziel zeigt die Schadens-Animation). */
	public void onHitConfirmed(boolean critical, long now) {
		if (m.hitFeedback.isEnabled()) hits.onHit(now, critical);
	}

	/** Zusätzliche Partikel-Emitter je Vanilla-Emitter (0 = keine). */
	public int extraParticles() {
		if (!m.hitFeedback.isEnabled()) return 0;
		return HitFeedback.particleCopies(m.hitParticles.get()) - 1;
	}

	/** Entity-Ereignis eines Lebewesens: 35 = Totem, 3 = Tod. */
	public void onEntityEvent(String name, boolean self, int id, long now) {
		if (id == 35) pops.onPop(name, self, now);
		else if (id == 3 && !self) pops.onDeath(name);
	}

	// --- Hilfen ---

	public void notice(String key, Notices.Level level, String text, String icon, long now) {
		notices.show(key, level, text, icon, now);
	}

	/** Streamer-Modus umschalten (Taste). */
	public void toggleStreamer(long now) {
		m.streamer.toggle();
		notices.show("streamer", Notices.Level.INFO,
				I18n.tr("toast.streamerMode", I18n.tr(m.streamer.isEnabled() ? "common.enabled" : "common.disabled")), "eye", now);
	}

	/** Welt verlassen bzw. gewechselt. */
	public void onWorldChange() {
		pops.reset();
		warnings.reset();
		queue.reset();
		hits.reset();
		streamer.forgetPlayers();
	}

	public String ownName() {
		return ownName;
	}

	private static float volume(double percent) {
		return (float) Math.max(0.05, Math.min(1.0, percent / 100.0));
	}
}
