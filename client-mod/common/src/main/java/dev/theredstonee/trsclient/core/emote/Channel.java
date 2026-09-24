package dev.theredstonee.trsclient.core.emote;

/**
 * Kanäle einer Emote-Pose (ein {@code float[COUNT]} je Zeitpunkt).
 *
 * <p>Rotationen in Bogenmaß, Verschiebungen in Modell-Pixeln (1/16 Block). Die Winkel folgen Minecrafts
 * Modellraum (y nach unten, Gesicht Richtung −z, rechter Arm bei x = −5): {@code xRot} negativ hebt einen Arm
 * nach vorn, {@code zRot} positiv hebt den <em>rechten</em> Arm zur Seite (links negativ), {@code yRot} negativ
 * dreht den nach vorn gestreckten rechten Arm zur Körpermitte (links positiv).
 *
 * <ul>
 *   <li>{@code TORSO_*}: Oberkörper um die Hüfte (Vorbeugen, Drehen, Seitneigen) – Kopf und Arme gehen mit.</li>
 *   <li>{@code ROOT_*}: ganzes Modell verschieben ({@code ROOT_Y} positiv = nach oben, {@code ROOT_Z} positiv = nach vorn).</li>
 *   <li>{@code HEAD_*}: zusätzlich zur Blickrichtung des Spielers.</li>
 *   <li>Arme/Beine: absolute Winkel relativ zum Oberkörper bzw. zur Hüfte. Hat ein Emote für ein Glied keine
 *       Spur, bleibt dessen Vanilla-Haltung (Arme gehen dann trotzdem mit dem Oberkörper mit).</li>
 *   <li>{@code SHOULDERS}: Schultern hochziehen (Pixel, positiv = nach oben).</li>
 * </ul>
 */
public final class Channel {
	public static final int TORSO_LEAN = 0;
	public static final int TORSO_TWIST = 1;
	public static final int TORSO_ROLL = 2;
	public static final int ROOT_X = 3;
	public static final int ROOT_Y = 4;
	public static final int ROOT_Z = 5;
	public static final int HEAD_X = 6;
	public static final int HEAD_Y = 7;
	public static final int HEAD_Z = 8;
	public static final int R_ARM_X = 9;
	public static final int R_ARM_Y = 10;
	public static final int R_ARM_Z = 11;
	public static final int L_ARM_X = 12;
	public static final int L_ARM_Y = 13;
	public static final int L_ARM_Z = 14;
	public static final int R_LEG_X = 15;
	public static final int R_LEG_Y = 16;
	public static final int R_LEG_Z = 17;
	public static final int L_LEG_X = 18;
	public static final int L_LEG_Y = 19;
	public static final int L_LEG_Z = 20;
	public static final int SHOULDERS = 21;
	public static final int COUNT = 22;

	/** Glieder, deren Vanilla-Haltung ein Emote übernimmt (Bitmaske). */
	public static final int MASK_RIGHT_ARM = 1;
	public static final int MASK_LEFT_ARM = 2;
	public static final int MASK_RIGHT_LEG = 4;
	public static final int MASK_LEFT_LEG = 8;

	private Channel() {
	}

	/** Verschiebung in Pixeln (sonst Winkel). */
	public static boolean isOffset(int channel) {
		return channel == ROOT_X || channel == ROOT_Y || channel == ROOT_Z || channel == SHOULDERS;
	}

	/** Zu welchem Glied gehört der Kanal (Bit aus MASK_*), 0 = Oberkörper/Kopf/Modell. */
	public static int limb(int channel) {
		if (channel >= R_ARM_X && channel <= R_ARM_Z) return MASK_RIGHT_ARM;
		if (channel >= L_ARM_X && channel <= L_ARM_Z) return MASK_LEFT_ARM;
		if (channel >= R_LEG_X && channel <= R_LEG_Z) return MASK_RIGHT_LEG;
		if (channel >= L_LEG_X && channel <= L_LEG_Z) return MASK_LEFT_LEG;
		return 0;
	}
}
