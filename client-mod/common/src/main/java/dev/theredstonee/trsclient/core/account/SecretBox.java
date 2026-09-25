package dev.theredstonee.trsclient.core.account;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verschlüsselt die Refresh-Tokens der im Spiel hinzugefügten Konten.
 *
 * <ul>
 * <li>Windows: DPAPI ({@code CryptProtectData}) – an das Windows-Benutzerkonto gebunden. Aufgerufen über JNA,
 * das jede Minecraft-Version mitbringt (nur per Reflection, keine Abhängigkeit beim Bauen).</li>
 * <li>Sonst (oder ohne JNA): AES-128-GCM mit einem zufälligen Schlüssel in einer Datei im Benutzerordner
 * (Rechte 0600 unter Linux/macOS) – wie der Launcher ohne Schlüsselbund. Der Schlüssel liegt NICHT in der
 * Instanz: eine kopierte oder geteilte Instanz enthält keine lesbaren Tokens.</li>
 * </ul>
 */
public abstract class SecretBox {
	/** Kennung für die Datei ({@code dpapi} / {@code aesgcm}). */
	public abstract String kind();

	public abstract byte[] seal(byte[] plain) throws GeneralSecurityException;

	/** null = nicht zu entschlüsseln (anderer Rechner/Benutzer, beschädigt). */
	public abstract byte[] open(byte[] sealed);

	/** Beste verfügbare Variante; {@code keyDir} = Ordner für die Schlüsseldatei. */
	public static SecretBox best(Path keyDir) {
		if (isWindows()) {
			SecretBox dpapi = Dpapi.create();
			if (dpapi != null) return dpapi;
		}
		return new KeyFile(keyDir.resolve("account-key.bin"));
	}

	/** Zum Lesen: die Variante, mit der die Datei geschrieben wurde (null = hier nicht verfügbar). */
	public static SecretBox forKind(String kind, Path keyDir) {
		if ("dpapi".equals(kind)) return isWindows() ? Dpapi.create() : null;
		if ("aesgcm".equals(kind)) return new KeyFile(keyDir.resolve("account-key.bin"));
		return null;
	}

	/** Standardordner für die Schlüsseldatei: Benutzerordner, nicht die Instanz. */
	public static Path defaultKeyDir() {
		String home = System.getProperty("user.home", ".");
		if (isWindows()) {
			String appData = System.getenv("APPDATA");
			if (appData != null && !appData.isEmpty()) return java.nio.file.Paths.get(appData, "trsclient");
		}
		String xdg = System.getenv("XDG_DATA_HOME");
		if (xdg != null && !xdg.isEmpty()) return java.nio.file.Paths.get(xdg, "trsclient");
		return java.nio.file.Paths.get(home, ".local", "share", "trsclient");
	}

