package dev.theredstonee.trsclient.core.cosmetic;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Eine Modell-Vorlage der TRS API (API.md §11): Würfel im Anhängepunkt-Raum (+x Spieler-links, +y oben,
 * +z vorne, Einheit = Skin-Pixel), Vanilla-Box-UV-Netz, Texturmaße bei scale 1. Unveränderlich.
 */
public final class CosmeticModel {
	/** Animation eines Würfels (API.md §11.4 / §11.4.1). */
	public enum Anim {
		NONE, FLAP, BOB, SPIN, LOOK, QUACK, BLINK, WING;

		static Anim of(String s) {
			if (s == null) return NONE;
			switch (s) {
				case "flap": return FLAP;
				case "bob": return BOB;
				case "spin": return SPIN;
				case "look": return LOOK;
				case "quack": return QUACK;
				case "blink": return BLINK;
				case "wing": return WING;
				default: return null;
			}
		}
	}

	public static final class Cube {
		public final float x0, y0, z0, x1, y1, z1;
		/** Linke obere Ecke des UV-Netzes bei scale 1. */
		public final int u, v;
		public final Anim anim;
		/** Drehpunkt (nur bei Animationen, die einen brauchen), sonst Würfelmitte. */
		public final float px, py, pz;

		Cube(float[] from, float[] to, int u, int v, Anim anim, float[] pivot) {
			this.x0 = Math.min(from[0], to[0]);
			this.y0 = Math.min(from[1], to[1]);
			this.z0 = Math.min(from[2], to[2]);
			this.x1 = Math.max(from[0], to[0]);
			this.y1 = Math.max(from[1], to[1]);
			this.z1 = Math.max(from[2], to[2]);
			this.u = u;
			this.v = v;
			this.anim = anim;
			if (pivot != null) {
				px = pivot[0];
				py = pivot[1];
				pz = pivot[2];
			} else {
				px = (x0 + x1) * 0.5f;
				py = (y0 + y1) * 0.5f;
				pz = (z0 + z1) * 0.5f;
			}
		}

		/** Mitte des Würfels auf x (für die Seite eines Flügels). */
		public float centerX() {
			return (x0 + x1) * 0.5f;
		}
	}

	public final String id;
	public final int textureWidth;
	public final int textureHeight;
	public final List<Cube> cubes;
	/** Tier-Rig ({@code duck}) oder null. */
	public final String rig;
	/** Drehpunkt des Tierkopfs (nur mit Rig). */
	public final float neckX, neckY, neckZ;

	private CosmeticModel(String id, int tw, int th, List<Cube> cubes, String rig, float[] neck) {
		this.id = id;
		this.textureWidth = tw;
		this.textureHeight = th;
		this.cubes = Collections.unmodifiableList(cubes);
		this.rig = rig;
		this.neckX = neck == null ? 0 : neck[0];
		this.neckY = neck == null ? 0 : neck[1];
		this.neckZ = neck == null ? 0 : neck[2];
	}

	// --- JSON (nur die Felder, die die Mod braucht) ---

	private static final class Json {
		String id;
		String kind;
		String slot;
		Integer textureWidth;
		Integer textureHeight;
		RigJson rig;
		List<CubeJson> cubes;
	}

	private static final class RigJson {
		String type;
		float[] neck;
	}

	private static final class CubeJson {
		float[] from;
		float[] to;
		int[] uv;
		String attach;
		float[] pivot;
		String anim;
	}

	/** Liest eine Vorlage und prüft sie streng (nur Kopf-Modelle); ungültig → IllegalArgumentException. */
	public static CosmeticModel parse(String json) {
		Json j = new Gson().fromJson(json, Json.class);
		if (j == null || j.id == null || !"model".equals(j.kind) || !"hat".equals(j.slot)) {
			throw new IllegalArgumentException("keine Kopf-Modellvorlage");
		}
		int tw = j.textureWidth == null ? 0 : j.textureWidth;
		int th = j.textureHeight == null ? 0 : j.textureHeight;
		if (tw < 8 || tw > 128 || th < 8 || th > 128 || tw != th * 2) throw new IllegalArgumentException("Texturmaße " + tw + "x" + th);
		if (j.cubes == null || j.cubes.isEmpty() || j.cubes.size() > 32) throw new IllegalArgumentException("Würfel");
		String rig = null;
		float[] neck = null;
		if (j.rig != null) {
			if (!"duck".equals(j.rig.type) || !vec(j.rig.neck)) throw new IllegalArgumentException("Rig");
			rig = j.rig.type;
			neck = j.rig.neck;
		}
		List<Cube> cubes = new ArrayList<>();
		for (CubeJson c : j.cubes) {
			if (c == null || !vec(c.from) || !vec(c.to) || c.uv == null || c.uv.length != 2 || !"head".equals(c.attach)) {
				throw new IllegalArgumentException("Würfel");
			}
			if (c.pivot != null && !vec(c.pivot)) throw new IllegalArgumentException("Drehpunkt");
			Anim anim = Anim.of(c.anim);
			if (anim == null) throw new IllegalArgumentException("Animation " + c.anim);
			if (rig == null && (anim == Anim.LOOK || anim == Anim.QUACK || anim == Anim.BLINK || anim == Anim.WING)) {
				throw new IllegalArgumentException("Animation ohne Rig");
			}
			if ((anim == Anim.QUACK || anim == Anim.WING || anim == Anim.FLAP || anim == Anim.SPIN) && c.pivot == null) {
				throw new IllegalArgumentException("Drehpunkt fehlt");
			}
			if (c.uv[0] < 0 || c.uv[1] < 0 || c.uv[0] > 255 || c.uv[1] > 255) throw new IllegalArgumentException("UV");
			cubes.add(new Cube(c.from, c.to, c.uv[0], c.uv[1], anim, c.pivot));
		}
		return new CosmeticModel(j.id, tw, th, cubes, rig, neck);
	}

	private static boolean vec(float[] v) {
		if (v == null || v.length != 3) return false;
		for (float f : v) if (Float.isNaN(f) || f < -64 || f > 64) return false;
		return true;
	}
}
