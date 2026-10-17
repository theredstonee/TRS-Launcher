package dev.theredstonee.trsclient.halloween;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.halloween.Miyako;
import dev.theredstonee.trsclient.core.ui.Affine;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import dev.theredstonee.trsclient.screen.TrsScreen;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.openal.AL10;
import org.lwjgl.stb.STBImage;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MiyakoMCs unüberspringbares Intro-Video (Vertrag Part A, nur moderner Fabric-Pfad 1.20+): die Bild-Folge (JPEG via
 * STBImage) läuft mit 12 fps als formatfüllendes Bild, der Ton (intro.ogg via STBVorbis → OpenAL) dazu. Tasten, Klicks
 * und ESC sind gesperrt. Danach schließt sich der Bildschirm und meldet {@link Miyako#introFinished()}.
 *
 * <p>Nur für MiyakoMC überhaupt erreichbar (die Medien liegen privat im Cache). Jeder Fehler endet still: Video aus,
 * trotzdem {@link Miyako#introFinished()}, damit es nicht hängen bleibt.
 */
public final class MiyakoVideoScreen extends TrsScreen {
	private static final int FPS = 12;
	private static final String TEX = "halloween/miyako_frame";

	private final Screen parent;
	private final int frameCount;
	private long startNanos = -1;
	private int currentFrame = -1;
	private TextureRef tex;
	private int texW;
	private int texH;
	private int alSource;
	private int alBuffer;
	private boolean audioTried;
	private boolean finished;

	public MiyakoVideoScreen(Screen parent) {
		super(Mc.text(""));
		this.parent = parent;
		this.frameCount = countFrames();
	}

	private static int countFrames() {
		Path d = Miyako.get().framesDir();
		if (d == null) return 0;
		int n = 0;
		for (int i = 1; i <= 100000; i++) {
			if (!Files.isRegularFile(d.resolve(String.format("%04d.jpg", i)))) break;
			n = i;
		}
		return n;
	}

	@Override
	protected boolean customBackground() {
		return true;
	}

	@Override
	protected void drawBackground(Gfx g, float partialTick) {
		GfxCanvas c = GfxCanvas.of(g, Mc.mc().font);
		c.fill(0, 0, width, height, 0xFF000000);
	}

	@Override
	protected void draw(Gfx g, int mouseX, int mouseY, float partialTick) {
		if (finished) return;
		if (frameCount <= 0) {
			finish();
			return;
		}
		if (startNanos < 0) {
			startNanos = System.nanoTime();
			startAudio();
		}
		long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
		int frame = (int) (elapsedMs * FPS / 1000L) + 1;
		if (frame > frameCount) {
			finish();
			return;
		}
		GfxCanvas c = GfxCanvas.of(g, Mc.mc().font);
		c.fill(0, 0, width, height, 0xFF000000);
		if (frame != currentFrame) {
			loadFrame(frame);
			currentFrame = frame;
		}
		if (tex != null && texW > 0 && texH > 0 && c.images()) {
			float s = Math.max(width / (float) texW, height / (float) texH);
			float dw = texW * s;
			float dh = texH * s;
			Affine.image(c, tex, (width - dw) / 2f, (height - dh) / 2f, dw, dh, 0, 0, texW, texH, 0xFFFFFFFF);
		}
	}

	/** Ein JPEG-Bild via STBImage entschlüsseln und als Textur hochladen (gleicher Name → an Ort und Stelle ersetzt). */
	private void loadFrame(int frame) {
		Path d = Miyako.get().framesDir();
		if (d == null) return;
		Path file = d.resolve(String.format("%04d.jpg", frame));
		ByteBuffer raw = null;
		ByteBuffer pixels = null;
		try {
			byte[] bytes = Files.readAllBytes(file);
			raw = MemoryUtil.memAlloc(bytes.length);
			raw.put(bytes).flip();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer w = stack.mallocInt(1);
				IntBuffer h = stack.mallocInt(1);
				IntBuffer comp = stack.mallocInt(1);
				pixels = STBImage.stbi_load_from_memory(raw, w, h, comp, 4);
				if (pixels == null) return;
				int iw = w.get(0);
				int ih = h.get(0);
				int[] argb = new int[iw * ih];
				for (int i = 0; i < argb.length; i++) {
					int r = pixels.get(i * 4) & 0xFF;
					int gg = pixels.get(i * 4 + 1) & 0xFF;
					int b = pixels.get(i * 4 + 2) & 0xFF;
					int a = pixels.get(i * 4 + 3) & 0xFF;
					argb[i] = (a << 24) | (r << 16) | (gg << 8) | b;
				}
				Textures.Store store = Textures.store();
				if (store != null) {
					tex = store.upload(TEX, iw, ih, argb);
					texW = iw;
					texH = ih;
				}
			}
		} catch (Exception | LinkageError ignored) {
			// Ein fehlendes/kaputtes Bild überspringt nur diesen Frame.
		} finally {
			if (pixels != null) STBImage.stbi_image_free(pixels);
			if (raw != null) MemoryUtil.memFree(raw);
		}
	}

	/** intro.ogg via STBVorbis entschlüsseln und über OpenAL abspielen (MC-Kontext). */
	private void startAudio() {
		if (audioTried) return;
		audioTried = true;
		Path file = Miyako.get().audioFile();
		if (file == null || !Files.isRegularFile(file)) return;
		ByteBuffer raw = null;
		ShortBuffer pcm = null;
		try {
			byte[] bytes = Files.readAllBytes(file);
			raw = MemoryUtil.memAlloc(bytes.length);
			raw.put(bytes).flip();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer channels = stack.mallocInt(1);
				IntBuffer rate = stack.mallocInt(1);
				pcm = STBVorbis.stb_vorbis_decode_memory(raw, channels, rate);
				if (pcm == null) return;
				int format = channels.get(0) == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
				alBuffer = AL10.alGenBuffers();
				AL10.alBufferData(alBuffer, format, pcm, rate.get(0));
				alSource = AL10.alGenSources();
				AL10.alSourcei(alSource, AL10.AL_BUFFER, alBuffer);
				AL10.alSourcef(alSource, AL10.AL_GAIN, 1.0f);
				AL10.alSourcePlay(alSource);
			}
		} catch (Exception | LinkageError ignored) {
			// Ohne Ton läuft das Video stumm weiter.
		} finally {
			if (pcm != null) {
				try {
					org.lwjgl.system.libc.LibCStdlib.free(pcm);
				} catch (Exception | LinkageError ignored) {
					// egal
				}
			}
			if (raw != null) MemoryUtil.memFree(raw);
		}
	}

	private void stopAudio() {
		try {
			if (alSource != 0) {
				AL10.alSourceStop(alSource);
				AL10.alDeleteSources(alSource);
				alSource = 0;
			}
			if (alBuffer != 0) {
				AL10.alDeleteBuffers(alBuffer);
				alBuffer = 0;
			}
		} catch (Exception | LinkageError ignored) {
			// egal
		}
	}

	private void finish() {
		if (finished) return;
		finished = true;
		stopAudio();
		Miyako.get().introFinished();
		Mc.setScreen(parent);
	}

	// --- Nicht überspringbar ---

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	protected boolean onKey(int key, int modifiers) {
		return true;
	}

	@Override
	protected boolean onChar(char c) {
		return true;
	}

	@Override
	protected boolean onClick(double mouseX, double mouseY, int button) {
		return true;
	}

	@Override
	protected boolean onRelease(double mouseX, double mouseY, int button) {
		return true;
	}

	@Override
	public void removed() {
		stopAudio();
		Textures.Store store = Textures.store();
		if (store != null && tex != null) {
			try {
				store.release(tex);
			} catch (RuntimeException | LinkageError ignored) {
				// egal
			}
			tex = null;
		}
		super.removed();
	}
}
