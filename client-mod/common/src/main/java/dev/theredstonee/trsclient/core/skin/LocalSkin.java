package dev.theredstonee.trsclient.core.skin;

import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.online.GameSession;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Skin (und Mojang-Umhang) des eigenen Kontos für Menüs – auch ohne Welt und ohne lebenden Spieler.
 *
 * <p>Ablauf: sofort der Standard-Skin der Version; im Hintergrund zuerst der Platten-Cache
 * ({@code config/trsclient/skins/<uuid>.png/.json}), danach einmal je Spielstart das Mojang-Profil
 * (sessionserver, nur echte Konten) mit Skin-/Umhang-Download von {@code textures.minecraft.net}.
 * Nichts davon läuft im Render-Thread; dort werden nur fertige Pixel hochgeladen.
 */
public final class LocalSkin {
	static final String SKIN_TEXTURE = "skins/local";
	static final String CAPE_TEXTURE = "skins/local_cape";
	/** Größte akzeptierte PNG (Skins sind wenige KB). */
	static final int MAX_PNG = 256 * 1024;
	/** Wie oft die Sitzung geprüft wird (Kontowechsel im Spiel). */
	static final long SESSION_CHECK_MS = 1000L;

	private final Path dir;
	private final Supplier<GameSession> session;
	private final Http http;
	private final Executor worker;
	private final Consumer<String> log;
	private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<Runnable>();
	private final PlayerLook look = new PlayerLook();

	private String sessionKey;
	private UUID uuid;
	private long nextSessionCheck;
	/** Zähler je Sitzung – Ergebnisse alter Sitzungen werden verworfen. */
	private int generation;
	private TextureRef ownSkin;
	private boolean ownSlim;
	/** Pixel des eigenen Skins (zu {@link #ownSkin}) oder null. */
	private int[] ownPixels;
	private TextureRef mojangCape;
	private boolean loading;
	/** Standard-Skin der Version für diese UUID (einmal je Sitzung bestimmt). */
	private Textures.DefaultSkin defaultSkin;
	/** Pixel des Standard-Skins (aus den Spiel-Ressourcen, im Hintergrund gelesen) oder null. */
	private int[] defaultPixels;
	private boolean defaultPixelsRequested;

	public LocalSkin(Path dir, Supplier<GameSession> session, Http http, Executor worker, Consumer<String> log) {
		this.dir = dir;
		this.session = session;
		this.http = http;
		this.worker = worker;
		this.log = log;
	}

	/**
	 * Aktuelles Aussehen (Render-Thread, je Bild). Ohne Textur-Store der Version bleibt {@link PlayerLook#skin} null.
	 */
	public PlayerLook look(long now) {
		Runnable r;
		while ((r = results.poll()) != null) r.run();
		if (now >= nextSessionCheck) {
			nextSessionCheck = now + SESSION_CHECK_MS;
			checkSession();
		}
		look.loading = loading;
		look.ownSkin = ownSkin != null;
		look.cape = mojangCape;
		look.trsCape = false;
		if (ownSkin != null) {
			look.skin = ownSkin;
			look.slim = ownSlim;
			look.pixels = ownPixels;
		} else {
			if (defaultSkin == null && uuid != null) {
				Textures.Store store = Textures.store();
				if (store != null) defaultSkin = store.defaultSkin(uuid);
			}
			look.skin = defaultSkin == null ? null : defaultSkin.texture;
			look.slim = defaultSkin != null && defaultSkin.slim;
			if (defaultSkin != null && !defaultPixelsRequested) requestDefaultPixels(defaultSkin.texture);
			look.pixels = defaultSkin == null ? null : defaultPixels;
		}
		return look;
	}

