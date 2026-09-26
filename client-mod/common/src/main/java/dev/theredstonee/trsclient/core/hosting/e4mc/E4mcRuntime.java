package dev.theredstonee.trsclient.core.hosting.e4mc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Lädt die Laufzeit des öffentlichen Links bei der ersten Aktivierung: Netty 4.2 + QUIC (Apache-2.0) von Maven Central
 * (feste Versionen, SHA-256 geprüft) in {@code config/trsclient/e4mc/} und die mitgelieferte Brücke
 * ({@code e4mc-bridge.jar}, MIT – Protokoll nach e4mc). Alles läuft in einem eigenen ClassLoader: {@code io.netty.*}
 * und die Brücke kommen aus diesen Jars, alles andere (Java, TRS-Schnittstellen) vom Mod.
 *
 * <p>Blockierend (Download) – nur aus Hintergrund-Threads.
 */
public final class E4mcRuntime {
	public static final String NETTY = "4.2.16.Final";
	static final String CENTRAL = "https://repo1.maven.org/maven2/io/netty/";
	static final String BRIDGE_RESOURCE = "/assets/trsclient/e4mc/e4mc-bridge.jar";
	static final String BRIDGE_CLASS = "dev.theredstonee.trsclient.e4mcbridge.QuiclimeTunnel";
	static final int MAX_JAR = 8 * 1024 * 1024;

	/** Artefakt + SHA-256 (von Maven Central, fest). */
	static final String[][] COMMON = {
			{ "netty-common", "", "9825ee68a0dc4cd2b53e2f532502401b2211bad9b77e8b04882d9e64487283ff" },
			{ "netty-buffer", "", "cc36ae9fbd0b03fe755eb4eb4424ca53b59cfd297d7fd47d49e5b1059beded6c" },
			{ "netty-resolver", "", "c9eca6a99036485cf1d186b4a6a595b0a54c53808319ee03924270eae04c32bb" },
			{ "netty-transport", "", "cfa3f654caff906653385f4b7ddaa539d795b78f5711a622482b17d2b73484c0" },
			{ "netty-codec-base", "", "feb410225938d9970de6b624a0c031d079804fa5cc5e1ec6e9298f16db5998a7" },
			{ "netty-handler", "", "a259ca496da05ac1981f95cd856211f894a328056a6129e9cd70dbbd5df401f7" },
			{ "netty-codec-classes-quic", "", "9b2856532681b109ecc1d15ba81341665fea4ba1aab78788e3d74c1aff38ca00" } };
	static final String[][] NATIVE = {
			{ "windows-x86_64", "ebe477b5374382f0d34583907b7441dc59c52492c890f946154deff47883041d" },
			{ "linux-x86_64", "3c29587f8b2953032bd37fc20e3eda5bb7270e5e1e09b496f2c024f8ac8b5dc1" },
			{ "linux-aarch_64", "b338c76fee30c1b8b655e3e992e88dffa71591fecb35e1fed70089dc3696e0f6" },
			{ "osx-x86_64", "15cecee5ab661a650fa5c3513fd0c6d84f27d5dcc0eddcdc1b9619649b08fe12" },
			{ "osx-aarch_64", "93c4fcc7a63888cbbc621ff2774632234344518503fca967b77d6cb7f241a8ce" } };

	private static volatile ClassLoader loader;

	private E4mcRuntime() {
	}

	/** Fortschritt fürs UI (0..100). */
	public interface Progress {
		void progress(int percent);
	}

