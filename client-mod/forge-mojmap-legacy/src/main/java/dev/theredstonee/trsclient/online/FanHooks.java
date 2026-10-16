package dev.theredstonee.trsclient.online;

import dev.theredstonee.trsclient.core.creator.CreatorTier;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Creator-Programm im Spiel: Fan-Symbol (Bild) und Creator-Stern vor dem Namen – über dem Kopf und in der Tabliste.
 *
 * <p>Der Name bekommt vorne Zeichen der eigenen Schrift {@code trsclient:badge}: das TRS-Abzeichen ({@code U+E2A5}),
 * den Stern ({@code U+E2A6}, in der Farbe der Stufe) und einen unsichtbaren Platzhalter ({@code U+E2A7}, so breit wie
 * das Symbol plus Abstand). So rechnet Vanilla Breite, Hintergrund und Layout mit. Das Symbol selbst ist ein Bild und wird
 * genau dort gezeichnet, wo Vanilla den Namen schreibt: Die erweiterten Namen merkt sich {@link #DECOR} (Identität der
 * Komponente), die Text-Hooks ({@code FanTextMixin}, {@code FanGuiMixin}, {@code FanNameTagMixin}) zeichnen das Bild an
 * der Stelle des Platzhalters – mit derselben Matrix, Tiefe und Durchsicht wie die Schrift.
 */
public final class FanHooks {
	/** Stern in der eigenen Schrift. */
	public static final String STAR = "\ue2a6";
	/** Unsichtbarer Platzhalter für das Symbol (10 px Zeichen → 11 px Vorschub). */
	public static final String SLOT = "\ue2a7";
	/** Größe des Symbols in Schrift-Pixeln (wie ein Großbuchstabe). */
	public static final int SIZE = 8;

	/** Für Selbsttests: so oft wurde ein Symbol über dem Kopf bzw. in der Oberfläche gezeichnet. */
	public static int worldDraws;
	public static int guiDraws;

	private FanHooks() {
	}

	/** Wo und für wen ein erweiterter Name das Symbol (und das Profil-Abzeichen) zeichnet. */
	static final class Decor {
		final UUID uuid;
		final boolean tab;
		/** Breite von allem vor dem Platzhalter des Fan-Symbols (Schrift-Pixel), −1 = kein Fan-Symbol. */
		final int offset;
		/** Dasselbe für das Profil-Abzeichen (Halloween 2026), −1 = keins. */
		final int badgeOffset;

		Decor(UUID uuid, boolean tab, int offset, int badgeOffset) {
			this.uuid = uuid;
			this.tab = tab;
			this.offset = offset;
			this.badgeOffset = badgeOffset;
		}

		/** Versatz des Bildes {@code k} (0 = Abzeichen, 1 = Fan-Symbol) oder −1. */
		int offset(int k) {
			return k == 0 ? badgeOffset : offset;
		}
	}

	/** Letzter Eingangsname, Zustand und Ergebnis je Spieler und Ort (Tabliste/Namensschild). */
	private static final class Entry {
		Object name;
		int state;
		Object out;
	}

	private static final Map<Object, Decor> DECOR = new IdentityHashMap<>();
	private static final Map<UUID, Entry> TAB = new HashMap<>();
	private static final Map<UUID, Entry> TAGS = new HashMap<>();

	private static OnlineFeatures<Object> features() {
		return OnlineHooks.features();
	}

	/**
	 * Zustand der Zusätze für einen Spieler: Bit 0 Abzeichen, Bit 1–2 Stern-Stufe + 1, Bit 3 Symbol bereit, Bit 4
	 * Profil-Abzeichen (Halloween 2026) bereit.
	 * 0 = nichts davor.
	 */
	private static int state(UUID id, boolean tab) {
		OnlineFeatures<Object> f = features();
		if (f == null || id == null) return 0;
		int s = 0;
		if (f.badge(id, tab)) s |= 1;
		CreatorTier tier = f.creatorStar(id, tab);
		if (tier != null) s |= (tier.ordinal() + 1) << 1;
		//? if >=1.16 {
		if (f.fanSymbol(id, tab) != null) s |= 8;
		if (f.eventBadge(id, tab) != null) s |= 16;
		//?}
		return s;
	}

