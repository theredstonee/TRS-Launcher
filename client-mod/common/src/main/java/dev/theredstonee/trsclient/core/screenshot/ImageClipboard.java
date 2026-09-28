package dev.theredstonee.trsclient.core.screenshot;

import dev.theredstonee.trsclient.core.cape.PngDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Bild (PNG-Datei) in die Zwischenablage des Systems – blockierend, NUR aus einem Hintergrund-Thread aufrufen.
 *
 * <p>Minecraft läuft oft „headless“ (AWT ohne Zwischenablage, v. a. macOS/LWJGL 3), deshalb der Reihe nach:
 * <ol>
 *   <li>Windows: Win32-Zwischenablage direkt über JNA (Minecraft bringt JNA mit; nur Reflection) – {@code CF_DIB}
 *   plus das Format „PNG“ (Discord, Browser, Bildprogramme).</li>
 *   <li>AWT-Zwischenablage, wenn die JVM nicht headless ist (z. B. Legacy 1.8.9–1.12.2 mit LWJGL 2).</li>
 *   <li>Hilfsprogramm des Systems: Windows PowerShell/WinForms, macOS {@code osascript}, Linux {@code wl-copy} bzw.
 *   {@code xclip}. Der Pfad geht nie in eine Befehlszeile ein, die ausgewertet wird (Umgebungsvariable bzw. argv).</li>
 * </ol>
 */
public final class ImageClipboard {
	/** Ergebnis für die Meldung an den Spieler. */
	public enum Result {
		OK,
		/** Kein Weg auf diesem System (z. B. Linux ohne wl-copy/xclip). */
		UNSUPPORTED,
		/** Datei unlesbar oder alle Wege fehlgeschlagen. */
		FAILED
	}

	static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
	/** Größte Bildgröße, die dekodiert wird (8K = 33 MP). */
	public static final long MAX_PIXELS = 40_000_000L;
	/** Größte Kantenlänge für CF_DIB (darüber nur die PNG-Daten / Hilfsprogramme). */
	static final int MAX_DIB_EDGE = 8192;

	private ImageClipboard() {
	}