	/** Plattform-Kennung wie bei Netty ("windows-x86_64") oder null = nicht unterstützt. */
	public static String classifier() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		String a = arch.equals("amd64") || arch.equals("x86_64") ? "x86_64"
				: arch.equals("aarch64") || arch.equals("arm64") ? "aarch_64" : null;
		if (a == null) return null;
		String o = os.contains("win") ? "windows" : os.contains("mac") || os.contains("darwin") ? "osx"
				: os.contains("linux") ? "linux" : null;
		if (o == null) return null;
		String c = o + "-" + a;
		for (String[] n : NATIVE) if (n[0].equals(c)) return c;
		return null;
	}

	public static boolean supported() {
		return classifier() != null && E4mcRuntime.class.getResource(BRIDGE_RESOURCE) != null;
	}

	/** Neue Tunnel-Instanz (lädt beim ersten Mal herunter). */
	public static PublicTunnel newTunnel(Path dir, Progress progress) throws Exception {
		ClassLoader cl = loader;
		if (cl == null) {
			synchronized (E4mcRuntime.class) {
				cl = loader;
				if (cl == null) {
					cl = prepare(dir, progress);
					loader = cl;
				}
			}
		}
		Class<?> c = Class.forName(BRIDGE_CLASS, true, cl);
		return (PublicTunnel) c.getDeclaredConstructor().newInstance();
	}

	static ClassLoader prepare(Path dir, Progress progress) throws IOException {
		String cls = classifier();
		if (cls == null) throw new IOException("unsupported platform");
		Files.createDirectories(dir);
		List<URL> urls = new ArrayList<URL>();
		int total = COMMON.length + 2;
		int done = 0;
		for (String[] a : COMMON) {
			urls.add(fetch(dir, a[0], null, a[2]).toUri().toURL());
			if (progress != null) progress.progress(++done * 100 / total);
		}
		String nativeSha = null;
		for (String[] n : NATIVE) if (n[0].equals(cls)) nativeSha = n[1];
		urls.add(fetch(dir, "netty-codec-native-quic", cls, nativeSha).toUri().toURL());
		if (progress != null) progress.progress(++done * 100 / total);
		// Brücke aus dem Mod-Jar (immer frisch auspacken – sie gehört zur Mod-Version).
		Path bridge = dir.resolve("e4mc-bridge.jar");
		try (InputStream in = E4mcRuntime.class.getResourceAsStream(BRIDGE_RESOURCE)) {
			if (in == null) throw new IOException("bridge missing");
			Path tmp = dir.resolve("e4mc-bridge.jar.tmp");
			Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
			Files.move(tmp, bridge, StandardCopyOption.REPLACE_EXISTING);
		}
		urls.add(bridge.toUri().toURL());
		if (progress != null) progress.progress(100);
		return new IsolatingLoader(urls.toArray(new URL[0]), PublicTunnel.class.getClassLoader());
	}

	/** Jar aus dem Cache oder von Maven Central; SHA-256 muss stimmen. */
	static Path fetch(Path dir, String artifact, String classifier, String sha256) throws IOException {
		String file = artifact + "-" + NETTY + (classifier == null ? "" : "-" + classifier) + ".jar";
		Path target = dir.resolve(file);
		if (Files.isRegularFile(target) && sha256.equals(sha256(Files.readAllBytes(target)))) return target;
		URL url = new URL(CENTRAL + artifact + "/" + NETTY + "/" + file);
		HttpURLConnection c = (HttpURLConnection) url.openConnection();
		c.setConnectTimeout(10_000);
		c.setReadTimeout(30_000);
		c.setInstanceFollowRedirects(false);
		c.setRequestProperty("User-Agent", "TRS-Client");
		try {
			if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " for " + file);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (InputStream in = c.getInputStream()) {
				byte[] buf = new byte[16384];
				int n;
				while ((n = in.read(buf)) > 0) {
					out.write(buf, 0, n);
					if (out.size() > MAX_JAR) throw new IOException("too large: " + file);
				}
			}
			byte[] data = out.toByteArray();
			if (!sha256.equals(sha256(data))) throw new IOException("checksum mismatch: " + file);
			Path tmp = dir.resolve(file + ".tmp");
			try (OutputStream o = Files.newOutputStream(tmp)) {
				o.write(data);
			}
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			return target;
		} finally {
			c.disconnect();
		}
	}

	static String sha256(byte[] data) {
		try {
			byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
			StringBuilder sb = new StringBuilder();
			for (byte b : h) sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Kind-zuerst für Netty und die Brücke, sonst Eltern-zuerst. */
	static final class IsolatingLoader extends URLClassLoader {
		IsolatingLoader(URL[] urls, ClassLoader parent) {
			super(urls, parent);
		}

		static boolean own(String name) {
			return name.startsWith("io.netty.") || name.startsWith("dev.theredstonee.trsclient.e4mcbridge.");
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!own(name)) return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				Class<?> c = findLoadedClass(name);
				if (c == null) c = findClass(name);
				if (resolve) resolveClass(c);
				return c;
			}
		}

		@Override
		public URL getResource(String name) {
			if (name.startsWith("META-INF/native/") || name.startsWith("io/netty/") || name.startsWith("META-INF/io.netty")) {
				URL u = findResource(name);
				if (u != null) return u;
			}
			return super.getResource(name);
		}

		@Override
		public Enumeration<URL> getResources(String name) throws IOException {
			if (name.startsWith("META-INF/native/") || name.startsWith("META-INF/io.netty")) return findResources(name);
			return super.getResources(name);
		}
	}
}
