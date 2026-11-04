package dev.theredstonee.trsclient.core.minigames;

import dev.theredstonee.trsclient.core.qol.QolSound;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Redstone-Stapler: Bild, Effekte und Ton, Übertragung von {@code app/utils/minigames/staplerView.ts}. Rechnet das Bild je
 * Pixel in {@link #pixels} (ARGB, 224×384), damit Leuchten und Blitz in jeder Minecraft-Version gleich aussehen. Beeinflusst
 * das Spiel nicht, darum dürfen hier Zufall und Kommazahlen stehen.
 */
public final class StaplerView {
	public static final int TILE = 16;
	public static final int WIDTH = 14 * TILE;
	public static final int HEIGHT = 24 * TILE;
	private static final int MW = 14;
	private static final int MH = 24;
	private static final int OX = 2;
	private static final int OY = 3;
	public static final int ATLAS_COLS = 6;
	public static final int ATLAS_ROWS = 4;

	private static final int I_LAMP_ON = 13;
	private static final int I_TNT_HOT = 14;
	private static final int I_DROPPER = 15;
	private static final int I_DROP_ON = 16;
	private static final int I_FRAME = 17;
	private static final int I_WALL = 18;
	private static final int I_WELL = 19;
	private static final int I_DUST = 20;
	private static final int I_DUST_ON = 21;
	private static final int I_LEVER = 22;

	private static final int[] COLORS = {0, 0x8D8D8F, 0x6F6F70, 0xA5533F, 0xC9794A, 0xA57D48, 0x3A2A55, 0xD9D3C8, 0xE0261C, 0xD8352A,
			0xD9A43F, 0xA58A58, 0xB5B5B8};

	/** Abfall des Leuchtens über den quadrierten Abstand: zwei Stufen wie der Verlauf im Launcher. */
	private static final float[] GLOW = new float[256];

	static {
		for (int i = 0; i < 256; i++) {
			float t = 1f - (float) Math.sqrt(i / 255.0);
			GLOW[i] = t * t * 0.92f;
		}
	}

	private static final class Part {
		float x;
		float y;
		float vx;
		float vy;
		float g;
		float life;
		float max;
		int col;
		float size;
	}

	private static final class Timed {
		float x;
		float y;
		float a;
		float t;
		float max;
	}

	public final int[] pixels = new int[WIDTH * HEIGHT];
	/** Felder im Raster {@link #ATLAS_COLS}×{@link #ATLAS_ROWS}, für Vorschau-Steine und Regeln. */
	public final int[] atlas = new int[ATLAS_COLS * TILE * ATLAS_ROWS * TILE];
	private final int[][] tiles = new int[ATLAS_COLS * ATLAS_ROWS][256];
	private final int[][] seamed = new int[ATLAS_COLS * ATLAS_ROWS][256];
	private final boolean[] opaque = new boolean[ATLAS_COLS * ATLAS_ROWS];
	/** Wand, Mulde, Rahmen und Hebel, 16 Pixel größer je Seite (Wackeln). */
	private final int[] background = new int[(WIDTH + 32) * (HEIGHT + 32)];
	/** Abdunkeln zum Rand, 256 = unverändert. */
	private final int[] vignette = new int[WIDTH * HEIGHT];
	private final GameSkin skin;
	private final GameSound sound;
	private final Random random = new Random();
	private final List<Part> parts = new ArrayList<Part>();
	private final List<Timed> timed = new ArrayList<Timed>();
	private float shake;
	private float flash;
	private final float[] wire = new float[Stapler.WIDTH + 4];
	private final float[] drop = new float[Stapler.WIDTH];
	private float viewX;
	private float viewY;
	private final float[][] lights = new float[320][6];
	private int lightCount;
	private int clipX1;
	private int clipY1;
	private int clipX2 = WIDTH;
	private int clipY2 = HEIGHT;
	/** Wackeln und Blitze zeigen (Einstellung). */
	public boolean effects = true;

	public StaplerView(GameTextures textures, GameSkin skin, GameSound sound) {
		this.skin = skin;
		this.sound = sound;
		buildAtlas(textures);
		buildBackground();
		buildVignette();
	}

	/** Liest die Texturen neu, zum Beispiel nach einem Ressourcenpaket-Wechsel. */
	public void reload(GameTextures textures) {
		buildAtlas(textures);
		buildBackground();
	}

	// --- Aufbau ---

	private void put(int index, GameTextures.Tex tex, int tint) {
		int[] out = tiles[index];
		float a = (tint >>> 24) / 255f;
		int tr = (tint >> 16) & 255;
		int tg = (tint >> 8) & 255;
		int tb = tint & 255;
		boolean full = true;
		for (int i = 0; i < 256; i++) {
			int c = tex.d[i];
			int alpha = c >>> 24;
			if (alpha < 255) full = false;
			if (a > 0f && alpha > 0) {
				int r = Math.round(((c >> 16) & 255) * (1f - a) + tr * a);
				int g = Math.round(((c >> 8) & 255) * (1f - a) + tg * a);
				int b = Math.round((c & 255) * (1f - a) + tb * a);
				c = (alpha << 24) | (r << 16) | (g << 8) | b;
			}
			out[i] = c;
		}
		opaque[index] = full;
	}

	/** Staub quer durchs Feld. Alte Versionen legen die Datei anders herum ab, darum entscheidet die Form. */
	private void dust(int index, GameTextures.Tex line, float[] color) {
		int minX = 16;
		int maxX = -1;
		int minY = 16;
		int maxY = -1;
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				if ((line.d[y * 16 + x] >>> 24) > 0) {
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
					minY = Math.min(minY, y);
					maxY = Math.max(maxY, y);
				}
			}
		}
		boolean upright = maxY - minY > maxX - minX;
		int[] out = tiles[index];
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				int c = upright ? line.d[(15 - x) * 16 + y] : line.d[y * 16 + x];
				int v = (c >> 16) & 255;
				int r = Math.min(255, Math.round(v * color[0]));
				int g = Math.min(255, Math.round(v * color[1]));
				int b = Math.min(255, Math.round(v * color[2]));
				out[y * 16 + x] = (c & 0xFF000000) | (r << 16) | (g << 8) | b;
			}
		}
		opaque[index] = false;
	}

	private void buildAtlas(GameTextures tx) {
		put(Stapler.STONE, tx.block("stone"), 0);
		put(Stapler.COBBLE, tx.block("cobblestone"), 0);
		put(Stapler.BRICK, tx.block("bricks"), 0);
		put(Stapler.COPPER, tx.block("copper_block"), 0);
		put(Stapler.OAK, tx.block("oak_planks"), 0);
		put(Stapler.OBSID, tx.block("obsidian"), 0);
		put(Stapler.QUARTZ, tx.block("quartz_block_side"), 0);
		put(Stapler.RED, tx.block("redstone_block"), 0);
		put(Stapler.TNT, tx.block("tnt_side"), 0);
		put(I_TNT_HOT, tx.block("tnt_side"), 0x9EFFFFFF);
		put(Stapler.LAMP, tx.block("redstone_lamp"), 0);
		put(I_LAMP_ON, tx.block("redstone_lamp_on"), 0);
		put(Stapler.PISTON, tx.block("piston_top"), 0);
		put(Stapler.HEAD, tx.block("iron_block"), 0);
		put(I_DROPPER, tx.block("dropper_front"), 0);
		put(I_DROP_ON, tx.block("dropper_front"), 0x6BFF3C1E);
		put(I_FRAME, tx.block(skin.frame), 0);
		put(I_WALL, tx.block(skin.wall), skin.wallTint);
		put(I_WELL, tx.block(skin.well), skin.wellTint);
		dust(I_DUST, tx.block("redstone_dust_line0"), skin.wireOff);
		dust(I_DUST_ON, tx.block("redstone_dust_line0"), skin.wireOn);
		// Hebel auf einem kleinen Sockel aus Bruchstein.
		int[] lever = tiles[I_LEVER];
		java.util.Arrays.fill(lever, 0);
		GameTextures.Tex cobble = tx.block("cobblestone");
		for (int y = 0; y < 5; y++) {
			for (int x = 0; x < 10; x++) lever[(11 + y) * 16 + 3 + x] = cobble.d[y * 16 + x * 16 / 10];
		}
		GameTextures.Tex handle = tx.block("lever");
		for (int i = 0; i < 256; i++) {
			if ((handle.d[i] >>> 24) > 127) lever[i] = handle.d[i];
		}
		opaque[I_LEVER] = false;
		for (int i = 0; i < tiles.length; i++) {
			int col = i % ATLAS_COLS;
			int row = i / ATLAS_COLS;
			for (int y = 0; y < 16; y++) {
				for (int x = 0; x < 16; x++) {
					int c = tiles[i][y * 16 + x];
					atlas[(row * TILE + y) * ATLAS_COLS * TILE + col * TILE + x] = c;
					// Dunkle Fuge, damit gleiche Steine im Stapel lesbar bleiben.
					seamed[i][y * 16 + x] = x == 0 || y == 0 || x == 15 || y == 15 ? darken(c, 0.66f) : c;
				}
			}
		}
	}

	private static int darken(int c, float k) {
		int r = (int) (((c >> 16) & 255) * k);
		int g = (int) (((c >> 8) & 255) * k);
		int b = (int) ((c & 255) * k);
		return (c & 0xFF000000) | (r << 16) | (g << 8) | b;
	}

	private void bgTile(int index, int x, int y) {
		int w = WIDTH + 32;
		int[] t = tiles[index];
		for (int j = 0; j < 16; j++) {
			int py = y + j + 16;
			if (py < 0 || py >= HEIGHT + 32) continue;
			for (int i = 0; i < 16; i++) {
				int px = x + i + 16;
				if (px < 0 || px >= w) continue;
				int c = t[j * 16 + i];
				if ((c >>> 24) > 127) background[py * w + px] = c | 0xFF000000;
			}
		}
	}

	private void buildBackground() {
		java.util.Arrays.fill(background, 0xFF000000);
		for (int y = -1; y <= MH; y++) {
			for (int x = -1; x <= MW; x++) bgTile(I_WALL, x * TILE, y * TILE);
		}
		for (int y = OY; y < OY + 20; y++) {
			for (int x = OX; x < OX + Stapler.WIDTH; x++) bgTile(I_WELL, x * TILE, y * TILE);
		}
		for (int x = OX - 1; x <= OX + Stapler.WIDTH; x++) bgTile(I_FRAME, x * TILE, (OY + 20) * TILE);
		for (int y = OY - 2; y < OY + 20; y++) {
			bgTile(I_FRAME, (OX - 1) * TILE, y * TILE);
			bgTile(I_FRAME, (OX + Stapler.WIDTH) * TILE, y * TILE);
		}
		bgTile(I_LEVER, (OX - 1) * TILE, (OY - 3) * TILE);
	}

	private void buildVignette() {
		float cx = WIDTH / 2f;
		float cy = HEIGHT / 2f;
		float r0 = HEIGHT * 0.28f;
		float r1 = HEIGHT * 0.72f;
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				float d = (float) Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
				float k = Math.max(0f, Math.min(1f, (d - r0) / (r1 - r0)));
				vignette[y * WIDTH + x] = Math.round(256 * (1f - 0.56f * k));
			}
		}
	}

	// --- Ablauf ---

	private static float vis(float y) {
		return OY + y - Stapler.HIDDEN;
	}

	private static int tileOf(Stapler.Cell c) {
		if (c.t == Stapler.LAMP && c.lit > 0) return I_LAMP_ON;
		if (c.t == Stapler.TNT && c.fuse > 0 && ((c.fuse >> 1) & 1) != 0) return I_TNT_HOT;
		return c.t;
	}

	private void burst(float x, float y, int col, int n, float speed, float life, float size) {
		for (int i = 0; i < n; i++) {
			float a = random.nextFloat() * 6.283f;
			float v = speed * (0.3f + random.nextFloat());
			Part p = new Part();
			p.x = x;
			p.y = y;
			p.vx = (float) Math.cos(a) * v;
			p.vy = (float) Math.sin(a) * v;
			p.g = 9f;
			p.life = life;
			p.max = life;
			p.col = col;
			p.size = size * (0.6f + random.nextFloat() * 0.7f);
			parts.add(p);
		}
	}

	private void dustAt(float x, float y, int n) {
		for (int i = 0; i < n; i++) {
			Part p = new Part();
			p.x = x + random.nextFloat();
			p.y = y + random.nextFloat();
			p.vx = (random.nextFloat() - 0.5f) * 0.6f;
			p.vy = -1.2f - random.nextFloat() * 1.2f;
			p.g = -0.2f;
			p.life = 0.7f;
			p.max = 0.7f;
			p.col = 0xFF3B24;
			p.size = 0.1f;
			parts.add(p);
		}
	}

	public void reset() {
		parts.clear();
		timed.clear();
		java.util.Arrays.fill(wire, 0f);
		java.util.Arrays.fill(drop, 0f);
		shake = 0f;
		flash = 0f;
		sound.clear();
	}

	public void onEvent(Stapler.Event e) {
		switch (e.type) {
			case MOVE:
				sound.play(QolSound.HAT, 0.08f, 1.6f);
				break;
			case ROTATE:
				sound.play(QolSound.LEVER, 0.18f, 1.5f);
				break;
			case HOLD:
				sound.play(QolSound.LEVER, 0.25f, 0.9f);
				break;
			case SPAWN:
				sound.play(QolSound.DISPENSE, 0.3f, 1.2f);
				viewX = e.x;
				viewY = 0f;
				for (int i = 0; i < e.n; i++) {
					int x = e.x + i;
					if (x >= 0 && x < Stapler.WIDTH) {
						drop[x] = 1f;
						burst(x + 0.5f, Stapler.HIDDEN - 0.2f, 0x8D8D8F, 3, 1.4f, 0.34f, 0.12f);
					}
				}
				break;
			case LOCK:
				sound.play(QolSound.STONE_PLACE, 0.45f, 0.9f);
				for (int[] c : e.cells) burst(c[0] + 0.5f, c[1] + 1f, 0xB9B1AA, 2, 1.2f, 0.3f, 0.09f);
				break;
			case HARD:
				sound.play(QolSound.BASS, 0.3f, 0.6f);
				shake = Math.max(shake, 2.4f);
				for (int i = 0; i < 6; i++) dustAt(random.nextFloat() * Stapler.WIDTH, e.to, 1);
				break;
			case SWEEP:
				for (int i = 0; i < 3 + e.rows.length; i++) sound.later(i * 0.03f, QolSound.PLING, 0.2f, 0.8f * (float) Math.pow(1.19, i));
				break;
			case CLEAR:
				sound.play(QolSound.ORB, 0.5f, 1f);
				for (int i = 0; i < e.rows.length + 1; i++) sound.later(i * 0.05f, QolSound.CHIME, 0.4f, (float) Math.pow(1.26, i));
				shake = Math.max(shake, 2.6f + e.rows.length);
				for (int r = 0; r < e.rows.length; r++) {
					int y = e.rows[r];
					for (int x = 0; x < Stapler.WIDTH; x++) {
						int t = e.cells[r][x];
						if (t > 0) burst(x + 0.5f, y + 0.5f, t < COLORS.length ? COLORS[t] : 0x999999, 5, 3.2f, 0.62f, 0.17f);
						dustAt(x, y, 2);
					}
				}
				break;
			case IGNITE:
				sound.play(QolSound.TNT_PRIMED, 0.4f, 1.2f);
				break;
			case EXPLODE:
				sound.play(QolSound.EXPLODE, 0.55f, 1f);
				shake = Math.max(shake, 9f);
				flash = Math.max(flash, 0.85f);
				burst(e.x + 0.5f, e.y + 0.5f, 0xFFB35A, 26, 8f, 0.68f, 0.24f);
				burst(e.x + 0.5f, e.y + 0.5f, 0x4A403C, 22, 4f, 1.1f, 0.32f);
				burst(e.x + 0.5f, e.y + 0.5f, 0xE0261C, 12, 6f, 0.78f, 0.16f);
				Timed light = new Timed();
				light.x = e.x + 0.5f;
				light.y = e.y + 0.5f;
				light.a = 5.5f;
				light.t = 0.34f;
				light.max = 0.34f;
				timed.add(light);
				break;
			case PISTON:
				sound.play(QolSound.PISTON, 0.5f, 1f);
				shake = Math.max(shake, 2.2f);
				burst(e.x + 0.5f + e.d, e.y + 0.5f, 0xB5B5B8, 8, 2.8f, 0.4f, 0.12f);
				break;
			case LAMP:
				sound.play(QolSound.BELL, 0.4f, 1.5f);
				burst(e.x + 0.5f, e.y + 0.5f, 0xFFD27A, 8, 2f, 0.44f, 0.1f);
				break;
			case CHARGE:
				sound.play(QolSound.PLING, 0.2f, 0.6f);
				dustAt(e.x, e.y, 6);
				break;
			case PULSE:
				sound.play(QolSound.BASS, 0.8f, 0.5f);
				sound.play(QolSound.PISTON, 0.5f, 0.7f);
				shake = Math.max(shake, 5f);
				flash = 0.58f;
				for (int x = 0; x < Stapler.WIDTH; x++) dustAt(x, Stapler.HEIGHT - 1, 8);
				break;
			case LEVEL: {
				int[] notes = {0, 4, 7, 12};
				for (int i = 0; i < 4; i++) sound.later(i * 0.07f, QolSound.PLING, 0.4f, GameSound.note(notes[i]));
				break;
			}
			case OVER: {
				int[] notes = {0, -3, -7, -12};
				for (int i = 0; i < 4; i++) sound.later(i * 0.12f, QolSound.BASS, 0.6f, GameSound.note(notes[i]));
				break;
			}
			default:
		}
	}

	public void update(float dt, Stapler game) {
		shake *= (float) Math.pow(0.0009, dt);
		if (shake < 0.1f) shake = 0f;
		flash = Math.max(0f, flash - dt * 3f);
		for (int i = parts.size() - 1; i >= 0; i--) {
			Part p = parts.get(i);
			p.life -= dt;
			if (p.life <= 0f) {
				int last = parts.size() - 1;
				parts.set(i, parts.get(last));
				parts.remove(last);
				continue;
			}
			p.vy += p.g * dt;
			p.x += p.vx * dt;
			p.y += p.vy * dt;
		}
		while (parts.size() > 900) parts.remove(parts.size() - 1);
		for (int i = timed.size() - 1; i >= 0; i--) {
			Timed t = timed.get(i);
			t.t -= dt;
			if (t.t <= 0f) timed.remove(i);
		}
		for (int i = 0; i < wire.length; i++) wire[i] = Math.max(0f, wire[i] - dt * 1.9f);
		for (int i = 0; i < Stapler.WIDTH; i++) drop[i] = Math.max(0f, drop[i] - dt * 2.8f);
		if (game == null) return;
		if (game.phase == Stapler.Phase.ARE) {
			// Das Signal läuft die Leitung entlang bis zum Dropper, der den nächsten Stein ausgibt.
			float k = 1f - game.timer / (float) Stapler.ARE;
			Stapler.Piece next = game.queue.get(0);
			int right = 0;
			for (int[] c : next.b) right = Math.max(right, c[0]);
			int end = game.nextX + right + 1;
			int pos = (int) Math.floor(k * (end + 1));
			for (int[] c : next.b) {
				int x = game.nextX + c[0];
				if (x >= 0 && x < Stapler.WIDTH) drop[x] = Math.max(drop[x], 0.25f + k * 0.7f);
			}
			for (int i = 0; i <= pos && i < Stapler.WIDTH + 1; i++) wire[i] = Math.max(wire[i], 1f - (pos - i) * 0.07f);
			if (random.nextFloat() < 0.55f && pos <= Stapler.WIDTH) dustAt(pos - 1, Stapler.HIDDEN - 2.2f, 1);
		}
		if (game.cur != null) {
			viewX += (game.cur.x - viewX) * Math.min(1f, dt * 34f);
			viewY += (game.cur.y - viewY) * Math.min(1f, dt * 30f);
			if (Math.abs(game.cur.x - viewX) > 4f) viewX = game.cur.x;
			if (Math.abs(game.cur.y - viewY) > 3f) viewY = game.cur.y;
		}
	}

	// --- Bild ---

	private void light(float x, float y, float r, float g, float b, float a) {
		if (lightCount >= lights.length) return;
		float[] l = lights[lightCount++];
		l[0] = x;
		l[1] = y;
		l[2] = r;
		l[3] = g;
		l[4] = b;
		l[5] = a;
	}

	private void collectLights(Stapler game) {
		lightCount = 0;
		for (int i = 0; i < Stapler.WIDTH + 1; i++) {
			if (wire[i] > 0.05f) light(i - 0.5f, Stapler.HIDDEN - 1.5f, 1f, 0.16f, 0.08f, wire[i] * 1.2f);
		}
		for (int x = 0; x < Stapler.WIDTH; x++) {
			if (drop[x] > 0.05f) light(x + 0.5f, Stapler.HIDDEN - 0.4f, 1f, 0.3f, 0.12f, drop[x] * 1.8f);
		}
		if (game != null) {
			for (int y = Stapler.HIDDEN; y < Stapler.HEIGHT; y++) {
				for (int x = 0; x < Stapler.WIDTH; x++) {
					Stapler.Cell c = game.grid[y][x];
					if (c == null) continue;
					if (c.t == Stapler.LAMP && c.lit > 0) light(x + 0.5f, y + 0.5f, 1f, 0.74f, 0.34f, 2.6f);
					else if (c.t == Stapler.RED) light(x + 0.5f, y + 0.5f, 1f, 0.12f, 0.06f, 0.72f);
					else if (c.t == Stapler.TNT && c.fuse > 0) light(x + 0.5f, y + 0.5f, 1f, 0.42f, 0.16f, 1.8f);
				}
			}
			if (game.sweep != null) {
				for (int y : game.sweep.rows) {
					light(game.sweep.t / (float) Stapler.CLEAR_T * Stapler.WIDTH, y + 0.5f, 1f, 0.32f, 0.14f, 3.4f);
				}
			}
			if (game.cur != null && game.phase == Stapler.Phase.FALL) {
				Stapler.Piece p = game.cur;
				for (int i = 0; i < p.b.length; i++) {
					if (p.mats[i] == Stapler.RED) light(p.x + p.b[i][0] + 0.5f, p.y + p.b[i][1] + 0.5f, 1f, 0.12f, 0.06f, 0.9f);
				}
			}
		}
		for (int i = 0; i < timed.size(); i++) {
			Timed t = timed.get(i);
			light(t.x, t.y, 1f, 0.55f, 0.2f, t.a * (t.t / t.max));
		}
		// Die 24 hellsten, wie im Launcher.
		for (int i = 1; i < lightCount; i++) {
			float[] l = lights[i];
			int j = i - 1;
			while (j >= 0 && lights[j][5] < l[5]) {
				lights[j + 1] = lights[j];
				j--;
			}
			lights[j + 1] = l;
		}
		if (lightCount > 24) lightCount = 24;
	}

	private void blit(int[] tile, boolean solid, int x, int y) {
		int x1 = Math.max(clipX1, x);
		int y1 = Math.max(clipY1, y);
		int x2 = Math.min(clipX2, x + TILE);
		int y2 = Math.min(clipY2, y + TILE);
		if (x1 >= x2 || y1 >= y2) return;
		int[] out = pixels;
		for (int py = y1; py < y2; py++) {
			int row = (py - y) * 16 - x;
			int o = py * WIDTH;
			if (solid) {
				System.arraycopy(tile, row + x1, out, o + x1, x2 - x1);
				continue;
			}
			for (int px = x1; px < x2; px++) {
				int c = tile[row + px];
				int a = c >>> 24;
				if (a > 127) out[o + px] = c | 0xFF000000;
			}
		}
	}

	private void blend(int x1, int y1, int x2, int y2, int rgb, float alpha) {
		x1 = Math.max(0, x1);
		y1 = Math.max(0, y1);
		x2 = Math.min(WIDTH, x2);
		y2 = Math.min(HEIGHT, y2);
		int a = Math.round(alpha * 256);
		if (a <= 0) return;
		int inv = 256 - a;
		int sr = ((rgb >> 16) & 255) * a;
		int sg = ((rgb >> 8) & 255) * a;
		int sb = (rgb & 255) * a;
		for (int y = y1; y < y2; y++) {
			int o = y * WIDTH;
			for (int x = x1; x < x2; x++) {
				int c = pixels[o + x];
				int r = (((c >> 16) & 255) * inv + sr) >> 8;
				int g = (((c >> 8) & 255) * inv + sg) >> 8;
				int b = ((c & 255) * inv + sb) >> 8;
				pixels[o + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
			}
		}
	}

	private void frameLine(int x1, int y1, int x2, int y2, int rgb, float alpha) {
		blend(x1, y1, x2, y1 + 1, rgb, alpha);
		blend(x1, y2 - 1, x2, y2, rgb, alpha);
		blend(x1, y1 + 1, x1 + 1, y2 - 1, rgb, alpha);
		blend(x2 - 1, y1 + 1, x2, y2 - 1, rgb, alpha);
	}

	private void glow(float x, float y, float radius, float cr, float cg, float cb, float alpha) {
		int x1 = Math.max(0, (int) Math.floor(x - radius));
		int y1 = Math.max(0, (int) Math.floor(y - radius));
		int x2 = Math.min(WIDTH, (int) Math.ceil(x + radius));
		int y2 = Math.min(HEIGHT, (int) Math.ceil(y + radius));
		float r2 = radius * radius;
		float scale = 255f / r2;
		float ar = cr * 255f * alpha;
		float ag = cg * 255f * alpha;
		float ab = cb * 255f * alpha;
		for (int py = y1; py < y2; py++) {
			float dy = py + 0.5f - y;
			int o = py * WIDTH;
			for (int px = x1; px < x2; px++) {
				float dx = px + 0.5f - x;
				float d2 = dx * dx + dy * dy;
				if (d2 >= r2) continue;
				float f = GLOW[(int) (d2 * scale)];
				int c = pixels[o + px];
				int r = ((c >> 16) & 255) + (int) (ar * f);
				int g = ((c >> 8) & 255) + (int) (ag * f);
				int b = (c & 255) + (int) (ab * f);
				if (r > 255) r = 255;
				if (g > 255) g = 255;
				if (b > 255) b = 255;
				pixels[o + px] = 0xFF000000 | (r << 16) | (g << 8) | b;
			}
		}
	}

	private void additive(int rgb, float alpha) {
		int ar = (int) (((rgb >> 16) & 255) * alpha);
		int ag = (int) (((rgb >> 8) & 255) * alpha);
		int ab = (int) ((rgb & 255) * alpha);
		for (int i = 0; i < pixels.length; i++) {
			int c = pixels[i];
			int r = ((c >> 16) & 255) + ar;
			int g = ((c >> 8) & 255) + ag;
			int b = (c & 255) + ab;
			if (r > 255) r = 255;
			if (g > 255) g = 255;
			if (b > 255) b = 255;
			pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
		}
	}

	/** Rechnet ein Bild in {@link #pixels}. */
	public void draw(Stapler game) {
		float strength = effects ? shake : 0f;
		int sx = Math.round((random.nextFloat() - 0.5f) * strength);
		int sy = Math.round((random.nextFloat() - 0.5f) * strength);
		int bgw = WIDTH + 32;
		for (int y = 0; y < HEIGHT; y++) {
			System.arraycopy(background, (y - sy + 16) * bgw + 16 - sx, pixels, y * WIDTH, WIDTH);
		}
		clipX1 = 0;
		clipY1 = 0;
		clipX2 = WIDTH;
		clipY2 = HEIGHT;
		for (int i = 0; i < Stapler.WIDTH + 1; i++) {
			int t = wire[i] > 0.3f ? I_DUST_ON : I_DUST;
			blit(tiles[t], false, (OX + i - 1) * TILE + sx, (OY - 2) * TILE + sy);
		}
		for (int x = 0; x < Stapler.WIDTH; x++) {
			int t = drop[x] > 0.2f ? I_DROP_ON : I_DROPPER;
			blit(tiles[t], opaque[t], (OX + x) * TILE + sx, (OY - 1) * TILE + sy);
		}
		if (game != null) drawGame(game, sx, sy);
		for (int i = 0; i < parts.size(); i++) {
			Part p = parts.get(i);
			float alpha = Math.min(1f, Math.max(0f, p.life / p.max) * 1.5f);
			int s = Math.max(1, Math.round(p.size * TILE));
			int px = Math.round((OX + p.x) * TILE) + sx;
			int py = Math.round(vis(p.y) * TILE) + sy;
			blend(px, py, px + s, py + s, p.col, alpha);
		}
		collectLights(game);
		for (int i = 0; i < lightCount; i++) {
			float[] l = lights[i];
			float a = l[5];
			glow((OX + l[0]) * TILE + sx, vis(l[1]) * TILE + sy, TILE * (1.6f + a * 0.95f), l[2], l[3], l[4], Math.min(0.9f, 0.22f + a * 0.16f));
		}
		if (effects && flash > 0f) additive(skin.glow, flash * 0.55f);
		for (int i = 0; i < pixels.length; i++) {
			int m = vignette[i];
			if (m >= 256) continue;
			int c = pixels[i];
			pixels[i] = 0xFF000000 | ((((c >> 16) & 255) * m >> 8) << 16) | ((((c >> 8) & 255) * m >> 8) << 8) | ((c & 255) * m >> 8);
		}
	}

	private void drawGame(Stapler game, int sx, int sy) {
		for (int y = Stapler.HIDDEN; y < Stapler.HEIGHT; y++) {
			for (int x = 0; x < Stapler.WIDTH; x++) {
				Stapler.Cell c = game.grid[y][x];
				if (c == null) continue;
				int t = tileOf(c);
				blit(seamed[t], opaque[t], (OX + x) * TILE + sx, Math.round(vis(y)) * TILE + sy);
			}
		}
		if (game.cur != null && game.phase == Stapler.Phase.FALL) {
			Stapler.Piece p = game.cur;
			int gy = game.ghostY();
			int fillRgb = skin.ghostFill & 0xFFFFFF;
			float fillA = (skin.ghostFill >>> 24) / 255f;
			int strokeRgb = skin.ghostStroke & 0xFFFFFF;
			float strokeA = (skin.ghostStroke >>> 24) / 255f;
			for (int[] c : p.b) {
				int y = gy + c[1];
				if (y < Stapler.HIDDEN) continue;
				int px = (OX + p.x + c[0]) * TILE + sx;
				int py = Math.round(vis(y)) * TILE + sy;
				blend(px, py, px + TILE, py + TILE, fillRgb, fillA);
				frameLine(px, py, px + TILE, py + TILE, strokeRgb, strokeA);
			}
			clipX1 = Math.max(0, OX * TILE + sx);
			clipY1 = Math.max(0, OY * TILE + sy);
			clipX2 = Math.min(WIDTH, (OX + Stapler.WIDTH) * TILE + sx);
			clipY2 = Math.min(HEIGHT, (OY + 20) * TILE + sy);
			for (int i = 0; i < p.b.length; i++) {
				int m = p.mats[i];
				int x = Math.round((OX + viewX + p.b[i][0]) * TILE) + sx;
				int y = Math.round((vis(viewY) + p.b[i][1]) * TILE) + sy;
				blit(seamed[m], opaque[m], x, y);
			}
			clipX1 = 0;
			clipY1 = 0;
			clipX2 = WIDTH;
			clipY2 = HEIGHT;
		}
		if (game.sweep != null) {
			float k = game.sweep.t / (float) Stapler.CLEAR_T;
			for (int y : game.sweep.rows) {
				int h = Math.round(vis(y)) * TILE + sy;
				float end = k * Stapler.WIDTH;
				int rgb = (255 << 16) | (Math.round(180 - 120 * k) << 8) | Math.round(120 - 90 * k);
				for (int x = 0; x < Stapler.WIDTH; x++) {
					if (x < end) {
						int px = (OX + x) * TILE + sx;
						blend(px, h, px + TILE, h + TILE, rgb, 0.26f + 0.62f * k);
					}
				}
				int bar = Math.round((OX + end) * TILE) + sx;
				blend(bar - 2, h, bar, h + TILE, 0xFFFFFF, 1f);
			}
		}
	}
}
