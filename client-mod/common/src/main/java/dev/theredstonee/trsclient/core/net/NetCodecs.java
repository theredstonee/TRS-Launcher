package dev.theredstonee.trsclient.core.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Kompression der Minecraft-Pakete ohne unnötige Kopien und Allokationen – dieselben Bytes, dieselben Prüfungen wie
 * Vanilla, nur schlanker. Die Unterklassen der Vanilla-Handler (je Minecraft-Version im Loader, damit Vanillas
 * {@code instanceof}-Abfragen weiter greifen) rufen nur diese Methoden auf.
 *
 * <p>Was Vanilla je Paket macht (1.8.9 … 1.20.4): ein {@code new byte[]} für die komprimierten Bytes, ein weiteres für das
 * Ergebnis und ein neuer Puffer drumherum; beim Senden ein {@code new byte[]} je Paket. Hier: Eingabe direkt aus dem
 * Netty-Puffer (bzw. einmal in ein wiederverwendetes Feld), Ausgabe in einen Puffer aus Nettys Pool. Deflate/Inflate
 * selbst bleibt die zlib des JDK – schneller geht es ohne native Bibliotheken nicht.
 */
public final class NetCodecs {
	/** Größte Eingabe, die ein Arbeitsfeld dauerhaft behält (größere Pakete bekommen ein einmaliges Feld). */
	static final int KEEP_SCRATCH = 1 << 21;
	/** Bis zu dieser Größe kommt das Ergebnis nicht aus dem Pool. */
	static final int SMALL = 2048;

	/** {@code Inflater.setInput(ByteBuffer)} ab Java 11 (null auf Java 8). */
	private static final MethodHandle INFLATER_BB = find(Inflater.class, "setInput", ByteBuffer.class);
	/** {@code Deflater.setInput(ByteBuffer)} ab Java 11. */
	private static final MethodHandle DEFLATER_BB = find(Deflater.class, "setInput", ByteBuffer.class);

	private NetCodecs() {
	}

	private static MethodHandle find(Class<?> owner, String name, Class<?> arg) {
		try {
			return MethodHandles.publicLookup().findVirtual(owner, name, MethodType.methodType(void.class, arg));
		} catch (NoSuchMethodException | IllegalAccessException | RuntimeException e) {
			return null;
		}
	}

	/** Gibt es die ByteBuffer-Variante (Java 11+)? Nur für Tests/Anzeige. */
	public static boolean byteBufferApi() {
		return INFLATER_BB != null;
	}

	// --- VarInt (wie Vanilla: höchstens 5 Bytes) ---

	public static int readVarInt(ByteBuf buf) {
		int value = 0;
		for (int i = 0; i < 5; i++) {
			byte b = buf.readByte();
			value |= (b & 0x7F) << (7 * i);
			if ((b & 0x80) == 0) return value;
		}
		throw new DecoderException("VarInt too big");
	}

