package dev.theredstonee.trsclient.chatheads;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.chatheads.ChatHeadCache;
import dev.theredstonee.trsclient.core.chatheads.ChatHeadDetect;
import dev.theredstonee.trsclient.core.chatheads.ChatHeadLayout;
import dev.theredstonee.trsclient.core.chatheads.ChatHeadSender;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kleber zwischen dem Chat und der Erkennung in {@code core.chatheads}. Eigener TRS-Code (GPL-3.0-only):
 * welches Modul an ist, welcher Spieler welche Textur hat, und welche Zeile schon geprüft wurde.
 * Nichts davon geht ins Netz und nichts blockiert den Render-Thread.
 */
public final class ChatHeadHooks {
	/** Zeichenfläche des aktuellen Chat-Bildes, oder null. */
	private static Object frame;
	/** ChatGraphicsAccess ab 1.21.11, solange eine Zeile gezeichnet wird. */
	private static Object access;
	/** Wie weit die Zeichenfläche gerade extra nach rechts steht. */
	private static int applied;
	/** Kopfbreite der Zeile, die gerade gezeichnet wird (0 = kein Einzug). */
	private static int lineShift;
	/** UUID aus dem signierten Chat, bis die nächste Nachricht sie verbraucht. */
	private static UUID pending;
	/** Absender je Komponente (übersteht das Neu-Umbrechen). */
	private static final ChatHeadCache messages = new ChatHeadCache();
	/** Absender je gezeichneter Zeile (Identität der Zeichenfolge). */
	private static final ChatHeadCache heads = new ChatHeadCache();
	/** Dieselbe Zuordnung für Zeichenketten, die jedes Bild neu entstehen (1.14/1.15). */
	private static final Map<String, ChatHeadSender> strings = new HashMap<String, ChatHeadSender>();
	private static ChatHeadSender current = ChatHeadSender.NONE;
	private static boolean firstLine = true;

	private ChatHeadHooks() {
	}

	public static void frame(Object graphics) {
		frame = graphics;
	}

	public static Object frame() {
		return frame;
	}

	public static void access(Object graphicsAccess) {
		access = graphicsAccess;
	}

	public static void clearFrame() {
		frame = null;
		access = null;
		applied = 0;
		lineShift = 0;
	}

	/** Modul an: jede Zeile rückt um die Kopfbreite ein, auch ohne Absender. */
	public static boolean shift() {
		TrsClient client = TrsClient.get();
		return client != null && client.modules().qol.chatHeads.isEnabled();
	}

	public static boolean hat() {
		TrsClient client = TrsClient.get();
		return client != null && client.modules().qol.chatHeadsHat.get();
	}

	public static int wrap(int vanillaWidth) {
		if (!shift()) return vanillaWidth;
		return ChatHeadLayout.wrapWidth(vanillaWidth, ChatHeadLayout.WIDTH);
	}

	/** Signierter Absender, unmittelbar bevor der Chat die Nachricht übernimmt. */
	public static void arm(Object message, Object profile) {
		pending = senderUuid(message, profile);
	}

	/** Eine Nachricht wird in Zeilen zerlegt. {@code component} ist das, was im Verlauf steht. */
	public static void begin(Object component) {
		UUID uuid = pending;
		pending = null;
		if (!shift()) {
			current = ChatHeadSender.NONE;
			firstLine = true;
			return;
		}
		ChatHeadSender known = component == null ? null : messages.get(component);
		if (known != null) {
			current = known;
		} else {
			current = combine(uuid, ChatHeadDetect.find(textOf(component), names()));
			if (component != null) messages.put(component, current);
		}
		firstLine = true;
	}

	/** Eine sichtbare Zeile ist entstanden. Nur die erste trägt den Kopf, der Rest bleibt eingerückt. */
	public static void onLine(Object key) {
		if (key == null || !shift()) return;
		if (firstLine) {
			firstLine = false;
			heads.put(key, current == null ? ChatHeadSender.NONE : current);
		} else {
			heads.put(key, ChatHeadSender.NONE);
		}
	}

	public static void clear() {
		pending = null;
		current = ChatHeadSender.NONE;
		firstLine = true;
		messages.clear();
		heads.clear();
		strings.clear();
	}

	/** Text um die Kopfbreite nach rechts, und den Kopf auf die erste Zeile eines Absenders. */
	public static int offset(int x, Object key, int y, int color) {
		if (shift()) {
			drawAt(key, y, color);
			return x + ChatHeadLayout.WIDTH;
		}
		return x;
	}

	public static float offset(float x, Object key, float y, int color) {
		if (shift()) {
			drawAt(key, (int) y, color);
			return x + ChatHeadLayout.WIDTH;
		}
		return x;
	}

	/**
	 * Ab 1.21.11: der Text sitzt schon in der Chat-Transformation. Kopf an die Zeile, dann die Fläche
	 * um die Kopfbreite nach rechts – nach dem Text wieder zurück.
	 */
	public static void offsetAccess(int y, float opacity, Object key) {
		pose(0);
		lineShift = 0;
		if (!shift()) return;
		int alpha = (int) (opacity * 255f);
		if (alpha < 0) alpha = 0;
		if (alpha > 255) alpha = 255;
		drawAt(key, y, (alpha << 24) | 0xFFFFFF);
		lineShift = ChatHeadLayout.WIDTH;
		pose(lineShift);
	}

	/** Text ist gezeichnet: Fläche zurück, die Kopfbreite der Zeile bleibt gemerkt. */
	public static void undoAccess() {
		pose(0);
	}

	/** Kennzeichen-Symbol um dieselbe Breite wie der Text. */
	public static void nudgeTag() {
		pose(lineShift);
	}

