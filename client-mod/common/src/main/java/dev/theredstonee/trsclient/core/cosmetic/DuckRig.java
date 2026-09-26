package dev.theredstonee.trsclient.core.cosmetic;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * Verhalten der Quietscheente (Rig {@code duck}, API.md §11.4.1) – je Träger ein kleiner Zustand, einmal je Bild
 * fortgeschrieben: watscheln und wippen beim Laufen, sanftes Wippen im Stand, Trägheit bei schnellen Kopfdrehungen
 * (federt nach), gelegentliches Umschauen, Blinzeln, Flügelschlagen in der Luft, Stauchen bei der Landung,
 * Vorlehnen beim Sprinten und Quaken (Schleichen, Emote, ab und zu).
 *
 * <p>Nur Render-Thread. Winkel der {@link Pose} in Grad im Anhängepunkt-Raum (API.md §11.2).
 */
public final class DuckRig {
	/** Ergebnis für ein Bild (je Träger wiederverwendet). */
	public static final class Pose {
		/** Ganzes Modell: Rollen (um z), Vorlehnen (um x, + = Schnabel runter), Drehen (um y, + = nach links). */
		public float roll, lean, yaw;
		/** Hoch/runter in Pixeln. */
		public float bob;
		/** Stauchung auf y (1 = normal). */
		public float squash = 1f;
		/** Kopf: Drehen (+ = links) und Nicken (+ = runter). */
		public float lookYaw, lookPitch;
		/** Unterschnabel offen (Grad). */
		public float beak;
		/** Augen offen (0,1 … 1). */
		public float eyeOpen = 1f;
		/** Flügel nach außen (Grad). */
		public float wing;
	}

	static final class State {
		final Random rnd;
		final Pose pose = new Pose();
		long lastNanos;
		boolean init;
		float time;
		float walk;
		double phase;
		// Trägheit des Kopfes (Welt-Blickrichtung mit Feder)
		float lagYaw, lagYawV, lagPitch, lagPitchV;
		// Umschauen
		float nextGlance, glanceEnd, glanceYawT, glancePitchT, glanceYaw, glancePitch;
		// Blinzeln
		float nextBlink, blinkStart = -10f, secondBlink = -10f;
		// Quaken
		float nextQuack, quackStart = -10f;
		boolean wasSneak;
		// Luft / Landung
		boolean wasAir;
		float airTime, minVy, wingAmp, squash, squashV, lean;

		State(int entityId) {
			rnd = new Random(entityId * 31L + 7L);
			nextGlance = 1f + rnd.nextFloat() * 3f;
			nextBlink = 1f + rnd.nextFloat() * 3f;
			nextQuack = 6f + rnd.nextFloat() * 10f;
		}
	}

	/** Zustände unbenutzter Träger fallen nach dieser Zeit weg. */
	static final long FORGET_NANOS = 10_000_000_000L;

	private final Map<Integer, State> states = new HashMap<>();
	private long lastCleanup;

	/** Schreibt den Zustand von {@code w} bis {@code nowNanos} fort und liefert die Haltung für dieses Bild. */
	public Pose update(Wearer w, long nowNanos) {
		State s = states.get(w.entityId);
		if (s == null) {
			s = new State(w.entityId);
			states.put(w.entityId, s);
		}
		if (nowNanos - lastCleanup > 2_000_000_000L) {
			lastCleanup = nowNanos;
			Iterator<State> it = states.values().iterator();
			while (it.hasNext()) if (nowNanos - it.next().lastNanos > FORGET_NANOS) it.remove();
		}
		float dt;
		if (!s.init) {
			s.init = true;
			s.lagYaw = w.headYaw;
			s.lagPitch = w.headPitch;
			s.wasSneak = w.sneaking;
			dt = 0f;
		} else {
			dt = (nowNanos - s.lastNanos) / 1e9f;
			if (dt < 0f) dt = 0f;
			if (dt > 0.1f) dt = 0.1f;
		}
		s.lastNanos = nowNanos;
		step(s, w, dt);
		return s.pose;
	}

