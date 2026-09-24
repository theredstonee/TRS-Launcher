package dev.theredstonee.trsclient.render;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.render.ColorGrade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.client.shader.ShaderManager;
import net.minecraft.client.shader.ShaderUniform;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * Modul „Farben“ unter Forge 1.8.9–1.12.2: eigene {@link ShaderGroup} (wie Vanillas Spektator-Shader,
 * aber getrennt davon) aus zwei Durchgängen – {@code trsclient_color} (Farbmatrix + Dynamik, siehe
 * {@link ColorGrade}) von der Haupt-Zeichenfläche in „swap“, dann {@code blit} zurück. Die Uniforms werden
 * jedes Bild aus den Einstellungen gesetzt.
 *
 * <p>Läuft einmal je Bild nach Welt und Hand: im {@code RenderGameOverlayEvent.Pre} (ALL), bei
 * ausgeblendeter Oberfläche (F1) am Ende des Render-Ticks. Das Programm liegt unter
 * {@code assets/minecraft/shaders/program/trsclient_color.*} (vor 1.11 kennt der Shader-Lader nur den
 * Minecraft-Namensraum).
 */
public final class ColorPass {
	private static final ColorGrade GRADE = new ColorGrade();
	private static final float[] MATRIX = new float[12];
	/** Wie oft der Durchgang lief (Selbsttest). */
	public static int applied;

	private static ShaderGroup group;
	private static Shader pass;
	private static int width = -1;
	private static int height = -1;
	private static boolean failed;
	private static boolean done;

	private ColorPass() {
	}

	/** Kann diese Grafikkarte/Einstellung den Durchgang? (Shader und Framebuffer nötig.) */
	public static boolean supported() {
		return !failed && OpenGlHelper.shadersSupported;
	}

	/** Beginn eines Bilds (Render-Tick START). */
	public static void frameStart() {
		done = false;
	}

	/** Welt und Hand sind gezeichnet, HUD und Menüs noch nicht. */
	public static void afterLevel(float partialTicks) {
		if (done || failed) return;
		done = true;
		TrsClient client = TrsClient.get();
		if (client == null) return;
		TrsModules modules = client.modules();
		if (!modules.colors.isEnabled()) return;
		modules.colorGrade(GRADE);
		if (GRADE.identity()) return;
		if (!OpenGlHelper.shadersSupported || !OpenGlHelper.isFramebufferEnabled()) return;
		Minecraft mc = Minecraft.getMinecraft();
		if (dev.theredstonee.trsclient.compat.Mc.world() == null) return;
		try {
			apply(mc, partialTicks);
			applied++;
		} catch (Exception e) {
			failed = true;
			TrsClient.LOGGER.warn("TRS Client: color grading switched off (" + e + ")");
			release();
		}
	}

	private static void apply(Minecraft mc, float partialTicks) throws Exception {
		Framebuffer main = mc.getFramebuffer();
		if (group == null) {
			group = new ShaderGroup(mc.getTextureManager(), mc.getResourceManager(), main,
					new ResourceLocation("trsclient", "shaders/post/color.json"));
			Framebuffer swap = group.getFramebufferRaw("swap");
			pass = group.addShader("trsclient_color", main, swap);
			group.addShader("blit", swap, main);
			width = -1;
			height = -1;
		}
		if (width != mc.displayWidth || height != mc.displayHeight) {
			width = mc.displayWidth;
			height = mc.displayHeight;
			group.createBindFramebuffers(width, height);
		}
		GRADE.matrix(MATRIX);
		ShaderManager shader = pass.getShaderManager();
		set4(shader, "RowR", 0);
		set4(shader, "RowG", 4);
		set4(shader, "RowB", 8);
		ShaderUniform vibrance = shader.getShaderUniform("Vibrance");
		if (vibrance != null) vibrance.set(GRADE.vibrance);

		GlStateManager.matrixMode(GL11.GL_TEXTURE);
		GlStateManager.pushMatrix();
		GlStateManager.loadIdentity();
		try {
			//? if >=1.11 {
			/*group.render(partialTicks);
			*///?} else {
			group.loadShaderGroup(partialTicks);
			//?}
		} finally {
			GlStateManager.popMatrix();
			GlStateManager.matrixMode(GL11.GL_MODELVIEW);
			main.bindFramebuffer(true);
		}
	}

	private static void set4(ShaderManager shader, String name, int offset) {
		ShaderUniform u = shader.getShaderUniform(name);
		if (u != null) u.set(MATRIX[offset], MATRIX[offset + 1], MATRIX[offset + 2], MATRIX[offset + 3]);
	}

	/** Shader-Gruppe freigeben (Ressourcen neu geladen oder Fehler). */
	public static void release() {
		if (group != null) {
			try {
				group.deleteShaderGroup();
			} catch (RuntimeException ignored) {
				// nichts mehr zu retten
			}
		}
		group = null;
		pass = null;
	}
}