	//? if >=1.16 {
	private static Component badgeGlyph;
	private static final Component[] STARS = new Component[CreatorTier.values().length];
	private static Component slotGlyph;

	private static net.minecraft.network.chat.MutableComponent glyph(String ch, int rgb) {
		//? if >=1.21.9 {
		/*final net.minecraft.network.chat.FontDescription font = new net.minecraft.network.chat.FontDescription.Resource(OnlineHooks.id("badge"));
		*///?} else
		final net.minecraft.resources.ResourceLocation font = OnlineHooks.id("badge");
		final net.minecraft.network.chat.TextColor color = net.minecraft.network.chat.TextColor.fromRgb(rgb);
		return OnlineHooks.text(ch).withStyle(style -> style.withFont(font).withColor(color));
	}

	private static Component star(CreatorTier tier) {
		Component c = STARS[tier.ordinal()];
		if (c == null) c = STARS[tier.ordinal()] = glyph(STAR, tier.color);
		return c;
	}

	/**
	 * Name mit allen Zusätzen davor (TRS-Abzeichen, Creator-Stern, Platz fürs Fan-Symbol) oder {@code name} selbst, wenn
	 * nichts davor gehört. Solange Name und Zustand gleich bleiben, kommt dieselbe Komponente zurück (keine Objekte je
	 * Bild). Nur Render-Thread.
	 */
	public static Component decorate(UUID id, Component name, boolean tab) {
		if (name == null || id == null) return name;
		try {
			int state = state(id, tab);
			Map<UUID, Entry> cache = tab ? TAB : TAGS;
			Entry e = cache.get(id);
			if (state == 0) {
				if (e != null) {
					DECOR.remove(e.out);
					cache.remove(id);
				}
				return name;
			}
			if (e != null && e.state == state && (e.name == name || e.name.equals(name))) return (Component) e.out;
			if (cache.size() > 512 || DECOR.size() > 1024) {
				TAB.clear();
				TAGS.clear();
				DECOR.clear();
				e = null;
			}
			if (e == null) {
				e = new Entry();
				cache.put(id, e);
			} else {
				DECOR.remove(e.out);
			}
			net.minecraft.network.chat.MutableComponent prefix = OnlineHooks.text("");
			if ((state & 1) != 0) {
				if (badgeGlyph == null) badgeGlyph = glyph(OnlineHooks.GLYPH, 0xFFFFFF);
				prefix.append(badgeGlyph).append(OnlineHooks.text(" "));
			}
			int tier = (state >> 1) & 3;
			if (tier != 0) prefix.append(star(CreatorTier.values()[tier - 1])).append(OnlineHooks.text(" "));
			net.minecraft.network.chat.MutableComponent out = OnlineHooks.text("");
			if (!prefix.getSiblings().isEmpty()) out.append(prefix);
			if ((state & 24) != 0) {
				if (slotGlyph == null) slotGlyph = glyph(SLOT, 0xFFFFFF);
				net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
				int at = prefix.getSiblings().isEmpty() ? 0 : font.width(prefix);
				int badgeAt = -1;
				int fanAt = -1;
				// Profil-Abzeichen vor dem Fan-Symbol, jedes an einem eigenen Platzhalter.
				if ((state & 16) != 0) {
					out.append(slotGlyph);
					badgeAt = at;
					at += font.width(slotGlyph);
				}
				if ((state & 8) != 0) {
					out.append(slotGlyph);
					fanAt = at;
				}
				DECOR.put(out.append(name), new Decor(id, tab, fanAt, badgeAt));
			} else {
				out.append(name);
			}
			e.name = name;
			e.state = state;
			e.out = out;
			return out;
		} catch (RuntimeException ex) {
			if (features() != null) features().online().reportError(ex);
			return name;
		}
	}
	//?} else {
	/*public static Component decorate(UUID id, Component name, boolean tab) {
		if (name == null || id == null) return name;
		String prefix = prefix(id, tab);
		return prefix.isEmpty() ? name : new net.minecraft.network.chat.TextComponent(prefix).append(name);
	}
	*///?}

