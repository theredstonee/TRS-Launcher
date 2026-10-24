package dev.theredstonee.trsclient.core.cosmetic.pet;

import dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2Renderer;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Hat;

/**
 * Ein Begleiter am Boden für ein Bild: Teil samt Zustand ({@link #hat}), Lage relativ zur Kamera, Licht am Standort
 * und die Umrechnung der Vierecke aus {@link CosmeticV2Renderer#emit} (ModelPart-Raum: x/16, −y/16, −z/16) in
 * kamerazentrierte Weltkoordinaten – so braucht kein Minecraft-Baum eigene Dreh-/Matrix-Aufrufe, die Pose bleibt die
 * der Welt (Kamera am Ursprung). Wiederverwendet: nur im Render-Thread, nur bis zum nächsten Bild gültig.
 *
 * @param <T> Textur-Kennung der Version
 */
public final class PetDraw<T> {
	public V2Hat<T> hat;
	public Pet pet;
	/** Ursprung des Begleiters (Boden unter der Mitte) relativ zur Kamera, in Blöcken. */
	public double dx, dy, dz;
	/** Gieren wie Minecraft (0 = Süden/+z, 90 = Westen/−x). */
	public double yaw;
	/** Licht am Standort, gepackt wie Minecraft ({@code (himmel << 20) | (block << 4)}). */
	public int light;
	/** Kamera im Anhängepunkt-Raum des Modells (Einheiten = Pixel) – für Höfe und Entfernungs-Stufe. */
	public final double[] eye = new double[3];
	private double cos, sin;

	/** Lage setzen; berechnet {@link #eye}. */
	public void set(double dx, double dy, double dz, double yawDeg) {
		this.dx = dx;
		this.dy = dy;
		this.dz = dz;
		this.yaw = yawDeg;
		double h = Math.toRadians(yawDeg);
		cos = Math.cos(h);
		sin = Math.sin(h);
		// Kamera relativ zum Ursprung: c = (−dx, −dy, −dz); Modell: x = links (cos, 0, sin), z = vorne (−sin, 0, cos)
		double cx = -dx, cy = -dy, cz = -dz;
		eye[0] = 16.0 * (cx * cos + cz * sin);
		eye[1] = 16.0 * cy;
		eye[2] = 16.0 * (-cx * sin + cz * cos);
	}

	/** Vierecke (ModelPart-Raum) in kamerazentrierte Welt-Koordinaten umrechnen und an {@code out} geben. */
	public CosmeticV2Renderer.VertexSink wrap(final CosmeticV2Renderer.VertexSink out) {
		final double c = cos, s = sin, ox = dx, oy = dy, oz = dz;
		return new CosmeticV2Renderer.VertexSink() {
			@Override
			public void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, int argb) {
				// Modell (x, y, z)/16 = (x', −y', −z'): Welt = x·links + y·oben + z·vorne
				float wx = (float) (ox + x * c + z * s);
				float wy = (float) (oy - y);
				float wz = (float) (oz + x * s - z * c);
				float mx = (float) (nx * c + nz * s);
				float my = -ny;
				float mz = (float) (nx * s - nz * c);
				out.vertex(wx, wy, wz, u, v, mx, my, mz, argb);
			}
		};
	}

	/** Blocklicht (0…15) aus {@link #light}. */
	public int blockLight() {
		return (light >> 4) & 0xF;
	}

	/** Himmelslicht (0…15) aus {@link #light}. */
	public int skyLight() {
		return (light >> 20) & 0xF;
	}
}
