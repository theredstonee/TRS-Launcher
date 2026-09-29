package dev.theredstonee.trsclient.core.cosmetic.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ein Kopf-Kosmetik-Modell im TRS-Format v2 (Studio: {@code cosmetic-format.md}, Referenz {@code cosmetic-format.mjs}):
 * Knochen-Baum mit Drehpunkt/Drehung (ZYX, Grad), Würfel im Modellraum (+x Spieler-links, +y oben, +z vorne,
 * 1 Einheit = 1 Skin-Pixel) mit UV je Fläche, Materialien, Leucht-Höfe und Animationsspuren. Unveränderlich.
 *
 * <p>{@link #parse} prüft genau wie {@code validateModel} der Referenz (Port – gleiche Grenzen, gleiche Regeln);
 * ein ungültiges Modell wird nie gezeichnet.
 */
public final class CosmeticV2 {
	public static final int FORMAT = 2;

	/** Flächen in fester Reihenfolge (wie {@code FACES} der Referenz). */
	public static final String[] FACES = { "north", "south", "east", "west", "up", "down" };
	public static final int NORTH = 0, SOUTH = 1, EAST = 2, WEST = 3, UP = 4, DOWN = 5;
	/** Außennormale je Fläche im Modellraum. */
	static final float[][] FACE_NORMAL = { { 0, 0, -1 }, { 0, 0, 1 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 } };

	public static final int CUTOUT = 0, EMISSIVE = 1, TRANSLUCENT = 2;
	static final String[] MATERIALS = { "cutout", "emissive", "translucent" };
	public static final int ROTATION = 0, POSITION = 1, SCALE = 2;
	static final String[] CHANNELS = { "rotation", "position", "scale" };
	public static final int LINEAR = 0, SMOOTH = 1, STEP = 2;
	static final String[] INTERPOLATIONS = { "linear", "smooth", "step" };
	static final String[] DRIVERS = { "idle", "walk", "sneak", "jump", "air" };

	/** Grenzen (§10). */
	public static final int MAX_CUBES = 64, MAX_BONES = 32, MAX_TEXTURE_EDGE = 1024, MAX_STRIP = 4096, MAX_FRAMES = 16,
			MAX_ANIMATIONS = 8, MAX_TRACKS = 32, MAX_KEYS = 64, MAX_HALOS = 16, MAX_LENGTH_MS = 60000;
	static final float MAX_COORD = 48f, MAX_SIZE = 32f, MAX_INFLATE = 1f, GRID = 0.125f;
	/** Größte Modell-Datei, die gelesen wird. */
	public static final int MAX_JSON_BYTES = 512 * 1024;

	private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");
	private static final Pattern FILE = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,63}\\.png$");
	private static final Pattern COLOR = Pattern.compile("^#[0-9a-f]{6}$");

	public static final class Bone {
		public final String id;
		/** Index des Eltern-Knochens (steht immer davor) oder −1. */
		public final int parent;
		public final float px, py, pz;
		public final float rx, ry, rz;

		Bone(String id, int parent, float[] pivot, float[] rot) {
			this.id = id;
			this.parent = parent;
			px = pivot[0];
			py = pivot[1];
			pz = pivot[2];
			rx = rot[0];
			ry = rot[1];
			rz = rot[2];
		}
	}

	public static final class Face {
		public final float u0, v0, u1, v1;
		/** 0/90/180/270 im Uhrzeigersinn. */
		public final int rotation;
		public final int material;

		Face(float[] uv, int rotation, int material) {
			u0 = uv[0];
			v0 = uv[1];
			u1 = uv[2];
			v1 = uv[3];
			this.rotation = rotation;
			this.material = material;
		}
	}

	public static final class Cube {
		public final String id;
		public final int bone;
		public final float x0, y0, z0, x1, y1, z1;
		public final float inflate;
		/** Je Fläche ({@link #FACES}) oder null = nicht zeichnen. */
		public final Face[] faces;

		Cube(String id, int bone, float[] from, float[] to, float inflate, Face[] faces) {
			this.id = id;
			this.bone = bone;
			x0 = from[0];
			y0 = from[1];
			z0 = from[2];
			x1 = to[0];
			y1 = to[1];
			z1 = to[2];
			this.inflate = inflate;
			this.faces = faces;
		}
	}

	public static final class Track {
		public final int bone;
		public final int channel;
		/** Standard-Interpolation der Spur. */
		public final int interpolation;
		public final int[] t;
		public final float[][] v;
		/** Interpolation des Stücks bis zu diesem Schlüssel (−1 = die der Spur). */
		public final int[] keyInterpolation;

		Track(int bone, int channel, int interpolation, int[] t, float[][] v, int[] keyInterpolation) {
			this.bone = bone;
			this.channel = channel;
			this.interpolation = interpolation;
			this.t = t;
			this.v = v;
			this.keyInterpolation = keyInterpolation;
		}
	}

	public static final class Animation {
		public final String id;
		/** Treiber-Index ({@code idle} = 0; nur der wird im Moment abgespielt). */
		public final int driver;
		public final int lengthMs;
		public final boolean loop;
		public final int offsetMs;
		public final Track[] tracks;

		Animation(String id, int driver, int lengthMs, boolean loop, int offsetMs, Track[] tracks) {
			this.id = id;
			this.driver = driver;
			this.lengthMs = lengthMs;
			this.loop = loop;
			this.offsetMs = offsetMs;
			this.tracks = tracks;
		}
	}

	public static final class Halo {
		public final int bone;
		public final float x, y, z;
		public final float size;
		/** RGB (0xRRGGBB). */
		public final int color;
		public final float intensity;
		/** Leuchtende Seite (normiert) oder null. */
		public final float[] normal;
		public final boolean pulse;
		public final int periodMs;
		public final float min, max;
		public final int phaseMs;

		Halo(int bone, float[] pos, float size, int color, float intensity, float[] normal, boolean pulse, int periodMs,
				float min, float max, int phaseMs) {
			this.bone = bone;
			x = pos[0];
			y = pos[1];
			z = pos[2];
			this.size = size;
			this.color = color;
			this.intensity = intensity;
			this.normal = normal;
			this.pulse = pulse;
			this.periodMs = periodMs;
			this.min = min;
			this.max = max;
			this.phaseMs = phaseMs;
		}
	}

	public final String id;
	public final String name;
	/** Texturmaße in Einheiten (Texel bei Faktor 1). */
	public final int textureWidth, textureHeight;
	/** Texel je Einheit (1, 2, 4, 8, 16). */
	public final int scale;
	public final int frames, frameTimeMs;
	/** Leucht-Schicht vorhanden? */
	public final boolean glow;
	public final int glowFrames, glowFrameTimeMs;
	public final List<Bone> bones;
	public final List<Cube> cubes;
	public final List<Animation> animations;
	public final List<Halo> halos;
	/** Anzahl gezeichneter Flächen. */
	public final int faceCount;
	/** Welche Materialien vorkommen (Bitmaske 1 &lt;&lt; Material). */
	public final int materials;

	private CosmeticV2(String id, String name, int tw, int th, int scale, int frames, int frameTimeMs, boolean glow,
			int glowFrames, int glowFrameTimeMs, List<Bone> bones, List<Cube> cubes, List<Animation> animations,
			List<Halo> halos) {
		this.id = id;
		this.name = name;
		this.textureWidth = tw;
		this.textureHeight = th;
		this.scale = scale;
		this.frames = frames;
		this.frameTimeMs = frameTimeMs;
		this.glow = glow;
		this.glowFrames = glowFrames;
		this.glowFrameTimeMs = glowFrameTimeMs;
		this.bones = Collections.unmodifiableList(bones);
		this.cubes = Collections.unmodifiableList(cubes);
		this.animations = Collections.unmodifiableList(animations);
		this.halos = Collections.unmodifiableList(halos);
		int n = 0;
		int mats = 0;
		for (Cube c : cubes) {
			for (Face f : c.faces) {
				if (f == null) continue;
				n++;
				mats |= 1 << f.material;
			}
		}
		this.faceCount = n;
		this.materials = mats;
	}

	/** Breite eines Bildes der Grundtextur in echten Pixeln. */
	public int pixelWidth() {
		return textureWidth * scale;
	}

	/** Höhe eines Bildes (Grundtextur und Leucht-Schicht) in echten Pixeln. */
	public int pixelHeight() {
		return textureHeight * scale;
	}

	public boolean has(int material) {
		return (materials & (1 << material)) != 0;
	}

	// =====================================================================================================
	// Lesen + Prüfen
	// =====================================================================================================

	/** Prüf-Ergebnis (wie {@code { ok, errors, warnings }} der Referenz). */
	public static final class Check {
		public final List<String> errors = new ArrayList<String>();
		public final List<String> warnings = new ArrayList<String>();
		/** Das Modell, wenn es gültig ist, sonst null. */
		public CosmeticV2 model;

		public boolean ok() {
			return errors.isEmpty();
		}

		void err(String path, String msg) {
			errors.add(path + ": " + msg);
		}
	}

	/**
	 * Liest und prüft ein Modell; ungültig → {@link IllegalArgumentException} mit den ersten Fehlern.
	 */
	public static CosmeticV2 parse(String json) {
		Check c = check(json, -1, -1, -1, -1);
		if (!c.ok()) {
			StringBuilder sb = new StringBuilder("ungültiges Kosmetik-Modell");
			for (int i = 0; i < c.errors.size() && i < 3; i++) sb.append(i == 0 ? ": " : "; ").append(c.errors.get(i));
			throw new IllegalArgumentException(sb.toString());
		}
		return c.model;
	}

	/**
	 * Prüft ein Modell (Port von {@code validateModel}). Bildmaße in echten Pixeln (−1 = nicht prüfen):
	 * Grundtextur {@code texW×texH} und Leucht-Streifen {@code glowW×glowH}.
	 */
	public static Check check(String json, int texW, int texH, int glowW, int glowH) {
		Check out = new Check();
		JsonElement root;
		try {
			if (json == null || json.length() > MAX_JSON_BYTES) throw new IllegalArgumentException("zu groß");
			root = new JsonParser().parse(json);
		} catch (RuntimeException e) {
			out.err("json", "kein gültiges JSON");
			return out;
		}
		if (root == null || !root.isJsonObject()) {
			out.errors.add("Modell ist kein Objekt");
			return out;
		}
		JsonObject m = root.getAsJsonObject();
		if (intOr(m.get("format"), -1) != FORMAT || !isInt(m.get("format"))) out.err("format", "muss 2 sein");
		String id = str(m.get("id"));
		if (id == null || !ID.matcher(id).matches()) out.err("id", "a–z, 0–9, _ (Anfang Buchstabe), max. 40");
		String name = str(m.get("name"));
		if (name == null || name.trim().isEmpty() || name.length() > 60) out.err("name", "1–60 Zeichen");
		if (!"hat".equals(str(m.get("slot")))) out.err("slot", "eins von hat");
		if (!"head".equals(str(m.get("attach")))) out.err("attach", "eins von head");

		// Textur
		JsonObject tex = obj(m.get("texture"));
		if (tex == null) tex = new JsonObject();
		boolean texOk = checkImage(out, "texture", tex, true);
		JsonObject glow = null;
		JsonElement glowEl = m.get("glow");
		if (glowEl != null && !glowEl.isJsonNull()) {
			glow = obj(glowEl);
			if (glow == null) {
				out.err("glow", "Objekt erwartet");
			} else {
				checkImage(out, "glow", glow, false);
				JsonElement blend = glow.get("blend");
				if (blend != null && !blend.isJsonNull() && !"additive".equals(str(blend))) out.err("glow.blend", "nur additive");
			}
		}
		int tw = intOr(tex.get("width"), 0);
		int th = intOr(tex.get("height"), 0);
		int scale = intOr(tex.get("scale"), 1);
		int frames = intOr(tex.get("frames"), 1);
		int glowFrames = glow == null ? 0 : intOr(glow.get("frames"), 1);
		if (texOk) {
			int fh = th * scale;
			if ((long) fh * frames > MAX_STRIP) out.err("texture", "Streifen höher als " + MAX_STRIP + " px");
			if (glow != null && (long) fh * glowFrames > MAX_STRIP) {
				out.err("glow", "Streifen höher als " + MAX_STRIP + " px (weniger Frames oder breiteres Blatt)");
			}
		}
		if (texW >= 0 && texOk) {
			int w = tw * scale;
			int h = th * scale * frames;
			if (texW != w || texH != h) out.err("texture", "Bild ist " + texW + "×" + texH + ", erwartet " + w + "×" + h);
		}
		if (glowW >= 0 && glow != null && texOk) {
			int w = tw * scale;
			int h = th * scale * glowFrames;
			if (glowW != w || glowH != h) out.err("glow", "Bild ist " + glowW + "×" + glowH + ", erwartet " + w + "×" + h);
		}

		// Knochen
		Map<String, Integer> boneIndex = new HashMap<String, Integer>();
		List<Bone> bones = new ArrayList<Bone>();
		JsonArray bonesArr = arr(m.get("bones"));
		if (bonesArr == null || bonesArr.size() < 1) out.err("bones", "mindestens ein Knochen");
		else if (bonesArr.size() > MAX_BONES) out.err("bones", "höchstens " + MAX_BONES);
		if (bonesArr != null) {
			for (int i = 0; i < bonesArr.size(); i++) {
				String p = "bones[" + i + "]";
				JsonObject b = obj(bonesArr.get(i));
				String bid = b == null ? null : str(b.get("id"));
				if (bid == null || !ID.matcher(bid).matches()) {
					out.err(p, "ungültige id");
					continue;
				}
				if (boneIndex.containsKey(bid)) out.err(p, "id " + bid + " doppelt");
				JsonElement parentEl = b.get("parent");
				int parent = -1;
				if (parentEl != null && !parentEl.isJsonNull()) {
					String ps = str(parentEl);
					Integer pi = ps == null ? null : boneIndex.get(ps);
					if (pi == null) out.err(p, "Eltern-Knochen " + (ps == null ? parentEl : ps) + " muss vorher stehen");
					else parent = pi;
				}
				float[] pivot = vec(b.get("pivot"));
				if (pivot == null || abs3(pivot) > MAX_COORD) out.err(p, "pivot [x,y,z] fehlt oder zu groß");
				JsonElement rotEl = b.get("rotation");
				float[] rot = new float[3];
				if (rotEl != null && !rotEl.isJsonNull()) {
					float[] r = vec(rotEl);
					if (r == null || abs3(r) > 360f) out.err(p, "rotation [x,y,z] in Grad (±360)");
					else rot = r;
				}
				// Doppelte id: der erste Eintrag bleibt maßgeblich (Modell ist ohnehin ungültig).
				if (!boneIndex.containsKey(bid)) boneIndex.put(bid, bones.size());
				bones.add(new Bone(bid, parent, pivot == null ? new float[3] : pivot, rot));
			}
		}

		// Würfel
		List<Cube> cubes = new ArrayList<Cube>();
		JsonArray cubesArr = arr(m.get("cubes"));
		if (cubesArr == null || cubesArr.size() < 1) out.err("cubes", "mindestens ein Würfel");
		else if (cubesArr.size() > MAX_CUBES) out.err("cubes", "höchstens " + MAX_CUBES + " (hat " + cubesArr.size() + ")");
		Set<String> cubeIds = new HashSet<String>();
		if (cubesArr != null) {
			for (int i = 0; i < cubesArr.size(); i++) {
				JsonObject c = obj(cubesArr.get(i));
				String cid = c == null ? null : str(c.get("id"));
				String p = "cubes[" + i + "]" + (cid != null ? " (" + cid + ")" : "");
				if (c == null) {
					out.err(p, "Objekt erwartet");
					continue;
				}
				JsonElement cidEl = c.get("id");
				if (cidEl != null && !cidEl.isJsonNull()) {
					if (cid == null || !ID.matcher(cid).matches()) out.err(p, "ungültige id");
					else if (cubeIds.contains(cid)) out.err(p, "id doppelt");
					if (cid != null) cubeIds.add(cid);
				}
				String boneId = str(c.get("bone"));
				Integer bone = boneId == null ? null : boneIndex.get(boneId);
				if (bone == null) out.err(p, "unbekannter Knochen " + boneId);
				float[] from = vec(c.get("from"));
				float[] to = vec(c.get("to"));
				if (from == null || to == null) {
					out.err(p, "from/to [x,y,z] fehlen");
					continue;
				}
				for (int a = 0; a < 3; a++) {
					float size = to[a] - from[a];
					if (size < 0 || size > MAX_SIZE) out.err(p, "Kante " + "xyz".charAt(a) + " muss 0…32 sein");
					if (Math.abs(from[a]) > MAX_COORD || Math.abs(to[a]) > MAX_COORD) out.err(p, "außerhalb ±48");
					if (!onGrid(from[a], GRID) || !onGrid(to[a], GRID)) out.err(p, "Koordinaten im 0.125er-Raster");
				}
				int flatCount = 0;
				int flatAxis = -1;
				for (int a = 0; a < 3; a++) {
					if (to[a] == from[a]) {
						flatCount++;
						flatAxis = a;
					}
				}
				if (flatCount > 1) out.err(p, "höchstens eine Achse darf die Dicke 0 haben");
				float inflate = 0f;
				JsonElement infEl = c.get("inflate");
				if (infEl != null && !infEl.isJsonNull()) {
					Float inf = num(infEl);
					if (inf == null || Math.abs(inf) > MAX_INFLATE) out.err(p, "inflate ±1");
					else inflate = inf;
				}
				int material = CUTOUT;
				JsonElement matEl = c.get("material");
				if (matEl != null && !matEl.isJsonNull()) {
					int mi = index(MATERIALS, str(matEl));
					if (mi < 0) out.err(p, "material eins von cutout, emissive, translucent");
					else material = mi;
				}
				JsonObject facesObj = obj(c.get("faces"));
				if (facesObj == null) {
					out.err(p, "faces fehlt");
					continue;
				}
				for (Entry<String, JsonElement> e : facesObj.entrySet()) {
					if (index(FACES, e.getKey()) < 0) out.err(p, "unbekannte Fläche " + e.getKey());
				}
				Face[] faces = new Face[6];
				for (int fi = 0; fi < 6; fi++) {
					String face = FACES[fi];
					JsonElement fe = facesObj.get(face);
					if (fe == null || fe.isJsonNull()) continue;
					String fp = p + ".faces." + face;
					float[] fs = faceSize(from, to, fi);
					if (fs[0] == 0 || fs[1] == 0) {
						out.err(fp, "Fläche hat keine Ausdehnung – null setzen");
						continue;
					}
					if (flatCount == 1) {
						String[] pair = new String[][] { { "west", "east" }, { "down", "up" }, { "north", "south" } }[flatAxis];
						JsonElement other = facesObj.get(pair[1]);
						if (face.equals(pair[0]) && other != null && !other.isJsonNull()) {
							out.err(fp, "flacher Würfel: nur " + pair[1] + " angeben (wird beidseitig gezeichnet)");
						}
					}
					JsonObject f = obj(fe);
					float[] uv = f == null ? null : nums(f.get("uv"), 4);
					if (uv == null) {
						out.err(fp, "uv [u0,v0,u1,v1] fehlt");
						continue;
					}
					if (Math.min(uv[0], uv[2]) < 0 || Math.max(uv[0], uv[2]) > tw || Math.min(uv[1], uv[3]) < 0
							|| Math.max(uv[1], uv[3]) > th) {
						out.err(fp, "uv außerhalb der Textur");
					}
					boolean grid = true;
					for (float v : uv) if (!onGrid(v * scale, 1f)) grid = false;
					if (!grid) out.warnings.add(fp + ": uv liegt nicht auf dem Texel-Raster (Faktor " + scale + ")");
					if (uv[0] == uv[2] || uv[1] == uv[3]) out.err(fp, "uv-Rechteck ist leer");
					int rotation = 0;
					JsonElement rotEl = f.get("rotation");
					if (rotEl != null && !rotEl.isJsonNull()) {
						int r = isInt(rotEl) ? intOr(rotEl, -1) : -1;
						if (r != 0 && r != 90 && r != 180 && r != 270) out.err(fp, "rotation 0/90/180/270");
						else rotation = r;
					}
					int fm = material;
					JsonElement fmEl = f.get("material");
					if (fmEl != null && !fmEl.isJsonNull()) {
						int mi = index(MATERIALS, str(fmEl));
						if (mi < 0) out.err(fp, "material eins von cutout, emissive, translucent");
						else fm = mi;
					}
					faces[fi] = new Face(uv, rotation, fm);
				}
				cubes.add(new Cube(cid, bone == null ? 0 : bone, from, to, inflate, faces));
			}
		}

		// Animationen
		List<Animation> animations = new ArrayList<Animation>();
		JsonElement animsEl = m.get("animations");
		JsonArray anims = null;
		if (animsEl != null && !animsEl.isJsonNull()) {
			anims = arr(animsEl);
			if (anims == null) out.err("animations", "Liste erwartet");
			else if (anims.size() > MAX_ANIMATIONS) out.err("animations", "höchstens " + MAX_ANIMATIONS);
		}
		if (anims != null) {
			for (int i = 0; i < anims.size(); i++) {
				String p = "animations[" + i + "]";
				JsonObject a = obj(anims.get(i));
				if (a == null) {
					out.err(p, "ungültige id");
					continue;
				}
				String aid = str(a.get("id"));
				if (aid == null || !ID.matcher(aid).matches()) out.err(p, "ungültige id");
				JsonElement drvEl = a.get("driver");
				int driver = drvEl == null || drvEl.isJsonNull() ? 0 : index(DRIVERS, str(drvEl));
				if (driver < 0) out.err(p, "driver eins von idle, walk, sneak, jump, air");
				JsonElement lenEl = a.get("lengthMs");
				int length = isInt(lenEl) ? intOr(lenEl, 0) : 0;
				if (!isInt(lenEl) || length < 50 || length > MAX_LENGTH_MS) out.err(p, "lengthMs 50…" + MAX_LENGTH_MS);
				JsonElement offEl = a.get("offsetMs");
				int offset = 0;
				if (offEl != null && !offEl.isJsonNull()) {
					if (!isInt(offEl)) out.err(p, "offsetMs ganzzahlig");
					else offset = intOr(offEl, 0);
				}
				JsonElement loopEl = a.get("loop");
				boolean loop = !(loopEl != null && loopEl.isJsonPrimitive() && loopEl.getAsJsonPrimitive().isBoolean()
						&& !loopEl.getAsBoolean());
				JsonArray tracksArr = arr(a.get("tracks"));
				if (tracksArr == null || tracksArr.size() == 0 || tracksArr.size() > MAX_TRACKS) {
					out.err(p, "1–" + MAX_TRACKS + " tracks");
					continue;
				}
				List<Track> tracks = new ArrayList<Track>();
				for (int j = 0; j < tracksArr.size(); j++) {
					String tp = p + ".tracks[" + j + "]";
					JsonObject tr = obj(tracksArr.get(j));
					String tb = tr == null ? null : str(tr.get("bone"));
					Integer tbi = tb == null ? null : boneIndex.get(tb);
					if (tbi == null) out.err(tp, "unbekannter Knochen " + tb);
					int channel = tr == null ? -1 : index(CHANNELS, str(tr.get("channel")));
					if (channel < 0) out.err(tp, "channel eins von rotation, position, scale");
					int interp = LINEAR;
					JsonElement ie = tr == null ? null : tr.get("interpolation");
					if (ie != null && !ie.isJsonNull()) {
						interp = index(INTERPOLATIONS, str(ie));
						if (interp < 0) out.err(tp, "interpolation eins von linear, smooth, step");
					}
					JsonArray keys = tr == null ? null : arr(tr.get("keys"));
					if (keys == null || keys.size() == 0 || keys.size() > MAX_KEYS) {
						out.err(tp, "1–" + MAX_KEYS + " keys");
						continue;
					}
					int[] ts = new int[keys.size()];
					float[][] vs = new float[keys.size()][];
					int[] kis = new int[keys.size()];
					int last = -1;
					for (int k = 0; k < keys.size(); k++) {
						String kp = tp + ".keys[" + k + "]";
						JsonObject key = obj(keys.get(k));
						JsonElement tEl = key == null ? null : key.get("t");
						int t = isInt(tEl) ? intOr(tEl, -1) : -1;
						if (!isInt(tEl) || t < 0 || t > length || t <= last) out.err(kp, "t aufsteigend in 0…lengthMs");
						if (isInt(tEl)) last = t;
						float[] v = key == null ? null : vec(key.get("v"));
						if (v == null) out.err(kp, "v [x,y,z] fehlt");
						JsonElement kiEl = key == null ? null : key.get("interpolation");
						int ki = -1;
						if (kiEl != null && !kiEl.isJsonNull()) {
							ki = index(INTERPOLATIONS, str(kiEl));
							if (ki < 0) out.err(kp, "unbekannte interpolation");
						}
						if (channel == SCALE && v != null) {
							for (float s : v) {
								if (s <= 0 || s > 4) {
									out.err(kp, "scale 0…4");
									break;
								}
							}
						}
						ts[k] = t;
						vs[k] = v == null ? new float[3] : v;
						kis[k] = ki;
					}
					tracks.add(new Track(tbi == null ? 0 : tbi, Math.max(0, channel), Math.max(0, interp), ts, vs, kis));
				}
				animations.add(new Animation(aid, Math.max(0, driver), Math.max(1, length), loop, offset,
						tracks.toArray(new Track[0])));
			}
		}

		// Leucht-Höfe
		List<Halo> halos = new ArrayList<Halo>();
		JsonElement halosEl = m.get("halos");
		JsonArray halosArr = null;
		if (halosEl != null && !halosEl.isJsonNull()) {
			halosArr = arr(halosEl);
			if (halosArr == null || halosArr.size() > MAX_HALOS) out.err("halos", "höchstens " + MAX_HALOS);
		}
		if (halosArr != null) {
			for (int i = 0; i < halosArr.size() && i < MAX_HALOS * 2; i++) {
				String p = "halos[" + i + "]";
				JsonObject h = obj(halosArr.get(i));
				if (h == null) {
					out.err(p, "Objekt erwartet");
					continue;
				}
				String hb = str(h.get("bone"));
				Integer hbi = hb == null ? null : boneIndex.get(hb);
				if (hbi == null) out.err(p, "unbekannter Knochen " + hb);
				float[] pos = vec(h.get("pos"));
				if (pos == null) out.err(p, "pos [x,y,z] fehlt");
				Float size = num(h.get("size"));
				if (size == null || size <= 0 || size > 32) out.err(p, "size 0…32");
				String color = str(h.get("color"));
				if (color == null || !COLOR.matcher(color).matches()) out.err(p, "color #rrggbb");
				float intensity = 1f;
				JsonElement intEl = h.get("intensity");
				if (intEl != null && !intEl.isJsonNull()) {
					Float in = num(intEl);
					if (in == null || in < 0 || in > 2) out.err(p, "intensity 0…2");
					else intensity = in;
				}
				float[] normal = null;
				JsonElement nEl = h.get("normal");
				if (nEl != null && !nEl.isJsonNull()) {
					float[] n = vec(nEl);
					double len = n == null ? 0 : Math.sqrt((double) n[0] * n[0] + (double) n[1] * n[1] + (double) n[2] * n[2]);
					if (n == null || len < 1e-6) out.err(p, "normal [x,y,z] ≠ 0");
					else normal = new float[] { (float) (n[0] / len), (float) (n[1] / len), (float) (n[2] / len) };
				}
				boolean pulse = false;
				int period = 1000;
				float pmin = 1f;
				float pmax = 1f;
				int phase = 0;
				JsonElement pEl = h.get("pulse");
				if (pEl != null && !pEl.isJsonNull()) {
					JsonObject q = obj(pEl);
					JsonElement perEl = q == null ? null : q.get("periodMs");
					if (!isInt(perEl) || intOr(perEl, 0) < 100 || intOr(perEl, 0) > MAX_LENGTH_MS) out.err(p, "pulse.periodMs 100…60000");
					Float qmin = q == null ? null : num(q.get("min"));
					Float qmax = q == null ? null : num(q.get("max"));
					if (qmin == null || qmax == null || qmin < 0 || qmax > 2 || qmin > qmax) out.err(p, "pulse.min ≤ max, 0…2");
					JsonElement phEl = q == null ? null : q.get("phaseMs");
					if (phEl != null && !phEl.isJsonNull() && !isInt(phEl)) out.err(p, "pulse.phaseMs ganzzahlig");
					if (q != null) {
						pulse = true;
						period = Math.max(1, intOr(perEl, 1000));
						pmin = qmin == null ? 1f : qmin;
						pmax = qmax == null ? 1f : qmax;
						phase = isInt(phEl) ? intOr(phEl, 0) : 0;
					}
				}
				int rgb = color != null && COLOR.matcher(color).matches() ? Integer.parseInt(color.substring(1), 16) : 0xFFFFFF;
				halos.add(new Halo(hbi == null ? 0 : hbi, pos == null ? new float[3] : pos, size == null ? 1f : size, rgb,
						intensity, normal, pulse, period, pmin, pmax, phase));
			}
		}

		if (out.ok()) {
			int glowTime = glow == null ? 0 : intOr(glow.get("frameTimeMs"), 0);
			out.model = new CosmeticV2(id, name, tw, th, scale, frames, frames > 1 ? intOr(tex.get("frameTimeMs"), 0) : 0,
					glow != null, glow == null ? 0 : glowFrames, glowFrames > 1 ? glowTime : 0, bones, cubes, animations, halos);
		}
		return out;
	}

	private static boolean checkImage(Check out, String p, JsonObject t, boolean main) {
		boolean ok = true;
		String file = str(t.get("file"));
		if (file == null || !FILE.matcher(file).matches()) {
			out.err(p + ".file", "Dateiname *.png");
			ok = false;
		}
		if (main) {
			int scale = isInt(t.get("scale")) ? intOr(t.get("scale"), 0) : 0;
			if (scale != 1 && scale != 2 && scale != 4 && scale != 8 && scale != 16) {
				out.err(p + ".scale", "eins von 1, 2, 4, 8, 16");
				ok = false;
			}
			for (String k : new String[] { "width", "height" }) {
				JsonElement e = t.get(k);
				int v = isInt(e) ? intOr(e, 0) : 0;
				if (!isInt(e) || v < 8 || v % 8 != 0) {
					out.err(p + "." + k, "Vielfaches von 8");
					ok = false;
				}
			}
			if (ok && ((long) intOr(t.get("width"), 0) * scale > MAX_TEXTURE_EDGE
					|| (long) intOr(t.get("height"), 0) * scale > MAX_TEXTURE_EDGE)) {
				out.err(p, "Kante höchstens " + MAX_TEXTURE_EDGE + " px");
				ok = false;
			}
		}
		JsonElement fe = t.get("frames");
		int frames = fe == null || fe.isJsonNull() ? 1 : (isInt(fe) ? intOr(fe, 0) : 0);
		if (frames < 1 || frames > MAX_FRAMES) {
			out.err(p + ".frames", "1–" + MAX_FRAMES);
			ok = false;
		}
		if (frames > 1) {
			JsonElement te = t.get("frameTimeMs");
			int ft = isInt(te) ? intOr(te, 0) : 0;
			if (!isInt(te) || ft < 16 || ft > 10000) {
				out.err(p + ".frameTimeMs", "16–10000");
				ok = false;
			}
		}
		return ok;
	}

	/** Größe einer Fläche in Einheiten [Breite, Höhe] (ohne inflate). */
	static float[] faceSize(float[] from, float[] to, int face) {
		float sx = to[0] - from[0];
		float sy = to[1] - from[1];
		float sz = to[2] - from[2];
		if (face == NORTH || face == SOUTH) return new float[] { sx, sy };
		if (face == EAST || face == WEST) return new float[] { sz, sy };
		return new float[] { sx, sz };
	}

	// --- JSON-Hilfen (Gson 2.2.4: kein keySet, kein JsonParser.parseString) ---

	private static JsonObject obj(JsonElement e) {
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static JsonArray arr(JsonElement e) {
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	private static String str(JsonElement e) {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return null;
		return e.getAsString();
	}

	private static Float num(JsonElement e) {
		if (e == null || !e.isJsonPrimitive()) return null;
		JsonPrimitive p = e.getAsJsonPrimitive();
		if (!p.isNumber()) return null;
		double d = p.getAsDouble();
		if (Double.isNaN(d) || Double.isInfinite(d)) return null;
		return (float) d;
	}

	private static boolean isInt(JsonElement e) {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return false;
		double d = e.getAsDouble();
		return !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d) && Math.abs(d) < 1e9;
	}

	private static int intOr(JsonElement e, int fallback) {
		return isInt(e) ? (int) e.getAsDouble() : fallback;
	}

	private static float[] vec(JsonElement e) {
		return nums(e, 3);
	}

	private static float[] nums(JsonElement e, int n) {
		JsonArray a = arr(e);
		if (a == null || a.size() != n) return null;
		float[] out = new float[n];
		for (int i = 0; i < n; i++) {
			Float f = num(a.get(i));
			if (f == null) return null;
			out[i] = f;
		}
		return out;
	}

	private static float abs3(float[] v) {
		return Math.max(Math.abs(v[0]), Math.max(Math.abs(v[1]), Math.abs(v[2])));
	}

	private static boolean onGrid(float v, float step) {
		double q = (double) v / step;
		return Math.abs(q - Math.rint(q)) < 1e-6;
	}

	private static int index(String[] list, String s) {
		if (s == null) return -1;
		for (int i = 0; i < list.length; i++) if (list[i].equals(s)) return i;
		return -1;
	}
}