	/**
	 * Zusätze als Text mit Farbcodes (bis 1.15 ohne eigene Schrift, Namensschild als Text): dunkelrotes Quadrat für das
	 * Abzeichen, farbiger Stern (\u2605) für Creator. Fan-Symbole gibt es dort nicht.
	 */
	public static String prefix(UUID id, boolean tab) {
		int state = state(id, tab) & 7;
		if (state == 0) return "";
		StringBuilder sb = new StringBuilder();
		if ((state & 1) != 0) sb.append(OnlineHooks.LEGACY_BADGE);
		int tier = (state >> 1) & 3;
		if (tier != 0) sb.append(LEGACY_STAR[tier - 1]).append("\u2605\u00a7r ");
		return sb.toString();
	}

	/** Farbcodes der Stern-Stufen ohne eigene Schrift: Starter hellblau, Partner violett, Top gold. */
	private static final String[] LEGACY_STAR = {"\u00a7b", "\u00a7d", "\u00a76"};

	/** Name als Text (Namensschild bis 1.15) mit den Zusätzen davor. */
	public static String decorate(UUID id, String name, boolean tab) {
		if (name == null || id == null) return name;
		String prefix = prefix(id, tab);
		return prefix.isEmpty() ? name : prefix + name;
	}

	/** Gehört zu dieser Komponente ein Fan-Symbol? (Schnelltest für die Text-Hooks.) */
	static Decor decor(Object text) {
		return DECOR.isEmpty() || text == null ? null : DECOR.get(text);
	}

	//? if >=1.16 {
	/** Bild {@code k} (0 = Profil-Abzeichen, 1 = Fan-Symbol) zu einem erweiterten Namen, sonst null. */
	private static TextureRef texture(Decor d, int k) {
		if (d.offset(k) < 0) return null;
		OnlineFeatures<Object> f = features();
		if (f == null) return null;
		return k == 0 ? f.eventBadge(d.uuid, d.tab) : f.fanSymbol(d.uuid, d.tab);
	}

	/** Breite des Bildes in Schrift-Pixeln (Symbol quadratisch, Abzeichen nach Seitenverhältnis). */
	private static int width(TextureRef tex, int k) {
		return k == 0 ? dev.theredstonee.trsclient.core.creator.EventBadges.widthFor(tex, SIZE) : SIZE;
	}
	//?}

	// --- Welt: Namensschilder über dem Kopf ---

	//? if >=1.16 && <26.2 {
	/**
	 * Aus {@code Font#drawInBatch(Component, …)}: gehört der Text zu einem erweiterten Namen, das Symbol an der Stelle des
	 * Platzhalters in denselben Puffer zeichnen – gleiche Matrix, gleicher Durchsicht-Modus, gleiche Deckkraft.
	 */
	public static void world(Object text, float x, float y, int color,
			//? if >=26.1 {
			/*org.joml.Matrix4fc pose,
			*///?} elif >=1.19.3 {
			/*org.joml.Matrix4f pose,
			*///?} else
			com.mojang.math.Matrix4f pose,
			net.minecraft.client.renderer.MultiBufferSource buffers, boolean seeThrough, int light) {
		Decor d = decor(text);
		if (d == null) return;
		try {
			for (int k = 0; k < 2; k++) {
				TextureRef tex = texture(d, k);
				if (tex == null) continue;
				com.mojang.blaze3d.vertex.VertexConsumer vc = buffers.getBuffer(renderType(tex.id, seeThrough));
				float x0 = x + d.offset(k);
				float y0 = y;
				float x1 = x0 + width(tex, k);
				float y1 = y0 + SIZE;
				// Deckkraft wie die Schrift (Vanilla: Alpha 0 = voll deckend).
				int a = (color & 0xFC000000) == 0 ? 255 : (color >>> 24);
				vertex(vc, pose, x0, y0, 0f, 0f, a, light);
				vertex(vc, pose, x0, y1, 0f, 1f, a, light);
				vertex(vc, pose, x1, y1, 1f, 1f, a, light);
				vertex(vc, pose, x1, y0, 1f, 0f, a, light);
				worldDraws++;
			}
		} catch (RuntimeException e) {
			if (features() != null) features().online().reportError(e);
		}
	}

