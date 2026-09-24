package dev.theredstonee.trsclient.render;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.render.ColorGrade;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import java.nio.FloatBuffer;

/**
 * Modul „Farben“: Vollbild-Nachbearbeitung des Spielbilds direkt nach der Welt (samt Hand), vor HUD und
 * Menüs. Ein eigener, kleiner GL-Durchgang: das Bild der Haupt-Zeichenfläche wird in eine eigene Textur
 * kopiert und mit dem Farb-Shader ({@link ColorGrade}) zurückgeschrieben.
 *
 * <p>Alles läuft über rohes OpenGL mit eigenem Programm, VAO, VBO und Framebuffer; jeder veränderte
 * GL-Zustand wird vorher abgefragt und danach exakt wiederhergestellt, damit Minecrafts Zustands-Cache
 * (GlStateManager) stimmt. Die Textur-ID der Haupt-Zeichenfläche: Feld bis 1.15, {@code getColorTextureId()}
 * 1.16–1.21.4, {@code GlTexture#glId()} ab 1.21.5 (26.3: {@code com.mojang.renderpearl}). Mit dem
 * Vulkan-Backend (26.2+) gibt es keine GL-Textur – dann bleibt das Modul aus.
 */
public final class ColorPass {
	private static final ColorGrade GRADE = new ColorGrade();
	private static final float[] MATRIX = new float[12];
	/** Wie oft der Durchgang lief (Selbsttest). */
	public static int applied;

	private static boolean failed;
	private static int program;
	private static int vao;
	private static int vbo;
	private static int fbo;
	private static int copyTex;
	private static int copyW;
	private static int copyH;
	private static int locScene;
	private static int locRowR;
	private static int locRowG;
	private static int locRowB;
	private static int locVibrance;
	private static int attachedTex;
	/** Wiederverwendete Puffer für das Sichern des Zustands (keine Allokation je Bild). */
	private static final java.nio.IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
	private static final java.nio.ByteBuffer COLOR_MASK = BufferUtils.createByteBuffer(16);

	private ColorPass() {
	}

	/** Gibt es hier eine OpenGL-Textur der Haupt-Zeichenfläche (sonst Modul ausblenden)? */
	public static boolean supported() {
		if (failed) return false;
		try {
			return mainTexture() > 0;
		} catch (Throwable t) {
			return false;
		}
	}

	/** Nach dem Zeichnen der Welt, vor HUD und Menüs (aus dem GameRenderer-Mixin). */
	public static void afterLevel() {
		if (failed) return;
		TrsClient client = TrsClient.get();
		if (client == null) return;
		TrsModules modules = client.modules();
		if (!modules.colors.isEnabled()) return;
		// Shaderpack aktiv (Iris/Oculus/OptiFine): das Pack macht seine eigene Nachbearbeitung – nicht doppelt färben.
		if (dev.theredstonee.trsclient.core.perf.ShaderPacks.active()) return;
		modules.colorGrade(GRADE);
		if (GRADE.identity()) return;
		try {
			if (apply()) applied++;
		} catch (Throwable t) {
			failed = true;
			TrsClient.LOGGER.warn("TRS Client: color grading switched off (" + t + ")");
		}
	}

	private static boolean apply() {
		com.mojang.blaze3d.pipeline.RenderTarget target = mainTarget();
		if (target == null) return false;
		int tex = mainTexture();
		int w = target.width;
		int h = target.height;
		if (tex <= 0 || w <= 0 || h <= 0) return false;
		if (!GL.getCapabilities().OpenGL30) throw new IllegalStateException("OpenGL 3.0 missing");
		if (program == 0) setup();

		// --- Zustand sichern ---
		int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		GL13.glActiveTexture(GL13.GL_TEXTURE0);
		int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		boolean samplers = GL.getCapabilities().OpenGL33;
		int prevSampler = samplers ? GL11.glGetInteger(GL33.GL_SAMPLER_BINDING) : 0;
		int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
		int prevVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
		VIEWPORT.clear();
		GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
		int vx = VIEWPORT.get(0), vy = VIEWPORT.get(1), vw = VIEWPORT.get(2), vh = VIEWPORT.get(3);
		boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
		boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
		boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
		boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
		java.nio.ByteBuffer colorMask = COLOR_MASK;
		colorMask.clear();
		GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
		try {
			// Kopie des Bilds
			if (copyTex == 0 || copyW != w || copyH != h) resize(w, h);
			if (attachedTex != tex) {
				GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
				GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
				attachedTex = tex;
			}
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
			if (samplers) GL33.glBindSampler(0, 0);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyTex);
			GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);

