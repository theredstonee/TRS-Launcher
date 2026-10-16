package dev.theredstonee.trsclient.online;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.creator.CreatorTier;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;
//? if >=1.12 {
/*import net.minecraft.client.renderer.BufferBuilder;
*///?} elif >=1.9 {
/*import net.minecraft.client.renderer.VertexBuffer;
*///?} else
import net.minecraft.client.renderer.WorldRenderer;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

/**
 * Creator-Programm in Legacy-Forge 1.8.9–1.12.2: Creator-Stern (\u2605 in der Farbe der Stufe) und Fan-Symbol vor dem Namen –
 * über dem Kopf und in der Tabliste. Die Schrift dieser Versionen kennt keine eigenen Zeichen: Vor dem Namen steht ein
 * unsichtbarer Marker ({@link #MARK}) und drei Leerzeichen Platz; das Symbol wird danach als Bild an genau diese Stelle
 * gezeichnet – über dem Kopf nach {@code RenderLivingEvent.Specials.Post} mit der Lage des Namensschilds wie Vanilla,
 * in der Tabliste nach {@code RenderGameOverlayEvent.Post(PLAYER_LIST)} mit dem Vanilla-Layout der Liste.
 */
public final class LegacyFans {
	/** Unsichtbarer Marker vor dem Platz fürs Symbol (nur Formatcodes, Breite 0). */
	public static final String MARK = "\u00a7r\u00a7k\u00a7r";
	/** Platz fürs Symbol: 3 Leerzeichen = 12 px (Symbol 8 px, 1 px davor, 3 px danach). */
	public static final String SLOT = MARK + "   ";
	/** Unsichtbare Marke + Platz fürs Profil-Abzeichen (Halloween 2026), steht vor dem Fan-Symbol. */
	public static final String BADGE_MARK = "\u00a7r\u00a7m\u00a7r";
	public static final String BADGE_SLOT = BADGE_MARK + "   ";
	/** Farbcodes der Stern-Stufen: Starter hellblau, Partner violett, Top gold. */
	private static final String[] STAR = {"\u00a7b", "\u00a7d", "\u00a76"};

	/** Für Selbsttests: so oft wurde ein Symbol über dem Kopf bzw. in der Tabliste gezeichnet. */
	public static int worldDraws;
	public static int tabDraws;

	private static Field footerField;
	private static Field headerField;
	private static Field orderingField;
	private static boolean reflectionFailed;

	public LegacyFans() {
	}

	private static OnlineFeatures<Object> features() {
		return LegacyOnline.features();
	}

	/**
	 * Zusätze vor dem Namen als Text: TRS-Abzeichen, Creator-Stern, Platz fürs Fan-Symbol (nur wenn das Bild schon geladen
	 * ist). Leer = nichts davor. {@code tab}: Tabliste, sonst Namensschild.
	 */
	public static String prefix(UUID id, boolean tab) {
		OnlineFeatures<Object> f = features();
		if (f == null || id == null) return "";
		StringBuilder sb = new StringBuilder();
		if (f.badge(id, tab)) sb.append(LegacyOnline.BADGE).append("\u00a7r ");
		CreatorTier tier = f.creatorStar(id, tab);
		if (tier != null) sb.append(STAR[tier.ordinal()]).append("\u2605\u00a7r ");
		if (f.eventBadge(id, tab) != null) sb.append(BADGE_SLOT);
		if (f.fanSymbol(id, tab) != null) sb.append(SLOT);
		return sb.toString();
	}

	/** Breite des Texts vor dem Platzhalter in {@code text} (Schrift-Pixel) oder −1 ohne Platzhalter. */
	static int offset(FontRenderer font, String text) {
		return offset(font, text, MARK);
	}

	/** Wie {@link #offset(FontRenderer, String)} für eine bestimmte Marke. */
	static int offset(FontRenderer font, String text, String mark) {
		if (text == null) return -1;
		int i = text.indexOf(mark);
		return i < 0 ? -1 : font.getStringWidth(text.substring(0, i)) + 1;
	}

	/** Bild {@code k} (0 = Profil-Abzeichen, 1 = Fan-Symbol) eines Spielers oder null. */
	private static TextureRef image(OnlineFeatures<Object> f, UUID id, boolean tab, int k) {
		return k == 0 ? f.eventBadge(id, tab) : f.fanSymbol(id, tab);
	}