	public static void writeVarInt(ByteBuf buf, int value) {
		while ((value & ~0x7F) != 0) {
			buf.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		buf.writeByte(value);
	}

	/** Entpacken mit wiederverwendetem Inflater und Arbeitsfeld (ein Objekt je Verbindung, nur Netty-Thread). */
	public static final class Inflate {
		private final Inflater inflater = new Inflater();
		private byte[] scratch = new byte[8192];

		/**
		 * Entpackt die restlichen Bytes von {@code in} auf genau {@code declared} Bytes.
		 *
		 * @param strict Länge muss stimmen (sonst Fehler wie Vanilla ab 1.17); ohne: fehlende Bytes = 0 (wie 1.8.9–1.16)
		 */
		public ByteBuf inflate(ByteBufAllocator alloc, ByteBuf in, int declared, boolean strict) throws DataFormatException {
			long t0 = System.nanoTime();
			int n = in.readableBytes();
			setInput(in, n);
			// Kleine Pakete: ein frisches Feld ist billiger als der Pool (so macht es auch Vanilla).
			ByteBuf out = declared <= SMALL ? Unpooled.buffer(Math.max(declared, 1)) : alloc.heapBuffer(declared);
			boolean ok = false;
			try {
				byte[] a = out.array();
				int off = out.arrayOffset() + out.writerIndex();
				int got = 0;
				while (got < declared) {
					int k = inflater.inflate(a, off + got, declared - got);
					if (k <= 0) break;
					got += k;
				}
				if (got != declared) {
					if (strict) {
						throw new DecoderException("Badly compressed packet - actual length of uncompressed payload " + got
								+ " is does not match declared size " + declared);
					}
					// 1.8.9–1.16 legten ein genulltes Feld an – Poolpuffer sind es nicht.
					out.setZero(out.writerIndex() + got, declared - got);
				}
				out.writerIndex(out.writerIndex() + declared);
				ok = true;
				return out;
			} finally {
				inflater.reset();
				in.skipBytes(n);
				if (!ok) out.release();
				NetBoost.STATS.inflated(n, declared, System.nanoTime() - t0);
			}
		}

		private void setInput(ByteBuf in, int n) {
			if (in.hasArray()) {
				inflater.setInput(in.array(), in.arrayOffset() + in.readerIndex(), n);
				return;
			}
			if (INFLATER_BB != null && in.nioBufferCount() == 1) {
				try {
					INFLATER_BB.invokeExact(inflater, in.nioBuffer(in.readerIndex(), n));
					return;
				} catch (Throwable ignored) {
					// Rückfall: kopieren.
				}
			}
			byte[] s = scratch(n);
			in.getBytes(in.readerIndex(), s, 0, n);
			inflater.setInput(s, 0, n);
		}

		private byte[] scratch(int n) {
			if (scratch.length >= n) return scratch;
			byte[] s = new byte[Math.max(n, scratch.length * 2)];
			if (s.length <= KEEP_SCRATCH) scratch = s;
			return s;
		}

		public void end() {
			inflater.end();
		}
	}

	/** Packen mit wiederverwendetem Deflater (Standard-Stufe wie Vanilla). */
	public static final class Deflate {
		private final Deflater deflater = new Deflater();
		private byte[] scratch = new byte[8192];
		private final byte[] staging = new byte[8192];

		/** Packt alle lesbaren Bytes von {@code in} und hängt sie an {@code out} an (ohne Längen-Präfix). */
		public void deflate(ByteBuf in, ByteBuf out) {
			long t0 = System.nanoTime();
			int n = in.readableBytes();
			try {
				setInput(in, n);
				deflater.finish();
				if (out.hasArray()) {
					while (!deflater.finished()) {
						out.ensureWritable(Math.max(256, n / 2 + 64));
						int k = deflater.deflate(out.array(), out.arrayOffset() + out.writerIndex(), out.writableBytes());
						out.writerIndex(out.writerIndex() + k);
					}
				} else {
					while (!deflater.finished()) {
						int k = deflater.deflate(staging);
						out.writeBytes(staging, 0, k);
					}
				}
			} finally {
				deflater.reset();
				in.skipBytes(n);
				NetBoost.STATS.deflated(n, System.nanoTime() - t0);
			}
		}

		private void setInput(ByteBuf in, int n) {
			if (in.hasArray()) {
				deflater.setInput(in.array(), in.arrayOffset() + in.readerIndex(), n);
				return;
			}
			if (DEFLATER_BB != null && in.nioBufferCount() == 1) {
				try {
					DEFLATER_BB.invokeExact(deflater, in.nioBuffer(in.readerIndex(), n));
					return;
				} catch (Throwable ignored) {
					// Rückfall: kopieren.
				}
			}
			byte[] s = scratch.length >= n ? scratch : new byte[Math.max(n, scratch.length * 2)];
			if (s != scratch && s.length <= KEEP_SCRATCH) scratch = s;
			in.getBytes(in.readerIndex(), s, 0, n);
			deflater.setInput(s, 0, n);
		}

		public void end() {
			deflater.end();
		}
	}
}