	/**
	 * Neues Aussehen sofort übernehmen (nach einem Skin-/Umhangwechsel in der Garderobe), ohne auf Mojangs Cache zu
	 * warten: schreibt den Platten-Cache und lädt die Pixel neu. {@code skinPng} null = Skin bleibt; {@code capeChanged}
	 * mit {@code capePng} null = Umhang abgelegt. Aus jedem Thread.
	 */
	public void update(final byte[] skinPng, final boolean slim, final byte[] capePng, final boolean capeChanged) {
		final int gen = generation;
		final UUID id = uuid;
		if (sessionKey == null || id == null) return;
		final String uuid32 = id.toString().replace("-", "").toLowerCase(Locale.ROOT);
		try {
			worker.execute(new Runnable() {
				@Override
				public void run() {
					Path skinFile = dir.resolve(uuid32 + ".png");
					Path metaFile = dir.resolve(uuid32 + ".json");
					Path capeFile = dir.resolve(uuid32 + "-cape.png");
					try {
						Files.createDirectories(dir);
						byte[] skin = skinPng;
						boolean isSlim = slim;
						if (skin != null) {
							Files.write(skinFile, skin);
							Files.write(metaFile, ("{\"slim\":" + slim + "}").getBytes(StandardCharsets.UTF_8));
						} else if (Files.isRegularFile(skinFile) && Files.size(skinFile) <= MAX_PNG) {
							skin = Files.readAllBytes(skinFile);
							isSlim = readSlim(metaFile);
						}
						byte[] cape = capePng;
						if (capeChanged) {
							if (capePng != null) Files.write(capeFile, capePng);
							else Files.deleteIfExists(capeFile);
						} else if (Files.isRegularFile(capeFile) && Files.size(capeFile) <= MAX_PNG) {
							cape = Files.readAllBytes(capeFile);
						}
						deliver(gen, skin, isSlim, cape, true);
					} catch (IOException | RuntimeException e) {
						log.accept("TRS Client: Skin-Cache nicht schreibbar (" + e.getMessage() + ")");
					}
				}
			});
		} catch (RuntimeException e) {
			log.accept("TRS Client: Skin-Aktualisierung abgelehnt");
		}
	}

	/** UUID der aktuellen Sitzung (Offline-UUID aus dem Namen, wenn keine da ist) oder null vor dem ersten Bild. */
	public UUID uuid() {
		return uuid;
	}

	private void checkSession() {
		GameSession s;
		try {
			s = session.get();
		} catch (RuntimeException e) {
			s = null;
		}
		String name = s == null || s.name == null ? "Player" : s.name;
		String id = s == null ? null : s.uuid;
		String key = name + "|" + id;
		if (key.equals(sessionKey)) return;
		sessionKey = key;
		look.name = name;
		uuid = id != null ? parseUuid(id) : UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
		generation++;
		defaultSkin = null;
		defaultPixels = null;
		defaultPixelsRequested = false;
		dropOwn();
		if (id == null || uuid == null) {
			loading = false;
			return;
		}
		final int gen = generation;
		final String uuid32 = id.replace("-", "").toLowerCase(Locale.ROOT);
		final boolean online = s.usable();
		loading = true;
		try {
			worker.execute(new Runnable() {
				@Override
				public void run() {
					load(gen, uuid32, online);
				}
			});
		} catch (RuntimeException e) {
			loading = false;
		}
	}

	private void dropOwn() {
		Textures.Store store = Textures.store();
		if (store != null) {
			if (ownSkin != null) store.release(ownSkin);
			if (mojangCape != null) store.release(mojangCape);
		}
		ownSkin = null;
		ownPixels = null;
		mojangCape = null;
	}

