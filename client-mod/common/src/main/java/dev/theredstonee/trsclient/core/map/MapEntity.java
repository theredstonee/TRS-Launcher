package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.ui.TextureRef;

import java.util.UUID;

/** Ein Spieler oder eine Kreatur auf der Karte (Momentaufnahme je Tick, wird wiederverwendet). */
public final class MapEntity {
	public static final int PLAYER = 0;
	public static final int HOSTILE = 1;
	public static final int PASSIVE = 2;

	public int type;
	/** Position im letzten und im aktuellen Tick (für flüssige Bewegung dazwischen). */
	public double prevX, prevZ, x, z, y;
	public float yaw;
	/** Nur Spieler: Kennung, Name, Skin-Textur (null = Standard). */
	public UUID uuid;
	public String name;
	public TextureRef skin;
	/** TRS-Freund? (setzt die Karte selbst) */
	public boolean friend;

	public MapEntity set(int type, double prevX, double prevZ, double x, double y, double z, float yaw) {
		this.type = type;
		this.prevX = prevX;
		this.prevZ = prevZ;
		this.x = x;
		this.y = y;
		this.z = z;
		this.yaw = yaw;
		this.uuid = null;
		this.name = null;
		this.skin = null;
		this.friend = false;
		return this;
	}

	public double lerpX(float alpha) {
		return prevX + (x - prevX) * alpha;
	}

	public double lerpZ(float alpha) {
		return prevZ + (z - prevZ) * alpha;
	}
}
