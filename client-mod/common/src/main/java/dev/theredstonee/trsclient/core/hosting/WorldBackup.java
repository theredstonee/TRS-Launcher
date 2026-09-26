package dev.theredstonee.trsclient.core.hosting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Welt-Backup vor dem Öffnen: ZIP des Weltordners in den Backup-Ordner des Spiels ({@code .minecraft/backups}), Name
 * wie beim Vanilla-Knopf „Sicherung erstellen“ ({@code JJJJ-MM-TT_HH-mm-ss_<Welt>.zip}). {@code session.lock} bleibt
 * draußen. Die Welt muss vorher gespeichert sein (macht der Aufrufer auf dem Server-Thread). Blockierend.
 */
public final class WorldBackup {
	private WorldBackup() {
	}

	public static Path zip(Path worldDir, Path backupDir, String levelId) throws IOException {
		final Path root = worldDir.toAbsolutePath().normalize();
		if (!Files.isDirectory(root)) throw new IOException("world folder missing");
		Files.createDirectories(backupDir);
		String safe = levelId == null ? "world" : levelId.replaceAll("[^A-Za-z0-9._ -]", "_");
		if (safe.isEmpty()) safe = "world";
		String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
		Path target = backupDir.resolve(stamp + "_" + safe + ".zip");
		int n = 1;
		while (Files.exists(target)) target = backupDir.resolve(stamp + "_" + safe + "_" + (n++) + ".zip");
		final String prefix = root.getFileName() == null ? safe : root.getFileName().toString();
		Path tmp = backupDir.resolve(target.getFileName() + ".part");
		try (OutputStream o = Files.newOutputStream(tmp); final ZipOutputStream zip = new ZipOutputStream(o)) {
			Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (file.getFileName() != null && file.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
					String rel = root.relativize(file).toString().replace('\\', '/');
					zip.putNextEntry(new ZipEntry(prefix + "/" + rel));
					try {
						Files.copy(file, zip);
					} catch (IOException e) {
						// Eine gerade geschriebene Datei ist gesperrt (Windows): überspringen statt abbrechen.
					}
					zip.closeEntry();
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
		Files.move(tmp, target);
		return target;
	}
}
