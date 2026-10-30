package dev.theredstonee.trsclient.core.ext;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.menu.ModulePanel;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds and calls the optional {@link TrsExtension}s. The service file is read by hand (not {@link java.util.ServiceLoader})
 * so it works the same on Knot, LaunchWrapper and ModLauncher. Without extensions every method is a cheap no-op.
 */
public final class TrsExtensions {
	static final String SERVICE = "META-INF/services/" + TrsExtension.class.getName();
	/** Extension id of Smart Threads. */
	public static final String SMART_THREADS = "smartThreads";
	/** Extension id of the own Vulkan renderer. */
	public static final String VULKAN = "vulkan";

	private static volatile TrsExtension[] loaded;

	private TrsExtensions() {
	}

	/** All extensions in this build (loaded once; empty in the open-source build). */
	public static TrsExtension[] all() {
		TrsExtension[] l = loaded;
		if (l == null) {
			synchronized (TrsExtensions.class) {
				l = loaded;
				if (l == null) {
					l = load();
					loaded = l;
				}
			}
		}
		return l;
	}

	/** Is the extension with this id part of this build? */
	public static boolean has(String id) {
		for (TrsExtension e : all()) {
			if (id.equals(safeId(e))) return true;
		}
		return false;
	}

	public static void preLaunch(ExtContext.Early early) {
		for (TrsExtension e : all()) {
			try {
				e.preLaunch(early);
			} catch (RuntimeException | LinkageError ex) {
				log(early.log, e, ex);
			}
		}
	}

	/**
	 * Pages of modules that belong to extensions show "not available in this build" until the extension (if any)
	 * registers its own page in {@link #clientInit}. Called by {@link TrsModules} – works in every loader tree.
	 */
	public static void placeholders(TrsModules m) {
		ModulePanel.Registry.set(m.smartThreads, new Unavailable());
		ModulePanel.Registry.set(m.vulkanRenderer, new Unavailable());
	}

	/** Lets every extension start (they replace the placeholder pages of their modules). */
	public static void clientInit(ExtContext.Client client) {
		for (TrsExtension e : all()) {
			try {
				e.clientInit(client);
			} catch (RuntimeException | LinkageError ex) {
				log(client.log, e, ex);
			}
		}
	}

	public static void clientTick(Object minecraft) {
		TrsExtension[] l = all();
		for (int i = 0; i < l.length; i++) {
			try {
				l[i].clientTick(minecraft);
			} catch (RuntimeException | LinkageError ex) {
				// never disturb the game
			}
		}
	}

	public static void frameStart() {
		TrsExtension[] l = all();
		for (int i = 0; i < l.length; i++) {
			try {
				l[i].frameStart();
			} catch (RuntimeException | LinkageError ex) {
				// never disturb the game
			}
		}
	}

	public static boolean hideBlockEntity(int x, int y, int z, double cameraX, double cameraY, double cameraZ) {
		TrsExtension[] l = all();
		for (int i = 0; i < l.length; i++) {
			try {
				if (l[i].hideBlockEntity(x, y, z, cameraX, cameraY, cameraZ)) return true;
			} catch (RuntimeException | LinkageError ex) {
				// keep drawing it
			}
		}
		return false;
	}

	public static boolean autotest(String name) {
		if (name == null) return false;
		for (TrsExtension e : all()) {
			try {
				if (e.autotest(name)) return true;
			} catch (RuntimeException | LinkageError ex) {
				// not handled
			}
		}
		return false;
	}

	// --- loading ---

	private static TrsExtension[] load() {
		List<TrsExtension> out = new ArrayList<TrsExtension>();
		ClassLoader loader = TrsExtensions.class.getClassLoader();
		for (String name : names(loader)) {
			try {
				Class<?> c = Class.forName(name, true, loader);
				if (TrsExtension.class.isAssignableFrom(c)) out.add((TrsExtension) c.getDeclaredConstructor().newInstance());
			} catch (Exception | LinkageError e) {
				// incomplete build or wrong Minecraft version – run without this extension
			}
		}
		return out.toArray(new TrsExtension[0]);
	}

	static Set<String> names(ClassLoader loader) {
		Set<String> names = new LinkedHashSet<String>();
		try {
			Enumeration<URL> urls = loader != null ? loader.getResources(SERVICE) : ClassLoader.getSystemResources(SERVICE);
			for (URL url : Collections.list(urls)) {
				InputStream in = url.openStream();
				try {
					names.addAll(parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))));
				} finally {
					in.close();
				}
			}
		} catch (Exception e) {
			// no service file
		}
		return names;
	}

	static List<String> parse(BufferedReader reader) throws java.io.IOException {
		List<String> names = new ArrayList<String>();
		String line;
		while ((line = reader.readLine()) != null) {
			int hash = line.indexOf('#');
			if (hash >= 0) line = line.substring(0, hash);
			line = line.trim();
			if (!line.isEmpty()) names.add(line);
		}
		return names;
	}

	private static String safeId(TrsExtension e) {
		try {
			return e.id();
		} catch (RuntimeException | LinkageError ex) {
			return "";
		}
	}

	private static void log(ExtContext.Log log, TrsExtension e, Throwable ex) {
		if (log != null) log.info("Extension " + safeId(e) + " failed: " + ex);
	}

	/** Page content of a module whose extension is not part of this build. */
	static final class Unavailable implements ModulePanel {
		@Override
		public int draw(Canvas c, Hits hits, int x, int y, int w, int mouseX, int mouseY, Runnable click) {
			Theme t = Theme.get();
			return Paint.paragraph(c, I18n.tr("ext.unavailable"), x, y, Math.min(w, 420), 10, t.textDim) + 6;
		}
	}

	/** For tests: forget what was loaded. */
	static void reset(TrsExtension... extensions) {
		loaded = extensions == null ? null : extensions.clone();
	}
}