	/** Breite in Schrift-Pixeln (Symbol 8, Abzeichen nach Seitenverhältnis). */
	private static int width(TextureRef tex, int k) {
		return k == 0 ? dev.theredstonee.trsclient.core.creator.EventBadges.widthFor(tex, 8) : 8;
	}

	// --- Über dem Kopf ---

	@SubscribeEvent
	@SuppressWarnings("rawtypes")
	public void onNameTag(RenderLivingEvent.Specials.Post event) {
		try {
			//? if >=1.9 {
			/*Object entity = event.getEntity();
			double x = event.getX(), y = event.getY(), z = event.getZ();
			*///?} else {
			Object entity = event.entity;
			double x = event.x, y = event.y, z = event.z;
			//?}
			if (!(entity instanceof EntityPlayer)) return;
			drawNameTag((EntityPlayer) entity, x, y, z);
		} catch (RuntimeException e) {
			if (features() != null) features().online().reportError(e);
		}
	}

	/** Würde Vanilla für diesen Spieler ein Namensschild zeichnen (canRenderName nachgebaut)? */
	private static boolean nameVisible(Minecraft mc, EntityPlayer p, RenderManager rm) {
		EntityPlayer self = Mc.player();
		if (self == null) return false;
		if (p != self) {
			Team team = p.getTeam();
			Team own = self.getTeam();
			if (team != null) {
				switch (team.getNameTagVisibility()) {
					case NEVER:
						return false;
					case HIDE_FOR_OTHER_TEAMS:
						if (own != null && !team.isSameTeam(own)) return false;
						break;
					case HIDE_FOR_OWN_TEAM:
						if (own != null && team.isSameTeam(own)) return false;
						break;
					default:
						break;
				}
			}
		}
		//? if >=1.9 {
		/*if (p == rm.renderViewEntity || p.isBeingRidden()) return false;
		*///?} else {
		if (p == rm.livingPlayer || p.riddenByEntity != null) return false;
		//?}
		return Minecraft.isGuiEnabled() && !p.isInvisibleToPlayer(self);
	}

	static void drawNameTag(EntityPlayer p, double x, double y, double z) {
		OnlineFeatures<Object> f = features();
		if (f == null) return;
		Minecraft mc = Minecraft.getMinecraft();
		RenderManager rm = mc.getRenderManager();
		String text = p.getDisplayName().getFormattedText();
		FontRenderer font = rm.getFontRenderer();
		int fanAt = offset(font, text, MARK);
		int badgeAt = offset(font, text, BADGE_MARK);
		if (fanAt < 0 && badgeAt < 0) return;
		TextureRef fanTex = fanAt < 0 ? null : f.fanSymbol(p.getUniqueID(), false);
		TextureRef badgeTex = badgeAt < 0 ? null : f.eventBadge(p.getUniqueID(), false);
		if ((fanTex == null && badgeTex == null) || !nameVisible(mc, p, rm)) return;
		//? if >=1.12 {
		/*double d0 = p.getDistanceSq(rm.renderViewEntity);
		*///?} elif >=1.9 {
		/*double d0 = p.getDistanceSqToEntity(rm.renderViewEntity);
		*///?} else
		double d0 = p.getDistanceSqToEntity(rm.livingPlayer);
		boolean sneak = p.isSneaking();
		float range = sneak ? 32f : 64f;
		if (d0 >= range * range) return;
		float left = -font.getStringWidth(text) / 2;
		Scoreboard board = p.getWorldScoreboard();
		ScoreObjective below = d0 < 100.0 && board != null ? board.getObjectiveInDisplaySlot(2) : null;
		GlStateManager.pushMatrix();
		//? if >=1.9 {
		/*float scale = 0.025F;
		double yy = y + (below != null ? font.FONT_HEIGHT * 1.15F * scale : 0) + p.height + 0.5F - (sneak ? 0.25F : 0F);
		GlStateManager.translate((float) x, (float) yy, (float) z);
		GlStateManager.rotate(-rm.playerViewY, 0.0F, 1.0F, 0.0F);
		GlStateManager.rotate((rm.options.thirdPersonView == 2 ? -1 : 1) * rm.playerViewX, 1.0F, 0.0F, 0.0F);
		GlStateManager.scale(-scale, -scale, scale);
		*///?} else {
		float scale = 0.02666667F;
		double base = y - (p.isChild() ? p.height / 2.0F : 0.0F);
		if (!sneak && below != null) base += font.FONT_HEIGHT * 1.15F * scale;
		GlStateManager.translate((float) x, (float) base + p.height + 0.5F, (float) z);
		GlStateManager.rotate(-rm.playerViewY, 0.0F, 1.0F, 0.0F);
		GlStateManager.rotate(rm.playerViewX, 1.0F, 0.0F, 0.0F);
		GlStateManager.scale(-scale, -scale, scale);
		if (sneak) GlStateManager.translate(0.0F, 9.374999F, 0.0F);
		//?}
		GlStateManager.disableLighting();
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
		GlStateManager.enableTexture2D();
		for (int k = 0; k < 2; k++) {
			TextureRef tex = k == 0 ? badgeTex : fanTex;
			if (tex == null) continue;
			float x0 = left + (k == 0 ? badgeAt : fanAt);
			int w = width(tex, k);
			mc.getTextureManager().bindTexture((ResourceLocation) tex.id);
			if (!sneak) {
				// Wie die Schrift: erst schwach durch Wände, dann normal.
				GlStateManager.depthMask(false);
				GlStateManager.disableDepth();
				quad(x0, 0, w, 0x20);
				GlStateManager.enableDepth();
				GlStateManager.depthMask(true);
				quad(x0, 0, w, 0xFF);
			} else {
				quad(x0, 0, w, 0x20);
			}
		}
		GlStateManager.enableLighting();
		GlStateManager.disableBlend();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		GlStateManager.popMatrix();
		worldDraws++;
	}