	static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
	}

	// --- AES-GCM mit Schlüsseldatei -----------------------------------------------------------

	static final class KeyFile extends SecretBox {
		private static final SecureRandom RANDOM = new SecureRandom();
		private final Path file;

		KeyFile(Path file) {
			this.file = file;
		}

		@Override
		public String kind() {
			return "aesgcm";
		}

		private synchronized byte[] key(boolean create) throws IOException {
			if (Files.isRegularFile(file) && Files.size(file) == 16) return Files.readAllBytes(file);
			if (!create) return null;
			byte[] key = new byte[16];
			RANDOM.nextBytes(key);
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.write(tmp, key);
			restrict(tmp);
			try {
				Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return key;
		}

		private static void restrict(Path path) {
			try {
				Set<PosixFilePermission> own = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
				Files.setPosixFilePermissions(path, own);
			} catch (UnsupportedOperationException | IOException ignored) {
				// Windows: das Benutzerprofil ist ohnehin nur für den Benutzer lesbar
			}
		}

		@Override
		public byte[] seal(byte[] plain) throws GeneralSecurityException {
			try {
				byte[] key = key(true);
				byte[] iv = new byte[12];
				RANDOM.nextBytes(iv);
				Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
				c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
				c.updateAAD("trsclient-accounts".getBytes(StandardCharsets.UTF_8));
				byte[] body = c.doFinal(plain);
				byte[] out = new byte[iv.length + body.length];
				System.arraycopy(iv, 0, out, 0, iv.length);
				System.arraycopy(body, 0, out, iv.length, body.length);
				return out;
			} catch (IOException e) {
				throw new GeneralSecurityException("Schlüsseldatei", e);
			}
		}

		@Override
		public byte[] open(byte[] sealed) {
			try {
				byte[] key = key(false);
				if (key == null || sealed == null || sealed.length < 12 + 16) return null;
				Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
				c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, sealed, 0, 12));
				c.updateAAD("trsclient-accounts".getBytes(StandardCharsets.UTF_8));
				return c.doFinal(sealed, 12, sealed.length - 12);
			} catch (IOException | GeneralSecurityException | RuntimeException e) {
				return null;
			}
		}
	}

	// --- DPAPI über JNA (nur Reflection) --------------------------------------------------------

	static final class Dpapi extends SecretBox {
		private static final byte[] ENTROPY = "trsclient-accounts".getBytes(StandardCharsets.UTF_8);
		/** CRYPTPROTECT_UI_FORBIDDEN – nie einen Dialog zeigen. */
		private static final int UI_FORBIDDEN = 0x1;

		private final Method getFunction;
		private final Constructor<?> memoryCtor;
		private final Class<?> pointerClass;
		private final int pointerSize;
		private final Integer callFlags;

		private Dpapi(Method getFunction, Constructor<?> memoryCtor, Class<?> pointerClass, int pointerSize, Integer callFlags) {
			this.getFunction = getFunction;
			this.memoryCtor = memoryCtor;
			this.pointerClass = pointerClass;
			this.pointerSize = pointerSize;
			this.callFlags = callFlags;
		}

		/** null, wenn JNA fehlt oder der Probelauf scheitert. */
		static Dpapi create() {
			try {
				ClassLoader cl = SecretBox.class.getClassLoader();
				Class<?> function = Class.forName("com.sun.jna.Function", true, cl);
				Class<?> nativeClass = Class.forName("com.sun.jna.Native", true, cl);
				Class<?> memory = Class.forName("com.sun.jna.Memory", true, cl);
				Class<?> pointer = Class.forName("com.sun.jna.Pointer", true, cl);
				int pointerSize = nativeClass.getField("POINTER_SIZE").getInt(null);
				Method get;
				Integer flags = null;
				if (pointerSize == 4) {
					// 32-Bit-Windows: stdcall
					get = function.getMethod("getFunction", String.class, String.class, int.class);
					flags = function.getField("ALT_CONVENTION").getInt(null);
				} else {
					get = function.getMethod("getFunction", String.class, String.class);
				}
				Dpapi d = new Dpapi(get, memory.getConstructor(long.class), pointer, pointerSize, flags);
				byte[] probe = {1, 2, 3, 4};
				byte[] back = d.open(d.seal(probe));
				return back != null && java.util.Arrays.equals(back, probe) ? d : null;
			} catch (Throwable t) {
				return null;
			}
		}

		@Override
		public String kind() {
			return "dpapi";
		}

		private Object fn(String lib, String name) throws ReflectiveOperationException {
			return callFlags == null ? getFunction.invoke(null, lib, name) : getFunction.invoke(null, lib, name, callFlags);
		}

		private Object memory(long size) throws ReflectiveOperationException {
			return memoryCtor.newInstance(size);
		}

		/** DATA_BLOB {DWORD cbData; BYTE* pbData} – Zeiger nach Ausrichtung. */
		private Object blob(byte[] data) throws ReflectiveOperationException {
			Object buf = memory(Math.max(1, data.length));
			pointerClass.getMethod("write", long.class, byte[].class, int.class, int.class).invoke(buf, 0L, data, 0, data.length);
			Object blob = memory(pointerSize * 2L);
			pointerClass.getMethod("setInt", long.class, int.class).invoke(blob, 0L, data.length);
			pointerClass.getMethod("setPointer", long.class, pointerClass).invoke(blob, (long) pointerSize, buf);
			return blob;
		}

		private byte[] call(String name, byte[] input) throws ReflectiveOperationException {
			Object in = blob(input);
			Object entropy = blob(ENTROPY);
			Object out = memory(pointerSize * 2L);
			pointerClass.getMethod("setInt", long.class, int.class).invoke(out, 0L, 0);
			pointerClass.getMethod("setPointer", long.class, pointerClass).invoke(out, (long) pointerSize, null);
			Object f = fn("crypt32", name);
			Method invokeInt = f.getClass().getMethod("invokeInt", Object[].class);
			// Beide Funktionen: (DATA_BLOB* in, Beschreibung, DATA_BLOB* entropy, reserviert, Prompt, Flags, DATA_BLOB* out)
			Object[] args = {in, null, entropy, null, null, UI_FORBIDDEN, out};
			int ok = (Integer) invokeInt.invoke(f, new Object[] {args});
			if (ok == 0) return null;
			int len = (Integer) pointerClass.getMethod("getInt", long.class).invoke(out, 0L);
			Object data = pointerClass.getMethod("getPointer", long.class).invoke(out, (long) pointerSize);
			if (data == null || len < 0 || len > 1 << 20) return null;
			byte[] result = (byte[]) pointerClass.getMethod("getByteArray", long.class, int.class).invoke(data, 0L, len);
			Object free = fn("kernel32", "LocalFree");
			free.getClass().getMethod("invokeInt", Object[].class).invoke(free, new Object[] {new Object[] {data}});
			return result;
		}

		@Override
		public byte[] seal(byte[] plain) throws GeneralSecurityException {
			try {
				byte[] r = call("CryptProtectData", plain);
				if (r == null) throw new GeneralSecurityException("CryptProtectData fehlgeschlagen");
				return r;
			} catch (ReflectiveOperationException | RuntimeException e) {
				throw new GeneralSecurityException("DPAPI", e);
			}
		}

		@Override
		public byte[] open(byte[] sealed) {
			try {
				return sealed == null ? null : call("CryptUnprotectData", sealed);
			} catch (ReflectiveOperationException | RuntimeException e) {
				return null;
			}
		}
	}
}
