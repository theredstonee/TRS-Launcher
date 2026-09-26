package dev.theredstonee.trsclient.core.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetCodecsTest {
	private static final PooledByteBufAllocator ALLOC = PooledByteBufAllocator.DEFAULT;

	/** Daten, die sich wie Chunk-Pakete packen lassen (Wiederholungen + etwas Rauschen). */
	static byte[] packetLike(int size, long seed) {
		Random r = new Random(seed);
		byte[] b = new byte[size];
		for (int i = 0; i < size; i++) b[i] = (byte) (r.nextInt(8) == 0 ? r.nextInt(256) : (i / 64) & 0x0F);
		return b;
	}

	static byte[] zlib(byte[] data) {
		Deflater d = new Deflater();
		d.setInput(data);
		d.finish();
		byte[] buf = new byte[data.length + 64];
		int n = d.deflate(buf);
		d.end();
		return Arrays.copyOf(buf, n);
	}

	static byte[] bytes(ByteBuf b) {
		byte[] out = new byte[b.readableBytes()];
		b.getBytes(b.readerIndex(), out);
		return out;
	}

	@Test
	void inflateMatchesTheOriginalFromHeapAndDirectBuffers() throws Exception {
		NetCodecs.Inflate inf = new NetCodecs.Inflate();
		for (int size : new int[]{300, 4096, 70_000, 900_000}) {
			byte[] data = packetLike(size, size);
			byte[] z = zlib(data);
			for (boolean direct : new boolean[]{false, true}) {
				ByteBuf in = direct ? Unpooled.directBuffer(z.length) : Unpooled.buffer(z.length);
				in.writeBytes(z);
				ByteBuf out = inf.inflate(ALLOC, in, data.length, true);
				assertArrayEquals(data, bytes(out), "size " + size + " direct " + direct);
				assertEquals(0, in.readableBytes());
				out.release();
				in.release();
			}
		}
		inf.end();
	}

	@Test
	void wrongDeclaredLengthIsAnErrorOrZeroFilledLikeTheVersion() throws Exception {
		NetCodecs.Inflate inf = new NetCodecs.Inflate();
		byte[] data = packetLike(1000, 1);
		byte[] z = zlib(data);
		// Ab 1.17: Fehler.
		assertThrows(DecoderException.class, () -> inf.inflate(ALLOC, Unpooled.wrappedBuffer(z), 1200, true));
		// 1.8.9–1.16: Rest bleibt 0 (Vanilla legte ein neues, genulltes Feld an).
		ByteBuf out = inf.inflate(ALLOC, Unpooled.wrappedBuffer(z), 1200, false);
		byte[] got = bytes(out);
		assertEquals(1200, got.length);
		assertArrayEquals(data, Arrays.copyOf(got, 1000));
		for (int i = 1000; i < 1200; i++) assertEquals(0, got[i]);
		out.release();
	}

	@Test
	void deflateProducesZlibThatInflatesBack() throws Exception {
		NetCodecs.Deflate def = new NetCodecs.Deflate();
		for (int size : new int[]{256, 5000, 200_000}) {
			byte[] data = packetLike(size, 7 + size);
			for (boolean directIn : new boolean[]{false, true}) {
				for (boolean directOut : new boolean[]{false, true}) {
					ByteBuf in = directIn ? Unpooled.directBuffer(size) : Unpooled.buffer(size);
					in.writeBytes(data);
					ByteBuf out = directOut ? Unpooled.directBuffer(16) : Unpooled.buffer(16);
					def.deflate(in, out);
					assertEquals(0, in.readableBytes());
					Inflater inf = new Inflater();
					inf.setInput(bytes(out));
					byte[] back = new byte[size];
					assertEquals(size, inf.inflate(back));
					assertTrue(inf.finished());
					assertArrayEquals(data, back);
					in.release();
					out.release();
				}
			}
		}
		def.end();
	}

	@Test
	void varIntRoundTrip() {
		ByteBuf b = Unpooled.buffer();
		int[] values = {0, 1, 127, 128, 255, 2097151, 2097152, Integer.MAX_VALUE, -1};
		for (int v : values) NetCodecs.writeVarInt(b, v);
		for (int v : values) assertEquals(v, NetCodecs.readVarInt(b));
		ByteBuf bad = Unpooled.wrappedBuffer(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 1});
		assertThrows(DecoderException.class, () -> NetCodecs.readVarInt(bad));
	}

	// --- CFB8 ---

	static Cipher jdk(int mode, byte[] key) throws Exception {
		Cipher c = Cipher.getInstance("AES/CFB8/NoPadding");
		c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
		return c;
	}

	@Test
	void fastCfb8DecryptsExactlyLikeTheJdkAcrossAnySplit() throws Exception {
		Random r = new Random(42);
		for (int round = 0; round < 20; round++) {
			byte[] key = new byte[16];
			r.nextBytes(key);
			byte[] plain = new byte[r.nextInt(20_000) + 1];
			r.nextBytes(plain);
			byte[] cipherText = jdk(Cipher.ENCRYPT_MODE, key).update(plain);
			FastCfb8 f = new FastCfb8(key, key);
			byte[] out = new byte[plain.length];
			int pos = 0;
			while (pos < plain.length) {
				int n = Math.min(plain.length - pos, r.nextInt(5000) + 1);
				f.decrypt(cipherText, pos, n, out, pos);
				pos += n;
			}
			assertArrayEquals(plain, out, "Runde " + round);
		}
	}

	@Test
	void fastCfb8WorksInPlaceAndWithTinyPieces() throws Exception {
		byte[] key = new byte[16];
		new Random(3).nextBytes(key);
		byte[] plain = packetLike(3000, 3);
		byte[] buf = jdk(Cipher.ENCRYPT_MODE, key).update(plain);
		FastCfb8 f = new FastCfb8(key, key);
		for (int i = 0; i < buf.length; i += 7) f.decrypt(buf, i, Math.min(7, buf.length - i), buf, i);
		assertArrayEquals(plain, buf);
	}

	@Test
	void selfTestPassesOnThisJvm() throws Exception {
		byte[] key = new byte[16];
		new Random(9).nextBytes(key);
		assertTrue(NetBoost.selfTest(key));
	}
}