	/** {@code w}×8-Rechteck mit der gebundenen Textur bei (x, y), Deckkraft {@code alpha}. */
	private static void quad(float x, float y, int w, int alpha) {
		Tessellator tessellator = Tessellator.getInstance();
		//? if >=1.12 {
		/*BufferBuilder b = tessellator.getBuffer();
		*///?} elif >=1.9 {
		/*VertexBuffer b = tessellator.getBuffer();
		*///?} else
		WorldRenderer b = tessellator.getWorldRenderer();
		b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
		b.pos(x, y, 0).tex(0, 0).color(255, 255, 255, alpha).endVertex();
		b.pos(x, y + 8, 0).tex(0, 1).color(255, 255, 255, alpha).endVertex();
		b.pos(x + w, y + 8, 0).tex(1, 1).color(255, 255, 255, alpha).endVertex();
		b.pos(x + w, y, 0).tex(1, 0).color(255, 255, 255, alpha).endVertex();
		tessellator.draw();
	}

	// --- Tabliste ---

	@SubscribeEvent
	public void onTabList(RenderGameOverlayEvent.Post event) {
		if (Mc.overlayType(event) != RenderGameOverlayEvent.ElementType.PLAYER_LIST) return;
		try {
			drawTab(Mc.resolution(event).getScaledWidth());
		} catch (ReflectiveOperationException | RuntimeException e) {
			reflectionFailed = true;
			if (features() != null) features().online().reportError(e instanceof RuntimeException ? (RuntimeException) e
					: new IllegalStateException(e));
		}
	}

	private static Field field(Class<?> owner, Class<?> type, int index, boolean wantStatic) {
		int n = 0;
		for (Field f : owner.getDeclaredFields()) {
			if (!type.isAssignableFrom(f.getType()) || java.lang.reflect.Modifier.isStatic(f.getModifiers()) != wantStatic) continue;
			if (n++ == index) {
				f.setAccessible(true);
				return f;
			}
		}
		throw new IllegalStateException("Feld " + type.getSimpleName() + "#" + index + " fehlt in " + owner.getName());
	}