	private static void vertex(com.mojang.blaze3d.vertex.VertexConsumer vc,
			//? if >=26.1 {
			/*org.joml.Matrix4fc pose,
			*///?} elif >=1.19.3 {
			/*org.joml.Matrix4f pose,
			*///?} else
			com.mojang.math.Matrix4f pose,
			float x, float y, float u, float v, int alpha, int light) {
		//? if >=1.21 {
		/*vc.addVertex(pose, x, y, 0f).setColor(255, 255, 255, alpha).setUv(u, v).setLight(light);
		*///?} else {
		vc.vertex(pose, x, y, 0f).color(255, 255, 255, alpha).uv(u, v).uv2(light).endVertex();
		//?}
	}
	//?}

	//? if >=1.16 {
	/** Schrift-Render-Typ mit der Symbol-Textur (normal bzw. durch Wände sichtbar). */
	//? if >=1.21.11 {
	/*static net.minecraft.client.renderer.rendertype.RenderType renderType(Object tex, boolean seeThrough) {
		net.minecraft.resources.Identifier id = (net.minecraft.resources.Identifier) tex;
		return seeThrough ? net.minecraft.client.renderer.rendertype.RenderTypes.textSeeThrough(id)
				: net.minecraft.client.renderer.rendertype.RenderTypes.text(id);
	}
	*///?} else {
	static net.minecraft.client.renderer.RenderType renderType(Object tex, boolean seeThrough) {
		net.minecraft.resources.ResourceLocation id = (net.minecraft.resources.ResourceLocation) tex;
		return seeThrough ? net.minecraft.client.renderer.RenderType.textSeeThrough(id)
				: net.minecraft.client.renderer.RenderType.text(id);
	}
	//?}
	//?}

	//? if >=26.2 {
	/*/^*
	 * Ab 26.2 aus {@code SubmitNodeCollection#submitNameTag}: Lage des Namensschilds wie Vanilla berechnen und das Symbol
	 * als eigene Geometrie einreichen (normal und – wenn das Schild durch Wände zu sehen ist – halbdurchsichtig).
	 *^/
	public static void submitNameTag(net.minecraft.client.renderer.OrderedSubmitNodeCollector collector, com.mojang.blaze3d.vertex.PoseStack poseStack,
			net.minecraft.world.phys.Vec3 attachment, int offset, Component name, boolean seeThrough, int light,
			net.minecraft.client.renderer.state.level.CameraRenderState camera) {
		Decor d = decor(name);
		if (d == null || attachment == null) return;
		try {
			net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
			poseStack.pushPose();
			try {
				poseStack.translate(attachment.x, attachment.y + 0.5, attachment.z);
				orient(poseStack, camera.orientation);
				poseStack.scale(0.025F, -0.025F, 0.025F);
				for (int k = 0; k < 2; k++) {
					TextureRef tex = texture(d, k);
					if (tex == null) continue;
					final float x0 = -mc.font.width(name) / 2.0F + d.offset(k);
					final float y0 = offset;
					final float w = width(tex, k);
					collector.submitCustomGeometry(poseStack, renderType(tex.id, false),
							(pose, vc) -> quad(vc, pose, x0, y0, w, seeThrough ? 255 : 128, light));
					if (seeThrough) {
						collector.submitCustomGeometry(poseStack, renderType(tex.id, true),
								(pose, vc) -> quad(vc, pose, x0, y0, w, 128, light));
					}
					worldDraws++;
				}
			} finally {
				poseStack.popPose();
			}
		} catch (RuntimeException e) {
			if (features() != null) features().online().reportError(e);
		}
	}

	private static void quad(com.mojang.blaze3d.vertex.VertexConsumer vc, com.mojang.blaze3d.vertex.PoseStack.Pose pose,
			float x0, float y0, float w, int alpha, int light) {
		float x1 = x0 + w;
		float y1 = y0 + SIZE;
		vc.addVertex(pose, x0, y0, 0f).setColor(255, 255, 255, alpha).setUv(0f, 0f).setLight(light);
		vc.addVertex(pose, x0, y1, 0f).setColor(255, 255, 255, alpha).setUv(0f, 1f).setLight(light);
		vc.addVertex(pose, x1, y1, 0f).setColor(255, 255, 255, alpha).setUv(1f, 1f).setLight(light);
		vc.addVertex(pose, x1, y0, 0f).setColor(255, 255, 255, alpha).setUv(1f, 0f).setLight(light);
	}
	*///?}