			// Zurückschreiben mit Farbmatrix
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
			GL11.glViewport(0, 0, w, h);
			GL11.glDisable(GL11.GL_BLEND);
			GL11.glDisable(GL11.GL_DEPTH_TEST);
			GL11.glDisable(GL11.GL_CULL_FACE);
			GL11.glDisable(GL11.GL_SCISSOR_TEST);
			GL11.glDepthMask(false);
			GL11.glColorMask(true, true, true, true);
			GL20.glUseProgram(program);
			GRADE.matrix(MATRIX);
			GL20.glUniform1i(locScene, 0);
			GL20.glUniform4f(locRowR, MATRIX[0], MATRIX[1], MATRIX[2], MATRIX[3]);
			GL20.glUniform4f(locRowG, MATRIX[4], MATRIX[5], MATRIX[6], MATRIX[7]);
			GL20.glUniform4f(locRowB, MATRIX[8], MATRIX[9], MATRIX[10], MATRIX[11]);
			GL20.glUniform1f(locVibrance, GRADE.vibrance);
			GL30.glBindVertexArray(vao);
			GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
			return true;
		} finally {
			// --- Zustand wiederherstellen ---
			GL30.glBindVertexArray(prevVao);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevVbo);
			GL20.glUseProgram(prevProgram);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
			if (samplers) GL33.glBindSampler(0, prevSampler);
			GL13.glActiveTexture(prevActive);
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
			GL11.glViewport(vx, vy, vw, vh);
			toggle(GL11.GL_BLEND, blend);
			toggle(GL11.GL_DEPTH_TEST, depth);
			toggle(GL11.GL_CULL_FACE, cull);
			toggle(GL11.GL_SCISSOR_TEST, scissor);
			GL11.glDepthMask(depthMask);
			GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
		}
	}

	private static void toggle(int cap, boolean on) {
		if (on) GL11.glEnable(cap);
		else GL11.glDisable(cap);
	}

	/** Programm, Dreieck und Framebuffer einmalig anlegen. */
	private static void setup() {
		boolean core = core();
		int vs = shader(GL20.GL_VERTEX_SHADER, core ? ColorGrade.VERTEX_150 : ColorGrade.VERTEX_120);
		int fs = shader(GL20.GL_FRAGMENT_SHADER, core ? ColorGrade.FRAGMENT_150 : ColorGrade.FRAGMENT_120);
		int p = GL20.glCreateProgram();
		GL20.glAttachShader(p, vs);
		GL20.glAttachShader(p, fs);
		GL20.glBindAttribLocation(p, 0, "Position");
		GL20.glLinkProgram(p);
		GL20.glDeleteShader(vs);
		GL20.glDeleteShader(fs);
		if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
			String log = GL20.glGetProgramInfoLog(p, 2048);
			GL20.glDeleteProgram(p);
			throw new IllegalStateException("link: " + log);
		}
		locScene = GL20.glGetUniformLocation(p, "Scene");
		locRowR = GL20.glGetUniformLocation(p, "RowR");
		locRowG = GL20.glGetUniformLocation(p, "RowG");
		locRowB = GL20.glGetUniformLocation(p, "RowB");
		locVibrance = GL20.glGetUniformLocation(p, "Vibrance");

		int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
		int prevVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
		vao = GL30.glGenVertexArrays();
		vbo = GL15.glGenBuffers();
		GL30.glBindVertexArray(vao);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
		FloatBuffer data = BufferUtils.createFloatBuffer(ColorGrade.TRIANGLE.length);
		data.put(ColorGrade.TRIANGLE).flip();
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
		GL20.glEnableVertexAttribArray(0);
		GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L);
		GL30.glBindVertexArray(prevVao);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevVbo);
		fbo = GL30.glGenFramebuffers();
		program = p;
	}

	private static int shader(int type, String source) {
		int s = GL20.glCreateShader(type);
		GL20.glShaderSource(s, source);
		GL20.glCompileShader(s);
		if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
			String log = GL20.glGetShaderInfoLog(s, 2048);
			GL20.glDeleteShader(s);
			throw new IllegalStateException("compile: " + log);
		}
		return s;
	}

	/** Kopie-Textur in Fenstergröße (neu bei Größenänderung). */
	private static void resize(int w, int h) {
		if (copyTex == 0) copyTex = GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyTex);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
				(java.nio.ByteBuffer) null);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
		copyW = w;
		copyH = h;
	}

	private static final int GL12_CLAMP_TO_EDGE = 0x812F;

	/** Core-Profil (GLSL 1.50) ab 1.17, davor Kompatibilitätsprofil (GLSL 1.20). */
	private static boolean core() {
		//? if >=1.17 {
		return true;
		//?} else {
		/*return false;
		*///?}
	}

	private static com.mojang.blaze3d.pipeline.RenderTarget mainTarget() {
		Minecraft mc = Minecraft.getInstance();
		//? if >=26.2 {
		/*return mc.gameRenderer == null ? null : mc.gameRenderer.mainRenderTarget();
		*///?} else {
		return mc.getMainRenderTarget();
		//?}
	}

	/** OpenGL-Name der Farbtextur der Haupt-Zeichenfläche (0 = keine, z. B. Vulkan). */
	private static int mainTexture() {
		com.mojang.blaze3d.pipeline.RenderTarget target = mainTarget();
		if (target == null) return 0;
		//? if >=26.3 {
		/*Object tex = target.getColorTexture();
		return tex instanceof com.mojang.renderpearl.backend.opengl.GlTexture
				? ((com.mojang.renderpearl.backend.opengl.GlTexture) tex).glId() : 0;
		*///?} elif >=1.21.5 {
		/*Object tex = target.getColorTexture();
		return tex instanceof com.mojang.blaze3d.opengl.GlTexture ? ((com.mojang.blaze3d.opengl.GlTexture) tex).glId() : 0;
		*///?} elif >=1.16 {
		return target.getColorTextureId();
		//?} else {
		/*return target.colorTextureId;
		*///?}
	}
}