	/** Vanilla-Layout von GuiPlayerTabOverlay#renderPlayerlist nachrechnen und die Symbole an die Namen setzen. */
	@SuppressWarnings("unchecked")
	private static void drawTab(int width) throws ReflectiveOperationException {
		OnlineFeatures<Object> f = features();
		if (f == null || reflectionFailed) return;
		Minecraft mc = Minecraft.getMinecraft();
		if (Mc.connection() == null || Mc.world() == null) return;
		GuiPlayerTabOverlay overlay = mc.ingameGUI.getTabList();
		if (orderingField == null) {
			orderingField = field(GuiPlayerTabOverlay.class, com.google.common.collect.Ordering.class, 0, true);
			//? if >=1.9 {
			/*footerField = field(GuiPlayerTabOverlay.class, net.minecraft.util.text.ITextComponent.class, 0, false);
			headerField = field(GuiPlayerTabOverlay.class, net.minecraft.util.text.ITextComponent.class, 1, false);
			*///?} else {
			footerField = field(GuiPlayerTabOverlay.class, net.minecraft.util.IChatComponent.class, 0, false);
			headerField = field(GuiPlayerTabOverlay.class, net.minecraft.util.IChatComponent.class, 1, false);
			//?}
		}
		com.google.common.collect.Ordering<NetworkPlayerInfo> ordering =
				(com.google.common.collect.Ordering<NetworkPlayerInfo>) orderingField.get(null);
		List<NetworkPlayerInfo> list = ordering.sortedCopy(Mc.connection().getPlayerInfoMap());
		FontRenderer font = Mc.font();
		Scoreboard board = Mc.world().getScoreboard();
		ScoreObjective objective = board.getObjectiveInDisplaySlot(0);
		boolean hearts = objective != null && "HEARTS".equals(objective.getRenderType().name());
		int maxName = 0;
		int maxScore = 0;
		boolean any = false;
		for (NetworkPlayerInfo info : list) {
			String name = overlay.getPlayerName(info);
			maxName = Math.max(maxName, font.getStringWidth(name));
			if (name.contains(MARK)) any = true;
			if (objective != null && !hearts) {
				//? if >=1.9 {
				/*int points = board.getOrCreateScore(info.getGameProfile().getName(), objective).getScorePoints();
				*///?} else
				int points = board.getValueFromObjective(info.getGameProfile().getName(), objective).getScorePoints();
				maxScore = Math.max(maxScore, font.getStringWidth(" " + points));
			}
		}
		if (!any) return;
		list = list.subList(0, Math.min(list.size(), 80));
		int count = list.size();
		int rows = count;
		int columns = 1;
		while (rows > 20) {
			columns++;
			rows = (count + columns - 1) / columns;
		}
		//? if >=1.9 {
		/*boolean heads = mc.isIntegratedServerRunning() || Mc.connection().getNetworkManager().isEncrypted();
		*///?} else
		boolean heads = mc.isIntegratedServerRunning() || Mc.connection().getNetworkManager().getIsencrypted();
		int scoreWidth = objective == null ? 0 : hearts ? 90 : maxScore;
		int colWidth = Math.min(columns * ((heads ? 9 : 0) + maxName + scoreWidth + 13), width - 50) / columns;
		int left = width / 2 - (colWidth * columns + (columns - 1) * 5) / 2;
		int top = 10;
		Object header = headerField.get(overlay);
		if (header != null) {
			//? if >=1.9 {
			/*String headerText = ((net.minecraft.util.text.ITextComponent) header).getFormattedText();
			*///?} else
			String headerText = ((net.minecraft.util.IChatComponent) header).getFormattedText();
			top += font.listFormattedStringToWidth(headerText, width - 50).size() * font.FONT_HEIGHT + 1;
		}
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		GlStateManager.enableTexture2D();
		for (int i = 0; i < count; i++) {
			NetworkPlayerInfo info = list.get(i);
			String name = overlay.getPlayerName(info);
			UUID id = info.getGameProfile().getId();
			if (id == null) continue;
			int column = i / rows;
			int row = i % rows;
			int y = top + row * 9;
			for (int k = 0; k < 2; k++) {
				int offset = offset(font, name, k == 0 ? BADGE_MARK : MARK);
				if (offset < 0) continue;
				TextureRef tex = image(f, id, true, k);
				if (tex == null) continue;
				int x = left + column * colWidth + column * 5 + (heads ? 9 : 0) + offset;
				mc.getTextureManager().bindTexture((ResourceLocation) tex.id);
				quad(x, y, width(tex, k), 0xFF);
				tabDraws++;
			}
		}
		GlStateManager.disableBlend();
	}

	/** Teamfarben/-präfixe wie im Vanilla-Namen (für die Tabliste ohne eigenen Anzeigenamen). */
	static String teamName(NetworkPlayerInfo info) {
		return ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), info.getGameProfile().getName());
	}
}