	//? if >=26.3 {
	/*private static void orient(com.mojang.blaze3d.vertex.PoseStack poseStack, org.joml.Quaternionf q) {
		poseStack.rotate(q);
	}
	*///?} elif >=26.2 {
	/*private static void orient(com.mojang.blaze3d.vertex.PoseStack poseStack, org.joml.Quaternionf q) {
		poseStack.mulPose(q);
	}
	*///?}

	// --- Menüs: Tabliste ---

	//? if >=1.16 {
	/**
	 * Aus den Text-Aufrufen der Oberfläche (GuiGraphics#drawString ab 1.20, Font#drawShadow mit PoseStack davor):
	 * gehört der Text zu einem erweiterten Namen, das Symbol an der Stelle des Platzhalters zeichnen (8×8 Pixel).
	 * {@code raw} = GuiGraphicsExtractor/GuiGraphics bzw. PoseStack.
	 */
	public static void gui(Object raw, Object text, int x, int y) {
		Decor d = decor(text);
		if (d == null) return;
		for (int k = 0; k < 2; k++) {
			TextureRef tex = texture(d, k);
			if (tex != null) gui(raw, tex, x + d.offset(k), y, width(tex, k));
		}
	}

	/** Ein Bild (Symbol oder Abzeichen), {@code w}×8 Pixel, an (px, y). */
	private static void gui(Object raw, TextureRef tex, int px, int y, int w) {
		try {
			int s = tex.width;
			int sh = tex.height;
			guiDraws++;
			//? if >=26.1 {
			/*((net.minecraft.client.gui.GuiGraphicsExtractor) raw).blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
					(net.minecraft.resources.Identifier) tex.id, px, y, 0f, 0f, w, SIZE, s, sh, s, sh);
			*///?} elif >=1.21.11 {
			/*((net.minecraft.client.gui.GuiGraphics) raw).blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
					(net.minecraft.resources.Identifier) tex.id, px, y, 0f, 0f, w, SIZE, s, sh, s, sh);
			*///?} elif >=1.21.6 {
			/*((net.minecraft.client.gui.GuiGraphics) raw).blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
					(net.minecraft.resources.ResourceLocation) tex.id, px, y, 0f, 0f, w, SIZE, s, sh, s, sh);
			*///?} elif >=1.21.2 {
			/*((net.minecraft.client.gui.GuiGraphics) raw).blit(net.minecraft.client.renderer.RenderType::guiTextured,
					(net.minecraft.resources.ResourceLocation) tex.id, px, y, 0f, 0f, w, SIZE, s, sh, s, sh);
			*///?} elif >=1.20 {
			/*net.minecraft.client.gui.GuiGraphics g = (net.minecraft.client.gui.GuiGraphics) raw;
			g.flush();
			com.mojang.blaze3d.systems.RenderSystem.enableBlend();
			com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
			g.blit((net.minecraft.resources.ResourceLocation) tex.id, px, y, w, SIZE, 0f, 0f, s, sh, s, sh);
			com.mojang.blaze3d.systems.RenderSystem.disableBlend();
			*///?} elif >=1.17 {
			/*com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, (net.minecraft.resources.ResourceLocation) tex.id);
			com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			com.mojang.blaze3d.systems.RenderSystem.enableBlend();
			com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
			net.minecraft.client.gui.GuiComponent.blit((com.mojang.blaze3d.vertex.PoseStack) raw, px, y, w, SIZE, 0f, 0f, s, sh, s, sh);
			com.mojang.blaze3d.systems.RenderSystem.disableBlend();
			*///?} else {
			net.minecraft.client.Minecraft.getInstance().getTextureManager().bind((net.minecraft.resources.ResourceLocation) tex.id);
			com.mojang.blaze3d.systems.RenderSystem.color4f(1f, 1f, 1f, 1f);
			com.mojang.blaze3d.systems.RenderSystem.enableBlend();
			com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
			net.minecraft.client.gui.GuiComponent.blit((com.mojang.blaze3d.vertex.PoseStack) raw, px, y, w, SIZE, 0f, 0f, s, sh, s, sh);
			com.mojang.blaze3d.systems.RenderSystem.disableBlend();
			//?}
		} catch (RuntimeException e) {
			if (features() != null) features().online().reportError(e);
		}
	}
	//?}
}