	/**
	 * Liest die Pixel des Standard-Skins (Steve, Alex …) im Hintergrund aus den Spiel-Ressourcen – für die Garderobe
	 * (Karte „Aktueller Skin“ bearbeiten/speichern). Geht es nicht (andere Ressourcen-Anordnung), bleibt es bei der
	 * Textur. Render-Thread.
	 */
	private void requestDefaultPixels(TextureRef texture) {
		defaultPixelsRequested = true;
		final String resource = resourcePath(texture.id);
		if (resource == null) return;
		final Class<?> anchor = texture.id.getClass();
		final int gen = generation;
		try {
			worker.execute(new Runnable() {
				@Override
				public void run() {
					final int[] px = resourcePixels(anchor, resource);
					if (px == null) return;
					results.add(new Runnable() {
						@Override
						public void run() {
							if (gen == generation) defaultPixels = px;
						}
					});
				}
			});
		} catch (RuntimeException e) {
			// ohne Pixel – nur die Textur
		}
	}

	/**
	 * Pfad in den Spiel-Ressourcen zu einer Textur-Kennung wie {@code minecraft:textures/entity/player/wide/steve.png}
	 * ({@code /assets/minecraft/textures/…}) oder null, wenn sie nicht danach aussieht.
	 */
	static String resourcePath(Object id) {
		if (id == null) return null;
		String s = id.toString();
		int colon = s.indexOf(':');
		String ns = colon < 0 ? "minecraft" : s.substring(0, colon);
		String path = colon < 0 ? s : s.substring(colon + 1);
		if (!ns.matches("[a-z0-9_.-]{1,64}") || !path.matches("[a-z0-9_./-]{1,200}\\.png") || path.contains("..")
				|| path.startsWith("/")) {
			return null;
		}
		return "/assets/" + ns + "/" + path;
	}

