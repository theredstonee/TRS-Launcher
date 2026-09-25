package dev.theredstonee.trsclient.online;

import dev.theredstonee.trsclient.core.account.AccountPlatform;
import dev.theredstonee.trsclient.core.account.SessionData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Kontowechsel ohne Neustart: tauscht Minecrafts {@link User} und alles, was beim Start mit dem Token angelegt
 * wurde – je nach Version Mojang-Dienst (Blockliste, Chat-Meldungen, Einstellungen), Chat-Signatur-Schlüssel,
 * Telemetrie, Realms, das eigene Profil (Skin) und ab 26.2 die Freundesliste.
 *
 * <p>Zuweisen per Reflection über den FELDTYP (die Felder sind {@code final}, ihre Namen verschleiert); angelegt
 * wird mit übersetztem Code je Version. Mojangs authlib ist nie verschleiert – dort per Namen. Multiplayer-Anmeldung
 * ({@code joinServer}) liest den Benutzer ohnehin bei jedem Verbinden neu.
 */
public final class SessionSwap implements AccountPlatform {
	/** Profil-/Eigenschaften-Abrufe wie Minecraft im Hintergrund (eigener Thread statt Util-Pools – deren Namen wechseln). */
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "TRS-Session");
		t.setDaemon(true);
		return t;
	});

	private final Path configDir;
	private final String userAgent;
	private final Consumer<String> log;

	public SessionSwap(Path configDir, String userAgent, Consumer<String> log) {
		this.configDir = configDir;
		this.userAgent = userAgent;
		this.log = log;
	}

	@Override
	public SessionData current() {
		Minecraft mc = Minecraft.getInstance();
		User user = mc == null ? null : mc.getUser();
		if (user == null) return null;
		//? if >=1.20.2 {
		/*String uuid = user.getProfileId() == null ? null : user.getProfileId().toString();
		*///?} else {
		String uuid = user.getUuid();
		//?}
		//? if >=1.18 {
		/*String xuid = user.getXuid().orElse(null);
		*///?} else {
		String xuid = null;
		//?}
		return new SessionData(uuid, user.getName(), user.getAccessToken(), xuid);
	}

	@Override
	public boolean inWorld() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null || mc.getConnection() != null;
	}

	@Override
	public void execute(Runnable task) {
		Minecraft.getInstance().execute(task);
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

	// --- Vorbereiten (Konto-Thread, darf ins Netz) ----------------------------------------------

	/** Was im Konto-Thread schon angelegt wurde. */
	static final class Prepared {
		/** UserApiService (ab 1.18) bzw. SocialInteractionsService (1.16.4–1.17.1); null = gibt es nicht. */
		Object api;
		/** Freundesdienst (ab 26.2) oder null. */
		Object friends;
		/** Eigene Profil-Eigenschaften (Skin) bis 1.20.1 oder null. */
		com.mojang.authlib.properties.PropertyMap properties;
	}

	@Override
	public Object prepare(SessionData s) {
		Minecraft mc = Minecraft.getInstance();
		Prepared p = new Prepared();
		Object auth = authService(mc);
		if (auth != null) {
			p.api = createService(auth, s.accessToken);
			p.friends = call(auth, "createFriendsService", s.accessToken);
		}
		//? if <1.20.2 {
		try {
			com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(UUID.fromString(s.dashedUuid()), s.name);
			p.properties = mc.getMinecraftSessionService().fillProfileProperties(profile, false).getProperties();
		} catch (RuntimeException e) {
			p.properties = null;
		}
		//?}
		return p;
	}

	/** Der Authentifizierungsdienst, mit dem Minecraft gestartet ist (oder ein neuer gleicher Art). */
	static Object authService(Minecraft mc) {
		for (Field f : Minecraft.class.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers())) continue;
			String type = f.getType().getName();
			if (type.equals("com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService")
					|| type.equals("com.mojang.authlib.services.MinecraftServicesDiscoveryService")) {
				try {
					f.setAccessible(true);
					Object v = f.get(mc);
					if (v != null) return v;
				} catch (ReflectiveOperationException | RuntimeException ignored) {
					// unten neu anlegen
				}
			}
		}
		java.net.Proxy proxy = mc.getProxy();
		try {
			Class<?> discovery = Class.forName("com.mojang.authlib.services.MinecraftServicesDiscoveryService");
			return discovery.getMethod("create", java.net.Proxy.class, boolean.class).invoke(null, proxy, true);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
			// ältere authlib
		}
		try {
			Class<?> ygg = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService");
			try {
				return ygg.getConstructor(java.net.Proxy.class).newInstance(proxy);
			} catch (NoSuchMethodException e) {
				// authlib 1.x/2.x (bis 1.17): Proxy + Client-Token
				return ygg.getConstructor(java.net.Proxy.class, String.class).newInstance(proxy, UUID.randomUUID().toString());
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** Mojang-Dienst für das neue Token; bei Ablehnung der Offline-Dienst (wie Minecraft selbst). */
	static Object createService(Object auth, String token) {
		for (String name : new String[] {"createUserApiService", "createSocialInteractionsService"}) {
			Method m;
			try {
				m = auth.getClass().getMethod(name, String.class);
			} catch (NoSuchMethodException e) {
				continue;
			}
			try {
				return m.invoke(auth, token);
			} catch (ReflectiveOperationException | RuntimeException e) {
				return offlineService(name);
			}
		}
		return null;
	}

	private static Object offlineService(String factory) {
		try {
			if (factory.equals("createUserApiService")) {
				return Class.forName("com.mojang.authlib.minecraft.UserApiService").getField("OFFLINE").get(null);
			}
			return Class.forName("com.mojang.authlib.minecraft.OfflineSocialInteractions").getConstructor().newInstance();
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	private static Object call(Object target, String method, String arg) {
		try {
			return target.getClass().getMethod(method, String.class).invoke(target, arg);
		} catch (NoSuchMethodException e) {
			return null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	// --- Einsetzen (Spiel-Thread) -----------------------------------------------------------------

	@Override
	public void apply(SessionData s, Object prepared) throws Exception {
		final Minecraft mc = Minecraft.getInstance();
		Prepared p = prepared instanceof Prepared ? (Prepared) prepared : new Prepared();
		User user = newUser(s);
		set(mc, User.class, user);

		//? if <1.20.2 {
		com.mojang.authlib.properties.PropertyMap map = (com.mojang.authlib.properties.PropertyMap) get(mc, com.mojang.authlib.properties.PropertyMap.class);
		if (map != null) {
			// Leeren reicht: Minecraft füllt sie beim nächsten Zugriff neu – vorab geholt, damit der Spiel-Thread nicht wartet.
			map.clear();
			if (p.properties != null) map.putAll(p.properties);
		}
		//?}
		//? if >=1.20.2 {
		/*// Eigenes Profil (Skin, Umhang) wie Minecraft beim Start im Hintergrund holen.
		final UUID profileId = user.getProfileId();
		*///?}
		//? if >=1.21.9 {
		/*java.util.function.Supplier<Object> fetch = () -> mc.services().sessionService().fetchProfile(profileId, true);
		*///?} elif >=1.20.2 {
		/*java.util.function.Supplier<Object> fetch = () -> mc.getMinecraftSessionService().fetchProfile(profileId, true);
		*///?}
		//? if >=1.20.2 {
		/*setFuture(mc, "ProfileResult", CompletableFuture.supplyAsync(fetch, IO));
		*///?}

		Object api = p.api;
		if (api != null) {
			setByTypeName(mc, api);
			//? if >=1.20.3 {
			/*final Object service = api;
			setFuture(mc, "UserProperties", CompletableFuture.supplyAsync(() -> userProperties(service), IO));
			*///?}
			//? if >=1.16.4 {
			socialManager(mc, api, p.friends);
			//?}
			//? if >=1.19.3 {
			/*com.mojang.authlib.minecraft.UserApiService userApi = (com.mojang.authlib.minecraft.UserApiService) api;
			set(mc, net.minecraft.client.multiplayer.ProfileKeyPairManager.class,
					net.minecraft.client.multiplayer.ProfileKeyPairManager.create(userApi, user, mc.gameDirectory.toPath()));
			*///?} elif >=1.19 {
			/*com.mojang.authlib.minecraft.UserApiService userApi = (com.mojang.authlib.minecraft.UserApiService) api;
			set(mc, net.minecraft.client.multiplayer.ProfileKeyPairManager.class,
					new net.minecraft.client.multiplayer.ProfileKeyPairManager(userApi, UUID.fromString(s.dashedUuid()), mc.gameDirectory.toPath()));
			*///?}
			//? if >=1.19.1 {
			/*set(mc, net.minecraft.client.multiplayer.chat.report.ReportingContext.class,
					net.minecraft.client.multiplayer.chat.report.ReportingContext.create(
							net.minecraft.client.multiplayer.chat.report.ReportEnvironment.local(), userApi));
			*///?}
			//? if >=1.19.3 {
			/*Object oldTelemetry = get(mc, net.minecraft.client.telemetry.ClientTelemetryManager.class);
			set(mc, net.minecraft.client.telemetry.ClientTelemetryManager.class,
					new net.minecraft.client.telemetry.ClientTelemetryManager(mc, userApi, user));
			if (oldTelemetry instanceof AutoCloseable) {
				try {
					((AutoCloseable) oldTelemetry).close();
				} catch (Exception ignored) {
					// alter Sender
				}
			}
			*///?}
		}

		// Realms: Benachrichtigungen des Titelbildschirms hängen am Client des alten Kontos.
		//? if >=1.21.5 {
		/*setStatic(com.mojang.realmsclient.client.RealmsClient.class, com.mojang.realmsclient.client.RealmsClient.class, null);
		com.mojang.realmsclient.client.RealmsClient realms = com.mojang.realmsclient.client.RealmsClient.getOrCreate(mc);
		*///?} elif >=1.19.1 {
		/*com.mojang.realmsclient.client.RealmsClient realms = com.mojang.realmsclient.client.RealmsClient.create(mc);
		*///?}
		//? if >=1.19.1 {
		/*set(mc, com.mojang.realmsclient.gui.RealmsDataFetcher.class, new com.mojang.realmsclient.gui.RealmsDataFetcher(realms));
		*///?}
		//? if >=1.20.2 {
		/*setStatic(com.mojang.realmsclient.RealmsAvailability.class, CompletableFuture.class, null);
		*///?}
		log.accept("Sitzung gewechselt: " + s.name);
	}

	private static Object userProperties(Object api) {
		try {
			return api.getClass().getMethod("fetchProperties").invoke(api);
		} catch (ReflectiveOperationException | RuntimeException e) {
			try {
				return Class.forName("com.mojang.authlib.minecraft.UserApiService").getField("OFFLINE_PROPERTIES").get(null);
			} catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
				return null;
			}
		}
	}

	static User newUser(SessionData s) {
		//? if >=1.21.9 {
		/*return new User(s.name, UUID.fromString(s.dashedUuid()), s.accessToken, Optional.ofNullable(s.xuid), Optional.empty());
		*///?} elif >=1.20.2 {
		/*return new User(s.name, UUID.fromString(s.dashedUuid()), s.accessToken, Optional.ofNullable(s.xuid), Optional.empty(), User.Type.MSA);
		*///?} elif >=1.18 {
		/*return new User(s.name, s.uuid, s.accessToken, Optional.ofNullable(s.xuid), Optional.empty(), User.Type.MSA);
		*///?} else {
		return new User(s.name, s.uuid, s.accessToken, "msa");
		//?}
	}

	//? if >=1.16.4 {
	// Blockliste/Freunde: neuer PlayerSocialManager mit dem neuen Dienst (ab 26.2 auch Freundesliste).
	private static void socialManager(Minecraft mc, Object api, Object friends) throws ReflectiveOperationException {
		Class<?> psmClass = net.minecraft.client.gui.screens.social.PlayerSocialManager.class;
		Object handler = null;
		Class<?> handlerClass = null;
		if (friends != null) {
			try {
				handlerClass = Class.forName("net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler");
				for (Constructor<?> c : handlerClass.getConstructors()) {
					if (c.getParameterCount() == 2) handler = c.newInstance(friends, mc);
				}
			} catch (ClassNotFoundException e) {
				handlerClass = null;
			}
		}
		Object psm = null;
		for (Constructor<?> c : psmClass.getConstructors()) {
			Class<?>[] types = c.getParameterTypes();
			Object[] args = new Object[types.length];
			boolean ok = true;
			for (int i = 0; i < types.length && ok; i++) {
				if (types[i] == Minecraft.class) args[i] = mc;
				else if (types[i].isInstance(api)) args[i] = api;
				else if (friends != null && types[i].isInstance(friends)) args[i] = friends;
				else if (handler != null && types[i].isInstance(handler)) args[i] = handler;
				else ok = false;
			}
			if (ok) {
				psm = c.newInstance(args);
				break;
			}
		}
		if (psm == null) return;
		if (handler != null) {
			Object old = get(mc, handlerClass);
			set(mc, handlerClass, handler);
			if (old != null) {
				try {
					old.getClass().getMethod("close").invoke(old);
				} catch (ReflectiveOperationException | RuntimeException ignored) {
					// alter Abruf läuft aus
				}
			}
		}
		set(mc, psmClass, psm);
		if (handler != null) {
			try {
				Object enabled = psmClass.getMethod("isFriendListEnabled").invoke(psm);
				if (Boolean.TRUE.equals(enabled)) handler.getClass().getMethod("start").invoke(handler);
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// ohne Freundesliste
			}
		}
	}
	//?}

	// --- Reflection über Feldtypen -----------------------------------------------------------------

	private static Field field(Class<?> owner, Class<?> type, boolean statik) {
		for (Field f : owner.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers()) == statik && f.getType() == type) {
				f.setAccessible(true);
				return f;
			}
		}
		return null;
	}

	static Object get(Minecraft mc, Class<?> type) throws IllegalAccessException {
		Field f = field(Minecraft.class, type, false);
		return f == null ? null : f.get(mc);
	}

	/** Setzt das (meist finale) Feld dieses Typs in Minecraft; fehlt es in dieser Version, passiert nichts. */
	static void set(Minecraft mc, Class<?> type, Object value) throws IllegalAccessException {
		Field f = field(Minecraft.class, type, false);
		if (f != null) f.set(mc, value);
	}

	/** Mojang-Dienst: Feld, dessen Typ die Schnittstelle des Objekts ist (UserApiService/SocialInteractionsService). */
	private static void setByTypeName(Minecraft mc, Object service) throws IllegalAccessException {
		for (Field f : Minecraft.class.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers())) continue;
			String n = f.getType().getName();
			if ((n.equals("com.mojang.authlib.minecraft.UserApiService") || n.equals("com.mojang.authlib.minecraft.SocialInteractionsService"))
					&& f.getType().isInstance(service)) {
				f.setAccessible(true);
				f.set(mc, service);
			}
		}
	}

	/** {@code CompletableFuture<…X>}-Feld (X = einfacher Name des Ergebnistyps). */
	private static void setFuture(Minecraft mc, String resultSimpleName, CompletableFuture<?> value) throws IllegalAccessException {
		for (Field f : Minecraft.class.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers()) || f.getType() != CompletableFuture.class) continue;
			Type g = f.getGenericType();
			if (!(g instanceof ParameterizedType)) continue;
			Type arg = ((ParameterizedType) g).getActualTypeArguments()[0];
			if (arg instanceof Class && ((Class<?>) arg).getName().endsWith(resultSimpleName)) {
				f.setAccessible(true);
				f.set(mc, value);
				return;
			}
		}
	}

	private static void setStatic(Class<?> owner, Class<?> type, Object value) {
		try {
			Field f = field(owner, type, true);
			if (f != null && !Modifier.isFinal(f.getModifiers())) f.set(null, value);
		} catch (IllegalAccessException | RuntimeException ignored) {
			// nur ein Zwischenspeicher
		}
	}
}
