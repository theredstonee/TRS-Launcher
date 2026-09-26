package dev.theredstonee.trsclient.core.cosmetic;

/**
 * Was die Tier-Kosmetik über ihren Träger wissen muss – von jeder Version einmal je Bild gefüllt (wiederverwendet,
 * keine Allokation). Geschwindigkeiten in Blöcken je Tick ({@code x − xo}), Winkel in Grad wie Minecraft.
 */
public final class Wearer {
	public int entityId;
	/** Waagerechte Geschwindigkeit (Blöcke/Tick). */
	public float speed;
	/** Senkrechte Geschwindigkeit (Blöcke/Tick, + = nach oben). */
	public float vy;
	public boolean onGround;
	public boolean sneaking;
	public boolean sprinting;
	public boolean inWater;
	/** Blickrichtung des Kopfes in der Welt (yHeadRot, Grad). */
	public float headYaw;
	/** Neigung des Kopfes (xRot, Grad, + = nach unten). */
	public float headPitch;
	/** Spielt gerade ein Emote. */
	public boolean emote;
	/** Trägt einen Helm (Ente sitzt dann etwas höher). */
	public boolean helmet;

	public Wearer set(int entityId, float speed, float vy, boolean onGround, boolean sneaking, boolean sprinting,
			boolean inWater, float headYaw, float headPitch, boolean emote, boolean helmet) {
		this.entityId = entityId;
		this.speed = speed;
		this.vy = vy;
		this.onGround = onGround;
		this.sneaking = sneaking;
		this.sprinting = sprinting;
		this.inWater = inWater;
		this.headYaw = headYaw;
		this.headPitch = headPitch;
		this.emote = emote;
		this.helmet = helmet;
		return this;
	}
}