	/** Skin-Pixel aus einer Ressource (neben der Klasse {@code anchor}, sonst Kontext-Lader) oder null. */
	static int[] resourcePixels(Class<?> anchor, String resource) {
		byte[] png = null;
		java.io.InputStream in = null;
		try {
			in = anchor == null ? null : anchor.getResourceAsStream(resource);
			if (in == null) {
				ClassLoader cl = Thread.currentThread().getContextClassLoader();
				if (cl != null) in = cl.getResourceAsStream(resource.substring(1));
			}
			if (in == null) return null;
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(4096);
			byte[] buf = new byte[4096];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				if (out.size() > MAX_PNG) return null;
			}
			png = out.toByteArray();
			PngDecoder.Image img = PngDecoder.decode(png);
			return SkinImage.validSize(img.width, img.height) ? SkinImage.normalize(img.width, img.height, img.argb) : null;
		} catch (IOException | RuntimeException e) {
			return null;
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (IOException ignored) {
					// egal
				}
			}
		}
	}

	// --- Hintergrund ---

	/** Hintergrund-Thread: Cache, dann Netz. */
	void load(int gen, String uuid32, boolean online) {
		Path skinFile = dir.resolve(uuid32 + ".png");
		Path metaFile = dir.resolve(uuid32 + ".json");
		Path capeFile = dir.resolve(uuid32 + "-cape.png");
		// 1) Cache
		try {
			if (Files.isRegularFile(skinFile) && Files.size(skinFile) <= MAX_PNG) {
				boolean slim = readSlim(metaFile);
				byte[] cape = Files.isRegularFile(capeFile) && Files.size(capeFile) <= MAX_PNG ? Files.readAllBytes(capeFile) : null;
				deliver(gen, Files.readAllBytes(skinFile), slim, cape, !online);
			}
		} catch (IOException | RuntimeException e) {
			log.accept("TRS Client: Skin-Cache nicht lesbar (" + e.getMessage() + ")");
		}
		if (!online) {
			finish(gen);
			return;
		}
		// 2) Mojang-Profil
		try {
			String url = MojangProfile.profileUrl(uuid32);
			Http.Response res = http.send(new Http.Request("GET", url));
			if (res.status == 204 || res.status == 404) {
				finish(gen);
				return;
			}
			if (res.status != 200) throw new IOException("HTTP " + res.status);
			MojangProfile profile = MojangProfile.parse(res.text());
			if (profile == null) throw new IOException("unlesbares Profil");
			byte[] skin = profile.skinUrl == null ? null : download(profile.skinUrl);
			byte[] cape = profile.capeUrl == null ? null : download(profile.capeUrl);
			if (skin != null) {
				Files.createDirectories(dir);
				Files.write(skinFile, skin);
				Files.write(metaFile, ("{\"slim\":" + profile.slim + "}").getBytes(StandardCharsets.UTF_8));
				if (cape != null) Files.write(capeFile, cape);
				else Files.deleteIfExists(capeFile);
			} else {
				Files.deleteIfExists(skinFile);
				Files.deleteIfExists(capeFile);
			}
			deliver(gen, skin, profile.slim, cape, true);
		} catch (IOException | RuntimeException e) {
			log.accept("TRS Client: eigener Skin nicht ladbar (" + e.getMessage() + ")");
			finish(gen);
		}
	}

	private byte[] download(String url) throws IOException {
		Http.Request req = new Http.Request("GET", url);
		req.maxBytes = MAX_PNG;
		Http.Response res = http.send(req);
		if (res.status != 200) throw new IOException("HTTP " + res.status + " für Textur");
		return res.body;
	}

	private static boolean readSlim(Path meta) {
		try {
			if (!Files.isRegularFile(meta) || Files.size(meta) > 1024) return false;
			return new String(Files.readAllBytes(meta), StandardCharsets.UTF_8).replace(" ", "").contains("\"slim\":true");
		} catch (IOException e) {
			return false;
		}
	}

	/** Dekodiert im Hintergrund und reicht die Pixel an den Render-Thread weiter. */
	private void deliver(final int gen, byte[] skinPng, final boolean slim, byte[] capePng, final boolean last) {
		int[] skin = null;
		if (skinPng != null) {
			try {
				PngDecoder.Image img = PngDecoder.decode(skinPng);
				if (SkinImage.validSize(img.width, img.height)) skin = SkinImage.normalize(img.width, img.height, img.argb);
			} catch (IOException | RuntimeException e) {
				skin = null;
			}
		}
		int[] cape = null;
		int capeW = 0;
		int capeH = 0;
		if (capePng != null) {
			try {
				PngDecoder.Image img = PngDecoder.decode(capePng);
				int[] c = CapeImage.normalize(img.width, img.height, img.argb);
				if (c != null) {
					cape = c;
					capeW = CapeImage.width(img.width, img.height);
					capeH = capeW / 2;
				}
			} catch (IOException | RuntimeException e) {
				cape = null;
			}
		}
		final int[] skinPx = skin;
		final int[] capePx = cape;
		final int cw = capeW;
		final int ch = capeH;
		results.add(new Runnable() {
			@Override
			public void run() {
				if (gen != generation) return;
				Textures.Store store = Textures.store();
				if (store == null) return;
				if (skinPx != null) {
					ownSkin = store.upload(SKIN_TEXTURE, 64, 64, skinPx);
					ownSlim = slim;
					ownPixels = ownSkin == null ? null : skinPx;
				} else if (ownSkin != null) {
					store.release(ownSkin);
					ownSkin = null;
					ownPixels = null;
				}
				if (capePx != null) {
					mojangCape = store.upload(CAPE_TEXTURE, cw, ch, capePx);
				} else if (mojangCape != null) {
					store.release(mojangCape);
					mojangCape = null;
				}
				if (last) loading = false;
			}
		});
	}

	private void finish(final int gen) {
		results.add(new Runnable() {
			@Override
			public void run() {
				if (gen == generation) loading = false;
			}
		});
	}

	static UUID parseUuid(String s) {
		String u = s.replace("-", "");
		if (!u.matches("[0-9a-fA-F]{32}")) return null;
		try {
			return new UUID(Long.parseUnsignedLong(u.substring(0, 16), 16), Long.parseUnsignedLong(u.substring(16), 16));
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