	public static void unnudgeTag() {
		pose(0);
	}

	private static void drawAt(Object key, int y, int color) {
		if (key == null) return;
		ChatHeadSender sender = lookup(key);
		if (sender == null) {
			sender = combine(null, ChatHeadDetect.find(textOf(key), names()));
			remember(key, sender);
		}
		if (!sender.hasHead()) return;
		int tint = ChatHeadLayout.tint(color);
		if (tint == 0) return;
		TextureRef texture = texture(sender);
		if (texture == null) return;
		ChatHeadDraw.draw(frame, 0, y, texture, tint, hat());
	}

	private static ChatHeadSender lookup(Object key) {
		if (key instanceof String) return strings.get(key);
		return heads.get(key);
	}

	private static void remember(Object key, ChatHeadSender sender) {
		if (key instanceof String) {
			if (strings.size() > ChatHeadCache.CAP) strings.clear();
			strings.put((String) key, sender);
		} else {
			heads.put(key, sender);
		}
	}

	/** Fläche auf genau {@code target} Pixel extra nach rechts stellen. */
	private static void pose(int target) {
		final int delta = target - applied;
		if (delta == 0) return;
		//? if >=1.21.11 {
		/*if (access == null) return;
		((net.minecraft.client.gui.components.ChatComponent.ChatGraphicsAccess) access).updatePose(
				new java.util.function.Consumer<org.joml.Matrix3x2f>() {
					@Override
					public void accept(org.joml.Matrix3x2f matrix) {
						matrix.translate(delta, 0f);
					}
				});
		applied = target;
		*///?}
	}

	private static ChatHeadSender combine(UUID uuid, ChatHeadDetect.Hit hit) {
		String name = hit == null ? null : hit.name;
		if (uuid != null) return ChatHeadSender.uuid(uuid.toString(), name);
		if (hit != null && hit.self) return ChatHeadSender.self();
		if (hit != null && name != null) return ChatHeadSender.name(name);
		return ChatHeadSender.NONE;
	}

	private static UUID senderUuid(Object message, Object profile) {
		UUID id = invokeUuid(message, "sender");
		if (id == null && message != null) {
			Object link = invoke(message, "link");
			id = invokeUuid(link, "sender");
		}
		if (id == null && profile instanceof com.mojang.authlib.GameProfile) {
			com.mojang.authlib.GameProfile game = (com.mojang.authlib.GameProfile) profile;
			//? if >=1.21.9 {
			/*id = game.id();
			*///?} else
			id = game.getId();
		}
		if (id != null && id.getMostSignificantBits() == 0L && id.getLeastSignificantBits() == 0L) return null;
		return id;
	}

	private static Object invoke(Object target, String name) {
		if (target == null) return null;
		try {
			java.lang.reflect.Method method = target.getClass().getMethod(name);
			return method.invoke(target);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static UUID invokeUuid(Object target, String name) {
		Object value = invoke(target, name);
		return value instanceof UUID ? (UUID) value : null;
	}

	private static String textOf(Object key) {
		if (key == null) return "";
		if (key instanceof String) return (String) key;
		if (key instanceof Component) return ((Component) key).getString();
		//? if >=1.16 {
		if (key instanceof net.minecraft.util.FormattedCharSequence) {
			final StringBuilder sb = new StringBuilder();
			((net.minecraft.util.FormattedCharSequence) key).accept((index, style, codePoint) -> {
				sb.appendCodePoint(codePoint);
				return true;
			});
			return sb.toString();
		}
		//?}
		return "";
	}

	private static List<String> names() {
		Minecraft mc = Minecraft.getInstance();
		List<String> out = new ArrayList<String>();
		if (mc.getConnection() == null) return out;
		for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
			String name = profileName(info);
			if (name != null && !name.isEmpty()) out.add(name);
		}
		return out;
	}

	private static TextureRef texture(ChatHeadSender sender) {
		Minecraft mc = Minecraft.getInstance();
		UUID uuid = parseUuid(sender.uuid);
		if (sender.self && mc.player != null) uuid = mc.player.getUUID();
		if (mc.getConnection() != null) {
			for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
				String name = profileName(info);
				if (name != null && name.startsWith("|slot_")) continue;
				UUID id = profileId(info);
				boolean match = uuid != null && uuid.equals(id);
				if (!match && uuid == null && sender.name != null && sender.name.equals(name)) match = true;
				if (!match) continue;
				TextureRef skin = skinOf(info);
				if (skin != null) return skin;
			}
		}
		Textures.Store store = Textures.store();
		if (store == null) return null;
		UUID fallback = uuid;
		if (fallback == null && sender.name != null) {
			fallback = UUID.nameUUIDFromBytes(sender.name.getBytes(StandardCharsets.UTF_8));
		}
		if (fallback == null) fallback = new UUID(0L, 0L);
		try {
			return store.defaultSkin(fallback).texture;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static UUID parseUuid(String text) {
		if (text == null || text.isEmpty()) return null;
		try {
			return UUID.fromString(text);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String profileName(PlayerInfo info) {
		//? if >=1.21.9 {
		/*return info.getProfile().name();
		*///?} else
		return info.getProfile().getName();
	}

	private static UUID profileId(PlayerInfo info) {
		//? if >=1.21.9 {
		/*return info.getProfile().id();
		*///?} else
		return info.getProfile().getId();
	}

	private static TextureRef skinOf(PlayerInfo info) {
		try {
			Object id;
			//? if >=1.21.9 {
			/*id = info.getSkin().body().texturePath();
			*///?} elif >=1.20.2 {
			id = info.getSkin().texture();
			//?} else
			/*id = info.getSkinLocation();*/
			if (id == null) return null;
			return new TextureRef(id, 64, 64);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
