package dev.theredstonee.trsclient.core.wardrobe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Nativer „Datei öffnen“-Dialog – aufgerufen NUR aus einem Hintergrund-Thread (blockiert, bis der Nutzer wählt).
 *
 * <p>Reihenfolge: (1) LWJGL-TinyFD per Reflection (Minecraft 1.15.2–26.2 bringt {@code lwjgl-tinyfd} mit),
 * (2) AWT-{@code FileDialog}, wenn die JVM nicht „headless“ ist (Legacy 1.8.9–1.12.2 mit LWJGL 2), (3) der Dialog des
 * Betriebssystems über ein Hilfsprogramm (Windows: PowerShell/WinForms, Linux: zenity/kdialog, macOS: osascript) –
 * z. B. für 1.14.4 und 26.3 ohne TinyFD. Liefert den gewählten Pfad oder null (abgebrochen/nicht möglich).
 */
public final class FileChooser {
	private FileChooser() {
	}

	/** Ergebnis: Pfad oder null; {@code available} false = kein Dialog möglich. */
	public static final class Choice {
		public final String path;
		public final boolean available;

		Choice(String path, boolean available) {
			this.path = path;
			this.available = available;
		}
	}

	/** Öffnet den Dialog für PNG-Dateien (blockierend, nie im Render-Thread aufrufen). */
	public static Choice openPng(String title) {
		String t = title == null ? "PNG" : title.replace('"', '\'').replace('\n', ' ');
		try {
			Choice c = tinyFd(t);
			if (c != null) return c;
		} catch (Throwable ignored) {
			// weiter mit dem nächsten Weg
		}
		try {
			Choice c = awt(t);
			if (c != null) return c;
		} catch (Throwable ignored) {
			// weiter
		}
		try {
			Choice c = system(t);
			if (c != null) return c;
		} catch (Throwable ignored) {
			// nichts mehr übrig
		}
		return new Choice(null, false);
	}

	private static Choice tinyFd(String title) throws Exception {
		Class<?> cls;
		try {
			cls = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
		} catch (ClassNotFoundException e) {
			return null;
		}
		Class<?> pb = Class.forName("org.lwjgl.PointerBuffer");
		Method m = cls.getMethod("tinyfd_openFileDialog", CharSequence.class, CharSequence.class, pb, CharSequence.class,
				boolean.class);
		String home = System.getProperty("user.home", "");
		Object r = m.invoke(null, title, home.isEmpty() ? null : home + File.separator, null, "PNG", false);
		return new Choice(r == null ? null : r.toString(), true);
	}

	private static Choice awt(final String title) throws Exception {
		if (Boolean.parseBoolean(System.getProperty("java.awt.headless", "false"))) return null;
		if (java.awt.GraphicsEnvironment.isHeadless()) return null;
		final String[] out = new String[1];
		Runnable show = new Runnable() {
			@Override
			public void run() {
				java.awt.FileDialog d = new java.awt.FileDialog((java.awt.Frame) null, title, java.awt.FileDialog.LOAD);
				d.setFile("*.png");
				d.setFilenameFilter((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".png"));
				d.setAlwaysOnTop(true);
				d.setVisible(true);
				if (d.getFile() != null) out[0] = new File(d.getDirectory(), d.getFile()).getPath();
				d.dispose();
			}
		};
		javax.swing.SwingUtilities.invokeAndWait(show);
		return new Choice(out[0], true);
	}

	private static Choice system(String title) throws Exception {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		List<String> cmd = new ArrayList<String>();
		if (os.contains("win")) {
			String script = "Add-Type -AssemblyName System.Windows.Forms;"
					+ "$d=New-Object System.Windows.Forms.OpenFileDialog;$d.Title='" + title.replace("'", "") + "';"
					+ "$d.Filter='PNG (*.png)|*.png';"
					+ "$f=New-Object System.Windows.Forms.Form;$f.TopMost=$true;"
					+ "if($d.ShowDialog($f) -eq 'OK'){[Console]::Out.Write($d.FileName)}";
			cmd.add("powershell.exe");
			cmd.add("-NoProfile");
			cmd.add("-NonInteractive");
			cmd.add("-STA");
			cmd.add("-Command");
			cmd.add(script);
		} else if (os.contains("mac")) {
			cmd.add("osascript");
			cmd.add("-e");
			cmd.add("POSIX path of (choose file of type {\"png\"} with prompt \"" + title.replace("\"", "") + "\")");
		} else if (onPath("zenity")) {
			cmd.add("zenity");
			cmd.add("--file-selection");
			cmd.add("--title=" + title);
			cmd.add("--file-filter=PNG | *.png");
		} else if (onPath("kdialog")) {
			cmd.add("kdialog");
			cmd.add("--getopenfilename");
			cmd.add(System.getProperty("user.home", "."));
			cmd.add("*.png");
		} else {
			return null;
		}
		ProcessBuilder b = new ProcessBuilder(cmd);
		b.redirectErrorStream(false);
		Process p = b.start();
		p.getOutputStream().close();
		byte[] out = read(p.getInputStream(), 8192);
		if (!p.waitFor(10, TimeUnit.MINUTES)) {
			p.destroy();
			return new Choice(null, true);
		}
		String path = new String(out, StandardCharsets.UTF_8).trim();
		return new Choice(path.isEmpty() ? null : path, true);
	}

	private static boolean onPath(String exe) {
		String path = System.getenv("PATH");
		if (path == null) return false;
		for (String dir : path.split(File.pathSeparator)) {
			if (new File(dir, exe).canExecute()) return true;
		}
		return false;
	}

	private static byte[] read(InputStream in, int max) throws IOException {
		try (InputStream s = in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[1024];
			int n;
			while ((n = s.read(buf)) > 0) {
				if (out.size() + n > max) break;
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
	}
}
