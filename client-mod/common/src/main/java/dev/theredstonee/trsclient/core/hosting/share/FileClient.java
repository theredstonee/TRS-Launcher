package dev.theredstonee.trsclient.core.hosting.share;

import dev.theredstonee.trsclient.core.hosting.net.PeerStream;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * Gast-Seite des Datei-Kanals ({@link FileChannel}): eine Datei per SHA-256 anfordern, mit Größen- und Hash-Prüfung.
 * Der erwartete Hash kommt aus der API (vom Host angekündigt), nicht aus dem Kanal. Blockierend.
 */
public final class FileClient implements AutoCloseable {
	/** Fehler vom Host oder bei der Prüfung ({@link #code}: Code aus {@link FileChannel#safeCode} bzw. size/hash/closed). */
	public static final class FileException extends IOException {
		private static final long serialVersionUID = 1L;
		public final String code;

		public FileException(String code) {
			super("files: " + code);
			this.code = code;
		}
	}

	/** Fortschritt (gelesene Bytes). */
	public interface Progress {
		void bytes(long done, long total);
	}

	/** Ergebnis: geprüfte Größe + Hashes. */
	public static final class Result {
		public final long size;
		public final String sha256;
		public final String sha1;

		Result(long size, String sha256, String sha1) {
			this.size = size;
			this.sha256 = sha256;
			this.sha1 = sha1;
		}
	}

	private final PeerStream stream;
	private final FileChannel.Input in = new FileChannel.Input();

	/** Kanal auf einem frischen Strom öffnen (schickt die Magie). */
	public FileClient(PeerStream stream) throws IOException {
		this.stream = stream;
		in.timeout(45_000L);
		stream.start(in);
		if (!stream.write(FileChannel.MAGIC, 0, FileChannel.MAGIC.length)) throw new FileException("closed");
	}

	/**
	 * Datei holen und nach {@code out} schreiben. {@code expectedSize}: angekündigte Größe (größer → Abbruch),
	 * {@code expectedSha256}: angekündigter Hash (anders → {@code hash}), {@code sha1}: optional zusätzlich prüfen.
	 */
	public Result fetch(int kind, String expectedSha256, long expectedSize, long maxSize, String expectedSha1, OutputStream out,
			Progress progress) throws IOException {
		byte[] want = FileChannel.unhex(expectedSha256);
		if (want == null || want.length != 32) throw new FileException("bad_request");
		byte[] req = FileChannel.get(kind, want);
		if (!stream.write(req, 0, req.length)) throw new FileException("closed");
		FileChannel.Frame f = FileChannel.read(in, 1024);
		if (f.type == FileChannel.ERROR) throw new FileException(FileChannel.safeCode(f.text()));
		if (f.type != FileChannel.FILE || f.payload.length != 8) throw new FileException("bad_request");
		long size = FileChannel.u64(f.payload, 0);
		if (size < 0 || size > maxSize || size != expectedSize) throw new FileException("size");
		MessageDigest s256 = ModScan.digest("SHA-256");
		MessageDigest s1 = ModScan.digest("SHA-1");
		long got = 0;
		while (true) {
			FileChannel.Frame d = FileChannel.read(in, FileChannel.CHUNK);
			if (d.type == FileChannel.DATA) {
				got += d.payload.length;
				if (got > size) throw new FileException("size");
				s256.update(d.payload);
				s1.update(d.payload);
				out.write(d.payload);
				if (progress != null) progress.bytes(got, size);
			} else if (d.type == FileChannel.END) {
				break;
			} else if (d.type == FileChannel.ERROR) {
				throw new FileException(FileChannel.safeCode(d.text()));
			} else {
				throw new FileException("bad_request");
			}
		}
		String sha256 = ModScan.hex(s256.digest());
		String sha1 = ModScan.hex(s1.digest());
		if (got != size) throw new FileException("size");
		if (!sha256.equals(expectedSha256)) throw new FileException("hash");
		if (expectedSha1 != null && !sha1.equals(expectedSha1)) throw new FileException("hash");
		return new Result(size, sha256, sha1);
	}

	@Override
	public void close() {
		byte[] bye = FileChannel.frame(FileChannel.BYE, new byte[0]);
		stream.write(bye, 0, bye.length);
		stream.close("files done");
	}
}