	static void step(State s, Wearer w, float dt) {
		Pose p = s.pose;
		float t = s.time += dt;
		boolean air = !w.onGround && !w.inWater;

		// --- Watscheln / Wippen ---
		float walkTarget = clamp(w.speed / 0.2f, 0f, 1.3f);
		if (air) walkTarget *= 0.3f;
		s.walk = approach(s.walk, walkTarget, dt, 8f);
		float walk = Math.min(1f, s.walk);
		s.phase += dt * 2.0 * Math.PI * (1.2 + 1.6 * s.walk);
		float sin = (float) Math.sin(s.phase);
		float cos = (float) Math.cos(s.phase);
		float idle = 1f - walk;
		p.roll = sin * 7f * walk + (float) Math.sin(t * 2.0 * Math.PI * 0.23) * 1.5f * idle;
		float wobble = cos * 4f * walk;
		p.bob = Math.abs(sin) * 0.7f * walk + (0.5f + 0.5f * (float) Math.sin(t * 2.0 * Math.PI * 0.45)) * 0.3f * idle;

		// --- Trägheit: die Ente dreht sich etwas später mit und federt nach ---
		if (dt > 0f) {
			// in kleinen Schritten, damit die Feder auch bei wenigen Bildern stabil bleibt
			int n = (int) Math.ceil(dt / 0.01f);
			float h = dt / n;
			for (int i = 0; i < n; i++) {
				float dy = wrap(w.headYaw - s.lagYaw);
				s.lagYawV += (70f * dy - 11f * s.lagYawV) * h;
				s.lagYaw += s.lagYawV * h;
				float dp = w.headPitch - s.lagPitch;
				s.lagPitchV += (70f * dp - 11f * s.lagPitchV) * h;
				s.lagPitch += s.lagPitchV * h;
			}
		}
		float relYaw = wrap(s.lagYaw - w.headYaw);
		if (relYaw > 50f || relYaw < -50f) {
			relYaw = clamp(relYaw, -50f, 50f);
			s.lagYaw = w.headYaw + relYaw;
		}
		float relPitch = s.lagPitch - w.headPitch;
		if (relPitch > 25f || relPitch < -25f) {
			relPitch = clamp(relPitch, -25f, 25f);
			s.lagPitch = w.headPitch + relPitch;
		}

		// --- Umschauen ---
		if (t >= s.nextGlance) {
			float range = 35f * (1f - 0.6f * walk);
			s.glanceYawT = (s.rnd.nextFloat() * 2f - 1f) * range;
			s.glancePitchT = -8f + s.rnd.nextFloat() * 23f;
			s.glanceEnd = t + 0.6f + s.rnd.nextFloat();
			s.nextGlance = s.glanceEnd + 1.5f + s.rnd.nextFloat() * 3f;
		}
		boolean glancing = t < s.glanceEnd;
		s.glanceYaw = approach(s.glanceYaw, glancing ? s.glanceYawT : 0f, dt, 6f);
		s.glancePitch = approach(s.glancePitch, glancing ? s.glancePitchT : 0f, dt, 6f);

		// Hinkt die Ente hinterher (Spieler dreht nach rechts), schaut sie relativ nach links (+).
		p.lookYaw = -relYaw * 0.6f + s.glanceYaw;
		p.lookPitch = relPitch * 0.6f + s.glancePitch;
		p.yaw = -relYaw * 0.4f + wobble;

		// --- Luft, Flügel, Landung, Vorlehnen ---
		if (air) {
			s.airTime += dt;
			s.minVy = Math.min(s.minVy, w.vy);
		} else if (s.wasAir) {
			if (s.airTime > 0.25f && s.minVy < -0.3f) s.squashV -= 3.5f;
			s.airTime = 0f;
			s.minVy = 0f;
		}
		s.wasAir = air;
		s.wingAmp = approach(s.wingAmp, air ? 1f : 0f, dt, air ? 14f : 6f);
		float flapHz = w.vy < -0.25f ? 8f : 6f;
		float flap = s.wingAmp * (12f + 32f * (0.5f + 0.5f * (float) Math.sin(t * 2.0 * Math.PI * flapHz)));
		float strut = (!air && !w.sprinting) ? 3f * Math.abs(sin) * walk : 0f;
		p.wing = flap + strut;
		if (dt > 0f) {
			int n = (int) Math.ceil(dt / 0.01f);
			float h = dt / n;
			for (int i = 0; i < n; i++) {
				s.squashV += (-120f * s.squash - 9f * s.squashV) * h;
				s.squash += s.squashV * h;
			}
		}
		float leanTarget = (w.sprinting && !air) ? 10f : air ? (w.vy > 0.05f ? -8f : (w.vy < -0.2f ? 10f : 0f)) : 0f;
		s.lean = approach(s.lean, leanTarget, dt, 6f);
		p.lean = s.lean;

		// --- Blinzeln (manchmal doppelt) ---
		if (t >= s.nextBlink) {
			s.blinkStart = t;
			s.secondBlink = s.rnd.nextFloat() < 0.2f ? t + 0.25f : -10f;
			s.nextBlink = t + 2.5f + s.rnd.nextFloat() * 3.5f;
		}
		if (s.secondBlink > 0f && t >= s.secondBlink) {
			s.blinkStart = s.secondBlink;
			s.secondBlink = -10f;
		}
		float bk = (t - s.blinkStart) / 0.15f;
		p.eyeOpen = bk >= 0f && bk <= 1f ? 1f - 0.9f * (float) Math.sin(Math.PI * bk) : 1f;

		// --- Quaken ---
		if (w.sneaking && !s.wasSneak) s.quackStart = t;
		s.wasSneak = w.sneaking;
		if (w.emote && t - s.quackStart > 0.9f) s.quackStart = t;
		if (t >= s.nextQuack) {
			s.quackStart = t;
			s.nextQuack = t + 10f + s.rnd.nextFloat() * 10f;
		}
		float qk = (t - s.quackStart) / 0.38f;
		p.beak = qk >= 0f && qk <= 1f ? 28f * Math.abs((float) Math.sin(2.0 * Math.PI * qk)) : 0f;
		p.lookPitch += p.beak * 0.2f;
		float quackSquash = p.beak / 28f * 0.04f;

		p.squash = clamp(1f + s.squash - quackSquash, 0.72f, 1.2f);
	}

	static float approach(float x, float target, float dt, float rate) {
		return x + (target - x) * (1f - (float) Math.exp(-rate * dt));
	}

	static float wrap(float deg) {
		float d = deg % 360f;
		if (d >= 180f) d -= 360f;
		if (d < -180f) d += 360f;
		return d;
	}

	static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : v > hi ? hi : v;
	}

	int size() {
		return states.size();
	}
}
