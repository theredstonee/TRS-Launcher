package dev.theredstonee.trsclient.core.emote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.theredstonee.trsclient.core.emote.Channel.*;

/**
 * Alle Emotes der TRS API ({@code api/server/lib/emotes.ts}, gleiche IDs und Reihenfolge) als Animationen.
 * IDs sind dauerhaft; unbekannte IDs aus der API werden ignoriert. Winkel in Grad, Verschiebungen in Pixeln.
 */
public final class Emotes {
	private static final Map<String, EmoteDef> BY_ID = new LinkedHashMap<>();
	public static final List<EmoteDef> ALL;

	static {
		List<EmoteDef> all = new ArrayList<>();

		// Winken: rechter Arm hoch, Hand pendelt seitlich.
		all.add(EmoteDef.builder("winken", 2000, false, 0, "wave")
				.hold(R_ARM_X, -160)
				.hold(R_ARM_Y, -8)
				.key(R_ARM_Z, 0, 25, 250, 45, 500, 10, 750, 45, 1000, 10, 1250, 45, 1500, 10, 1750, 35, 2000, 25)
				.key(HEAD_Z, 0, 0, 400, -6, 1600, -6, 2000, 0)
				.key(TORSO_ROLL, 0, 0, 400, -3, 1600, -3, 2000, 0)
				.build());

		// Klatschen: beide Arme vor, Hände treffen sich in der Mitte.
		all.add(EmoteDef.builder("klatschen", 2500, false, 0, "clap")
				.hold(R_ARM_X, -70)
				.hold(L_ARM_X, -70)
				.osc(R_ARM_Y, 10, -26, 170, 170, 2210)
				.osc(L_ARM_Y, -10, 26, 170, 170, 2210)
				.osc(HEAD_X, 0, 6, 170, 170, 2210)
				.hold(TORSO_LEAN, 4)
				.build());

		// Jubeln: Arme hoch, kleine Sprünge, Blick nach oben.
		all.add(EmoteDef.builder("jubeln", 2500, false, 0, "cheer")
				.osc(R_ARM_X, -150, -172, 250, 0, 2500)
				.hold(R_ARM_Z, 22)
				.osc(L_ARM_X, -150, -172, 250, 0, 2500)
				.hold(L_ARM_Z, -22)
				.osc(ROOT_Y, 0, 1.5f, 250, 0, 2500)
				.hold(HEAD_X, -18)
				.osc(TORSO_LEAN, 0, -6, 250, 0, 2500)
				.build());

		// Verbeugen: aus der Hüfte vorbeugen, rechte Hand vor den Bauch, linke hinter den Rücken.
		all.add(EmoteDef.builder("verbeugen", 2000, false, 0, "bow")
				.key(TORSO_LEAN, 0, 0, 550, 55, 1300, 55, 1850, 0)
				.key(R_ARM_X, 0, 0, 550, -55, 1300, -55, 1850, 0)
				.key(R_ARM_Y, 0, 0, 550, -40, 1300, -40, 1850, 0)
				.key(L_ARM_X, 0, 0, 550, 35, 1300, 35, 1850, 0)
				.key(L_ARM_Y, 0, 0, 550, -30, 1300, -30, 1850, 0)
				.key(HEAD_X, 0, 0, 550, 20, 1300, 20, 1850, 0)
				.build());

		// Facepalm: rechte Hand vors Gesicht, Kopf gesenkt und schüttelnd.
		all.add(EmoteDef.builder("facepalm", 2000, false, 0, "facepalm")
				.key(R_ARM_X, 0, -30, 300, -133, 1750, -133)
				.hold(R_ARM_Y, -38)
				.key(HEAD_X, 0, 0, 300, 22, 1750, 22, 2000, 0)
				.key(HEAD_Y, 0, 0, 500, 0, 700, -10, 900, 10, 1100, -10, 1300, 10, 1500, 0)
				.hold(TORSO_LEAN, 8)
				.build());

		// Schulterzucken: Schultern hoch, Arme leicht nach außen, Kopf schief.
		all.add(EmoteDef.builder("schulterzucken", 1500, false, 0, "shrug")
				.key(SHOULDERS, 0, 0, 300, 1.5f, 1100, 1.5f, 1500, 0)
				.key(R_ARM_X, 0, 0, 300, -25, 1100, -25, 1500, 0)
				.key(R_ARM_Y, 0, 0, 300, 25, 1100, 25, 1500, 0)
				.key(R_ARM_Z, 0, 0, 300, 28, 1100, 28, 1500, 0)
				.key(L_ARM_X, 0, 0, 300, -25, 1100, -25, 1500, 0)
				.key(L_ARM_Y, 0, 0, 300, -25, 1100, -25, 1500, 0)
				.key(L_ARM_Z, 0, 0, 300, -28, 1100, -28, 1500, 0)
				.key(HEAD_Z, 0, 0, 300, 12, 1100, 12, 1500, 0)
				.key(HEAD_X, 0, 0, 300, -6, 1100, -6, 1500, 0)
				.build());

		// Daumen hoch: rechter Arm nach vorn, zweimal nicken.
		all.add(EmoteDef.builder("daumen_hoch", 1500, false, 0, "thumb")
				.key(R_ARM_X, 0, -40, 350, -85, 1500, -85)
				.hold(R_ARM_Y, -12)
				.hold(R_ARM_Z, 6)
				.key(HEAD_X, 0, 0, 450, 0, 650, 12, 850, 0, 1050, 12, 1250, 0)
				.key(TORSO_LEAN, 0, 0, 350, -4, 1500, -4)
				.build());

		// Tanzen (Schleife 1 s): Arme im Wechsel hoch, Hüfte schwingt, federn.
		all.add(EmoteDef.builder("tanzen", 6000, true, 1000, "dance")
				.key(ROOT_Y, 0, 0, 250, 1.5f, 500, 0, 750, 1.5f)
				.key(TORSO_ROLL, 0, -7, 500, 7)
				.key(TORSO_TWIST, 0, -12, 500, 12)
				.key(R_ARM_X, 0, -155, 500, -35)
				.key(R_ARM_Z, 0, 30, 500, 8)
				.key(L_ARM_X, 0, -35, 500, -155)
				.key(L_ARM_Z, 0, -8, 500, -30)
				.key(R_LEG_Z, 0, 10, 500, 0)
				.key(L_LEG_Z, 0, 0, 500, -10)
				.key(HEAD_Z, 0, 8, 500, -8)
				.key(HEAD_X, 0, 0, 250, 8, 500, 0, 750, 8)
				.build());

		// Salutieren: Hand an die Stirn, Haltung.
		all.add(EmoteDef.builder("salutieren", 2000, false, 0, "salute")
				.key(R_ARM_X, 0, -60, 250, -146, 1600, -146, 1850, -60)
				.key(R_ARM_Y, 0, 0, 250, -34, 1600, -34, 1850, 0)
				.hold(L_ARM_X, 0)
				.hold(L_ARM_Z, -2)
				.hold(R_LEG_X, 0)
				.hold(L_LEG_X, 0)
				.hold(HEAD_X, -6)
				.hold(TORSO_LEAN, -3)
				.build());

		// Luftgitarre (Schleife 0,6 s): links Griffbrett, rechts schlagen, Kopfnicken im Takt.
		all.add(EmoteDef.builder("luftgitarre", 5000, true, 600, "guitar")
				.hold(L_ARM_X, -58)
				.hold(L_ARM_Y, -28)
				.hold(L_ARM_Z, -10)
				.key(R_ARM_X, 0, -28, 150, -55, 300, -28, 450, -55)
				.hold(R_ARM_Y, -42)
				.key(HEAD_X, 0, -8, 300, 24)
				.key(TORSO_LEAN, 0, -8, 300, 6)
				.hold(R_LEG_Z, 9)
				.hold(L_LEG_Z, -9)
				.key(ROOT_Y, 0, 0, 300, -0.8f)
				.build());

		// Redstone-Tanz (Schleife 1,2 s): Roboter-Posen im Takt eines Verstärkers, zackige Übergänge.
		all.add(EmoteDef.builder("redstone_tanz", 6000, true, 1200, "redstone")
				.ease(Track.Ease.SNAP)
				.key(R_ARM_X, 0, -90, 300, 0, 600, -180, 900, -90)
				.key(R_ARM_Z, 0, 0, 300, 90, 600, 10, 900, 0)
				.key(L_ARM_X, 0, 0, 300, -90, 600, -180, 900, -90)
				.key(L_ARM_Z, 0, -90, 300, 0, 600, -10, 900, 0)
				.key(TORSO_TWIST, 0, 0, 300, 20, 600, 0, 900, -20)
				.key(HEAD_Y, 0, 0, 300, 25, 600, 0, 900, -25)
				.key(ROOT_Y, 0, 0, 300, 0, 600, 1.5f, 900, 0)
				.key(R_LEG_Z, 0, 0, 300, 12, 600, 0, 900, 0)
				.key(L_LEG_Z, 0, 0, 300, 0, 600, 0, 900, -12)
				.build());

		for (EmoteDef d : all) BY_ID.put(d.id(), d);
		ALL = Collections.unmodifiableList(all);
	}

	private Emotes() {
	}

	/** Emote zu einer API-ID oder null (unbekannt → ignorieren). */
	public static EmoteDef byId(String id) {
		return id == null ? null : BY_ID.get(id);
	}
}
