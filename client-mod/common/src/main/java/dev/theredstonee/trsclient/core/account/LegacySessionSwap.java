package dev.theredstonee.trsclient.core.account;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Kontowechsel für Forge 1.7.10–1.13.2 (MCP-Namen, zur Laufzeit SRG): Dort hängt am Konto nur die
 * {@code Session} (Name, UUID, Token) und die Profil-Eigenschaften (eigener Skin) – keine Mojang-Dienste, keine
 * Chat-Signaturen. Beides wird per Reflection über den FELDTYP getauscht, damit derselbe Code in allen drei
 * Legacy-Bäumen läuft; authlib ist nie verschleiert (dort per Namen).
 *
 * <p>Die Bäume liefern nur: das Minecraft-Objekt, die aktuelle Sitzung, „in einer Welt?“ und rufen im Client-Tick
 * {@link #drain()} (Spiel-Thread).
 */
public final class LegacySessionSwap implements AccountPlatform {
	private final Supplier<Object> minecraft;
	/** {@code net.minecraft.util.Session} – beim Bauen übersetzt, daher auch zur Laufzeit (SRG) richtig. */
	private final Class<?> sessionClass;
	private final Supplier<SessionData> current;
	private final BooleanSupplier inWorld;
	private final Path configDir;
	private final String userAgent;
	private final Consumer<String> log;
	private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<Runnable>();

	public LegacySessionSwap(Supplier<Object> minecraft, Class<?> sessionClass, Supplier<SessionData> current, BooleanSupplier inWorld, Path configDir,
			String userAgent, Consumer<String> log) {
		this.minecraft = minecraft;
		this.sessionClass = sessionClass;
		this.current = current;
		this.inWorld = inWorld;
		this.configDir = configDir;
		this.userAgent = userAgent;
		this.log = log;
	}

	/** Aus dem Client-Tick (Spiel-Thread): wartende Wechsel ausführen. */
	public void drain() {
		Runnable r;
		while ((r = queue.poll()) != null) {
			try {
				r.run();
			} catch (RuntimeException e) {
				log.accept("Kontowechsel: " + e.getClass().getSimpleName());
			}
		}
	}

	@Override
	public SessionData current() {
		try {
			return current.get();
		} catch (RuntimeException e) {
			return null;
		}
	}

	@Override
	public boolean inWorld() {
		return inWorld.getAsBoolean();
	}

	@Override
	public void execute(Runnable task) {
		queue.add(task);
	}

	@Override
	public Path configDir() {
		return configDir;
	}

	@Override
	public String userAgent() {
		return userAgent;
	}

	@Override
	public void log(String message) {
		log.accept(message);
	}

	/** Profil-Eigenschaften (Skin) vorab im Konto-Thread holen – Minecraft täte es sonst blockierend im Spiel-Thread. */
	@Override
	public Object prepare(SessionData s) {
		Object mc = minecraft.get();
		try {
			Object sessions = fieldValue(mc, "com.mojang.authlib.minecraft.MinecraftSessionService");
			if (sessions == null) return null;
			Class<?> gp = Class.forName("com.mojang.authlib.GameProfile");
			Object profile = gp.getConstructor(UUID.class, String.class).newInstance(UUID.fromString(s.dashedUuid()), s.name);
			Method fill = sessions.getClass().getMethod("fillProfileProperties", gp, boolean.class);
			Object filled = fill.invoke(sessions, profile, false);
			return gp.getMethod("getProperties").invoke(filled);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	@Override
	public void apply(SessionData s, Object prepared) throws Exception {
		Object mc = minecraft.get();
		Field sessionField = sessionField(mc.getClass(), sessionClass);
		if (sessionField == null) throw new IllegalStateException("Session-Feld nicht gefunden");
		Constructor<?> ctor = sessionField.getType().getConstructor(String.class, String.class, String.class, String.class);
		sessionField.set(mc, ctor.newInstance(s.name, s.uuid, s.accessToken, "msa"));
		// Eigene Profil-Eigenschaften (Skin) leeren und ggf. mit den vorab geholten füllen.
		for (Field f : allFields(mc.getClass())) {
			if (Modifier.isStatic(f.getModifiers()) || !f.getType().getName().equals("com.mojang.authlib.properties.PropertyMap")) continue;
			f.setAccessible(true);
			Object map = f.get(mc);
			if (map == null) continue;
			map.getClass().getMethod("clear").invoke(map);
			if (prepared != null && f.getType().isInstance(prepared)) {
				Method putAll = findPutAll(map.getClass());
				if (putAll != null) putAll.invoke(map, prepared);
			}
		}
		log.accept("Sitzung gewechselt: " + s.name);
	}

	private static Method findPutAll(Class<?> c) {
		for (Method m : c.getMethods()) {
			if (m.getName().equals("putAll") && m.getParameterCount() == 1
					&& !Map.class.isAssignableFrom(m.getParameterTypes()[0])) {
				return m;
			}
		}
		return null;
	}

	/** Feld der Session (über den Typ – der Name ist zur Laufzeit verschleiert). */
	static Field sessionField(Class<?> mcClass, Class<?> sessionClass) {
		for (Field f : allFields(mcClass)) {
			if (!Modifier.isStatic(f.getModifiers()) && f.getType() == sessionClass) {
				f.setAccessible(true);
				return f;
			}
		}
		return null;
	}

	private static java.util.List<Field> allFields(Class<?> c) {
		java.util.List<Field> out = new java.util.ArrayList<Field>();
		for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
			for (Field f : k.getDeclaredFields()) out.add(f);
		}
		return out;
	}

	private static Object fieldValue(Object target, String typeName) throws IllegalAccessException {
		for (Field f : allFields(target.getClass())) {
			if (!Modifier.isStatic(f.getModifiers()) && f.getType().getName().equals(typeName)) {
				f.setAccessible(true);
				return f.get(target);
			}
		}
		return null;
	}
}
