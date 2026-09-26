package dev.theredstonee.trsclient.core.alert;

import java.util.ArrayList;
import java.util.List;

/**
 * Warnungen: Rüstung bzw. Werkzeug fast kaputt, wenig Hunger, wenig Leben, Inventar voll. Jede Warnung kommt einmal,
 * wenn die Schwelle unterschritten wird, und erst wieder, nachdem der Wert sich erholt hat (mit etwas Abstand zur
 * Schwelle) UND die Abklingzeit vorbei ist. Nur Anzeige; der Loader füllt je Tick {@link Input}.
 */
public final class Warnings {
	public enum Kind {
		ARMOR, TOOL, HUNGER, HEALTH, INVENTORY
	}

	/** Eine ausgelöste Warnung. */
	public static final class Alert {
		public final Kind kind;
		/** Rüstungsplatz 0–3 (Kopf … Füße), sonst -1. */
		public final int slot;
		/** Haltbarkeit in % bzw. Leben/Hunger als Wert. */
		public final int value;
		/** Name des Gegenstands (Rüstung/Werkzeug), sonst null. */
		public final String item;

		Alert(Kind kind, int slot, int value, String item) {
			this.kind = kind;
			this.slot = slot;
			this.value = value;
			this.item = item;
		}
	}

	/** Zustand des Spielers, einmal je Tick vom Loader gefüllt (keine Allokation). */
	public static final class Input {
		/** Schaden und Höchst-Haltbarkeit je Rüstungsplatz (0 = Kopf); max ≤ 0 = leer/unzerstörbar. */
		public final int[] armorDamage = new int[4];
		public final int[] armorMax = new int[4];
		public final String[] armorName = new String[4];
		public int handDamage;
		public int handMax;
		public String handName;
		public float health;
		public float maxHealth = 20;
		public int food = 20;
		public boolean inventoryFull;
		/** Überleben/Abenteuer (in Kreativ/Zuschauer keine Warnungen). */
		public boolean survival;
		public boolean alive;

		public Input clear() {
			for (int i = 0; i < 4; i++) {
				armorDamage[i] = 0;
				armorMax[i] = 0;
				armorName[i] = null;
			}
			handDamage = 0;
			handMax = 0;
			handName = null;
			health = 20;
			maxHealth = 20;
			food = 20;
			inventoryFull = false;
			survival = false;
			alive = false;
			return this;
		}
	}

	/** Einstellungen (vom Modul übernommen). */
	public static final class Config {
		public boolean armor = true;
		public boolean tools = true;
		/** Haltbarkeit in Prozent, ab der gewarnt wird. */
		public int durabilityPercent = 10;
		public boolean hunger = true;
		/** Hungerpunkte (0–20), ab denen gewarnt wird. */
		public int hungerLevel = 6;
		public boolean health = true;
		/** Lebenspunkte (halbe Herzen), ab denen gewarnt wird. */
		public int healthLevel = 6;
		public boolean inventory = true;
		public long cooldownMs = 60_000;
	}

	/** Abstand, um den sich ein Wert erholen muss, bevor dieselbe Warnung wieder scharf wird. */
	static final int DURABILITY_MARGIN = 3;
	static final int POINTS_MARGIN = 2;

	private final Input input = new Input();
	/** Scharf = darf beim nächsten Unterschreiten warnen; je Rüstungsplatz + Werkzeug + Hunger + Leben + Inventar. */
	private final boolean[] armed = new boolean[8];
	private final long[] lastAlert = new long[8];
	/** Werkzeug-Identität: anderer Gegenstand in der Hand = neu scharf. */
	private String lastHand;
	private final List<Alert> out = new ArrayList<Alert>();

	public Warnings() {
		reset();
	}

	public Input input() {
		return input.clear();
	}

	/** Nach Tod/Weltwechsel: alles wieder scharf. */
	public void reset() {
		for (int i = 0; i < armed.length; i++) {
			armed[i] = true;
			lastAlert[i] = Long.MIN_VALUE / 2;
		}
		lastHand = null;
		out.clear();
	}

	/**
	 * Werte des Ticks auswerten ({@link #input()} vorher füllen).
	 *
	 * @return neue Warnungen dieses Ticks (Liste wird beim nächsten Aufruf geleert)
	 */
	public List<Alert> tick(Config cfg, long now) {
		out.clear();
		Input in = input;
		if (!in.alive || !in.survival) return out;
		for (int s = 0; s < 4; s++) {
			int pct = percent(in.armorDamage[s], in.armorMax[s]);
			check(s, cfg.armor && pct >= 0, pct >= 0 && pct <= cfg.durabilityPercent,
					pct < 0 || pct > cfg.durabilityPercent + DURABILITY_MARGIN, now, cfg.cooldownMs,
					Kind.ARMOR, s, pct, in.armorName[s]);
		}
		String hand = in.handName == null ? null : in.handName + "|" + in.handMax;
		if (hand == null ? lastHand != null : !hand.equals(lastHand)) {
			armed[4] = true;
			lastHand = hand;
		}
		int toolPct = percent(in.handDamage, in.handMax);
		check(4, cfg.tools && toolPct >= 0, toolPct >= 0 && toolPct <= cfg.durabilityPercent,
				toolPct < 0 || toolPct > cfg.durabilityPercent + DURABILITY_MARGIN, now, cfg.cooldownMs,
				Kind.TOOL, -1, toolPct, in.handName);
		check(5, cfg.hunger, in.food <= cfg.hungerLevel, in.food > cfg.hungerLevel + POINTS_MARGIN, now, cfg.cooldownMs,
				Kind.HUNGER, -1, in.food, null);
		int hp = (int) Math.ceil(in.health);
		check(6, cfg.health, hp <= cfg.healthLevel && hp < in.maxHealth, hp > cfg.healthLevel + POINTS_MARGIN, now,
				cfg.cooldownMs, Kind.HEALTH, -1, hp, null);
		check(7, cfg.inventory, in.inventoryFull, !in.inventoryFull, now, cfg.cooldownMs, Kind.INVENTORY, -1, 0, null);
		return out;
	}

	private void check(int idx, boolean enabled, boolean low, boolean recovered, long now, long cooldown, Kind kind,
			int slot, int value, String item) {
		if (!enabled) {
			armed[idx] = true;
			return;
		}
		if (recovered) {
			armed[idx] = true;
			return;
		}
		if (low && armed[idx] && now - lastAlert[idx] >= cooldown) {
			armed[idx] = false;
			lastAlert[idx] = now;
			out.add(new Alert(kind, slot, value, item));
		}
	}

	/** Haltbarkeit in Prozent (abgerundet), -1 = kein zerstörbarer Gegenstand. */
	public static int percent(int damage, int max) {
		if (max <= 0) return -1;
		int left = Math.max(0, max - damage);
		return (int) Math.floor(left * 100.0 / max);
	}
}