	private static String os() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
	}

	/** PNG-Datei in die Zwischenablage. */
	public static Result copy(Path png) {
		if (png == null) return Result.FAILED;
		byte[] bytes;
		try {
			if (!Files.isRegularFile(png) || Files.size(png) > MAX_FILE_BYTES) return Result.FAILED;
			bytes = Files.readAllBytes(png);
		} catch (IOException | RuntimeException e) {
			return Result.FAILED;
		}
		String os = os();
		boolean windows = os.contains("win");
		PngDecoder.Image img = null;
		if (windows || !headless()) {
			try {
				img = PngDecoder.decode(bytes, MAX_PIXELS);
			} catch (Exception | OutOfMemoryError e) {
				img = null;
			}
		}
		if (windows && img != null) {
			try {
				if (Win32.copy(img, bytes)) return Result.OK;
			} catch (Throwable ignored) {
				// weiter mit dem nächsten Weg
			}
		}
		if (img != null && !headless()) {
			try {
				if (awt(img)) return Result.OK;
			} catch (Throwable ignored) {
				// weiter
			}
		}
		try {
			Boolean r = external(png, os);
			if (r == null) return Result.UNSUPPORTED;
			return r ? Result.OK : Result.FAILED;
		} catch (Throwable t) {
			return Result.FAILED;
		}
	}

	static boolean headless() {
		if (Boolean.parseBoolean(System.getProperty("java.awt.headless", "false"))) return true;
		try {
			return java.awt.GraphicsEnvironment.isHeadless();
		} catch (Throwable t) {
			return true;
		}
	}

	// --- AWT -------------------------------------------------------------------------------------

	private static boolean awt(PngDecoder.Image img) {
		final java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(img.width, img.height,
				java.awt.image.BufferedImage.TYPE_INT_RGB);
		bi.setRGB(0, 0, img.width, img.height, img.argb, 0, img.width);
		java.awt.datatransfer.Transferable t = new java.awt.datatransfer.Transferable() {
			@Override
			public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
				return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.imageFlavor};
			}

			@Override
			public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
				return java.awt.datatransfer.DataFlavor.imageFlavor.equals(flavor);
			}

			@Override
			public Object getTransferData(java.awt.datatransfer.DataFlavor flavor)
					throws java.awt.datatransfer.UnsupportedFlavorException {
				if (!isDataFlavorSupported(flavor)) throw new java.awt.datatransfer.UnsupportedFlavorException(flavor);
				return bi;
			}
		};
		java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(t, null);
		return true;
	}

	// --- Hilfsprogramme ------------------------------------------------------------------------------

	/** true = kopiert, false = fehlgeschlagen, null = kein Hilfsprogramm vorhanden. */
	private static Boolean external(Path png, String os) throws IOException, InterruptedException {
		String abs = png.toAbsolutePath().toString();
		List<String> cmd = new ArrayList<>();
		boolean stdin = false;
		ProcessBuilder b;
		if (os.contains("win")) {
			// Pfad über die Umgebung – nie im Skripttext.
			String script = "Add-Type -AssemblyName System.Windows.Forms,System.Drawing;"
					+ "$i=[System.Drawing.Image]::FromFile($env:TRS_CLIPBOARD_IMAGE);"
					+ "[System.Windows.Forms.Clipboard]::SetImage($i);$i.Dispose()";
			cmd.add("powershell.exe");
			cmd.add("-NoProfile");
			cmd.add("-NonInteractive");
			cmd.add("-STA");
			cmd.add("-Command");
			cmd.add(script);
			b = new ProcessBuilder(cmd);
			b.environment().put("TRS_CLIPBOARD_IMAGE", abs);
		} else if (os.contains("mac")) {
			cmd.add("osascript");
			cmd.add("-e");
			cmd.add("on run argv");
			cmd.add("-e");
			cmd.add("set the clipboard to (read (POSIX file (item 1 of argv)) as «class PNGf»)");
			cmd.add("-e");
			cmd.add("end run");
			cmd.add(abs);
			b = new ProcessBuilder(cmd);
		} else {
			String wayland = System.getenv("WAYLAND_DISPLAY");
			if (wayland != null && !wayland.isEmpty() && onPath("wl-copy")) {
				cmd.add("wl-copy");
				cmd.add("--type");
				cmd.add("image/png");
			} else if (onPath("xclip")) {
				cmd.add("xclip");
				cmd.add("-selection");
				cmd.add("clipboard");
				cmd.add("-t");
				cmd.add("image/png");
				cmd.add("-i");
			} else {
				return null;
			}
			stdin = true;
			b = new ProcessBuilder(cmd);
		}
		b.redirectErrorStream(true);
		if (stdin) b.redirectInput(png.toFile());
		Process p = b.start();
		if (!stdin) p.getOutputStream().close();
		drain(p.getInputStream());
		if (!p.waitFor(20, TimeUnit.SECONDS)) {
			// wl-copy/xclip bleiben als Besitzer der Zwischenablage im Hintergrund – das ist gewollt.
			return stdin;
		}
		return p.exitValue() == 0;
	}

	private static void drain(final InputStream in) {
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				byte[] buf = new byte[4096];
				try {
					while (in.read(buf) >= 0) {
						// verwerfen
					}
				} catch (IOException ignored) {
					// Ende
				}
			}
		}, "TRS-Clipboard-Out");
		t.setDaemon(true);
		t.start();
	}

	private static boolean onPath(String exe) {
		String path = System.getenv("PATH");
		if (path == null) return false;
		for (String dir : path.split(java.io.File.pathSeparator)) {
			if (!dir.isEmpty() && Files.isExecutable(java.nio.file.Paths.get(dir, exe))) return true;
		}
		return false;
	}

	// --- Windows über JNA (nur Reflection) --------------------------------------------------------

	/** BITMAPINFOHEADER + 32-Bit-BGRA-Zeilen von unten nach oben ({@code CF_DIB}). */
	static byte[] dib(int w, int h, int[] argb) {
		int header = 40;
		long size = (long) header + (long) w * h * 4;
		if (w <= 0 || h <= 0 || w > MAX_DIB_EDGE || h > MAX_DIB_EDGE || size > Integer.MAX_VALUE) return null;
		byte[] out = new byte[(int) size];
		le32(out, 0, header);
		le32(out, 4, w);
		le32(out, 8, h);
		out[12] = 1; // Ebenen
		out[14] = 32; // Bit je Pixel
		le32(out, 16, 0); // BI_RGB
		le32(out, 20, w * h * 4);
		int p = header;
		for (int y = h - 1; y >= 0; y--) {
			int row = y * w;
			for (int x = 0; x < w; x++) {
				int c = argb[row + x];
				out[p++] = (byte) c;
				out[p++] = (byte) (c >> 8);
				out[p++] = (byte) (c >> 16);
				out[p++] = (byte) 0xFF;
			}
		}
		return out;
	}

	private static void le32(byte[] b, int i, int v) {
		b[i] = (byte) v;
		b[i + 1] = (byte) (v >> 8);
		b[i + 2] = (byte) (v >> 16);
		b[i + 3] = (byte) (v >> 24);
	}

	static final class Win32 {
		private static final int CF_DIB = 8;
		private static final int GMEM_MOVEABLE = 0x0002;

		private Win32() {
		}

		static boolean copy(PngDecoder.Image img, byte[] png) throws Exception {
			byte[] dib = dib(img.width, img.height, img.argb);
			if (dib == null) return false;
			ClassLoader cl = ImageClipboard.class.getClassLoader();
			Class<?> function = Class.forName("com.sun.jna.Function", true, cl);
			Class<?> nativeClass = Class.forName("com.sun.jna.Native", true, cl);
			Class<?> pointer = Class.forName("com.sun.jna.Pointer", true, cl);
			Class<?> wstring = Class.forName("com.sun.jna.WString", true, cl);
			int pointerSize = nativeClass.getField("POINTER_SIZE").getInt(null);
			Method get;
			Object flags = null;
			if (pointerSize == 4) {
				get = function.getMethod("getFunction", String.class, String.class, int.class);
				flags = function.getField("ALT_CONVENTION").getInt(null);
			} else {
				get = function.getMethod("getFunction", String.class, String.class);
			}
			Fn fn = new Fn(get, flags, pointer, pointerSize);
			Object hwnd = fn.ptr("user32", "GetForegroundWindow");
			boolean open = false;
			for (int i = 0; i < 10 && !open; i++) {
				open = fn.i("user32", "OpenClipboard", hwnd) != 0;
				if (!open) Thread.sleep(30);
			}
			if (!open) return false;
			try {
				if (fn.i("user32", "EmptyClipboard") == 0) return false;
				boolean ok = put(fn, CF_DIB, dib);
				try {
					int pngFormat = fn.i("user32", "RegisterClipboardFormatW", wstring.getConstructor(String.class).newInstance("PNG"));
					if (pngFormat != 0) put(fn, pngFormat, png);
				} catch (Exception ignored) {
					// PNG ist nur ein Zusatz
				}
				return ok;
			} finally {
				fn.i("user32", "CloseClipboard");
			}
		}

		private static boolean put(Fn fn, int format, byte[] data) throws Exception {
			Object size = fn.pointerSize == 8 ? (Object) (long) data.length : (Object) data.length;
			Object mem = fn.ptr("kernel32", "GlobalAlloc", GMEM_MOVEABLE, size);
			if (mem == null) return false;
			Object locked = fn.ptr("kernel32", "GlobalLock", mem);
			if (locked == null) {
				fn.ptr("kernel32", "GlobalFree", mem);
				return false;
			}
			fn.pointer.getMethod("write", long.class, byte[].class, int.class, int.class).invoke(locked, 0L, data, 0, data.length);
			fn.i("kernel32", "GlobalUnlock", mem);
			Object set = fn.ptr("user32", "SetClipboardData", format, mem);
			if (set == null) {
				// Gehört uns noch – freigeben.
				fn.ptr("kernel32", "GlobalFree", mem);
				return false;
			}
			return true;
		}

		/** Aufrufe über {@code com.sun.jna.Function}. */
		private static final class Fn {
			final Method get;
			final Object flags;
			final Class<?> pointer;
			final int pointerSize;

			Fn(Method get, Object flags, Class<?> pointer, int pointerSize) {
				this.get = get;
				this.flags = flags;
				this.pointer = pointer;
				this.pointerSize = pointerSize;
			}

			private Object function(String lib, String name) throws Exception {
				return flags == null ? get.invoke(null, lib, name) : get.invoke(null, lib, name, flags);
			}

			int i(String lib, String name, Object... args) throws Exception {
				Object f = function(lib, name);
				return (Integer) f.getClass().getMethod("invokeInt", Object[].class).invoke(f, new Object[]{args});
			}

			Object ptr(String lib, String name, Object... args) throws Exception {
				Object f = function(lib, name);
				return f.getClass().getMethod("invokePointer", Object[].class).invoke(f, new Object[]{args});
			}
		}
	}
}
